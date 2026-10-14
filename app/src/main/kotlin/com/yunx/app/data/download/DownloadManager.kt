package com.yunx.app.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.LogRedactor
import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.security.AndroidKeystoreCredentialCipher
import com.yunx.app.data.security.CredentialCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** 实时下载统计（用于 UI 展示速度/剩余时间/线程数） */
data class DownloadStats(
    val speed: Long = 0L,         // 字节/秒
    val remainMillis: Long = -1L, // 剩余时间（毫秒），未知为 -1
    val chunkCount: Int = 1,      // 分片（线程）数
    val mergePercent: Int = -1,
    val phase: String = ""
)

private const val TAG = "YunX-DL"

/** 单文件 Range 分片的安全并发上限。迅雷等 CDN 对单文件并发 Range 有阈值，
 *  超过约 8 个并发会把多余请求降级为 200 整文件（忽略 Range），
 *  进而触发整任务回退单流、速度暴跌。压在安全上限内，所有分片都能稳定拿到 206。 */
private const val RANGE_WORKERS_CAP = 8

/** 错峰建连上限（序号）：第 i 个分片首次请求前延迟 (min(i, STAGGER_CAP) * STAGGER_MS) */
private const val STAGGER_CAP = 8
private const val STAGGER_MS = 25L

/** 慢连接抢占的采样间隔：看门狗每隔这么久刷新一次每路瞬时速度，再据此判定是否换连接 */
private const val PREEMPT_TICK_MS = 5_000L

// ---------- ★ 慢连接抢占（治「收尾塌到 KB 级」，勿删）----------
// 网盘 CDN 是**按连接**限速的，且个别连接会落在慢节点上：真机日志（70.6MB 文件）实测多数连接
// 40~80KB/s，少数只有 3~7KB/s。慢分片如果正好是收尾时唯一在跑的那几路，总速就塌到 KB 级
// （日志实测：64 路里 61 路空转 37 秒，只为等最后 500KB）。抢占 = 断开这条慢连接、换一条新连接续传。
/** 判定「慢连接」的绝对下限：低于 12KB/s 才算慢（实测正常连接 40~80KB/s） */
private const val PREEMPT_MIN_BPS = 12 * 1024L
/** 分片至少跑这么久才允许被抢占（避开建连与 TCP 爬坡期，否则会误杀刚起步的正常分片） */
private const val PREEMPT_MIN_AGE_MS = 15_000L
/** 同一分片两次抢占之间的冷却期（换完连接要给新连接爬坡时间，避免反复重连） */
private const val PREEMPT_COOLDOWN_MS = 10_000L
/** 剩余不足这个数就不再折腾（换连接本身也有握手成本） */
private const val PREEMPT_MIN_REMAIN = 128 * 1024L
/** 单个分片最多被抢占几次：全局都慢时（如整站限速）避免无意义的重连风暴 */
private const val PREEMPT_MAX = 3
/** 每轮看门狗最多抢占几路，避免同一时刻大批连接同时重建 */
private const val PREEMPT_PER_TICK = 2
/** 收尾判定：在飞分片不超过这个数就认为「其余连接已无事可做」，放宽抢占门槛（真机日志：收尾只剩 1~3 路在磨） */
private const val PREEMPT_ENDGAME_INFLIGHT = 3
/** 收尾时的最小存活时间：比常规门槛短得多，但也要避开刚建连的爬坡期 */
private const val PREEMPT_ENDGAME_MIN_AGE_MS = 3_000L

/** 暂停/删除时等待任务协程退出的上限（毫秒）：阻塞式 IO 不响应取消，不能无限等，否则按钮「点不动」 */
private const val JOB_EXIT_WAIT_MS = 10_000L

/**
 * 一个在飞分片的采样状态（慢连接抢占的判定依据，**永久结构，勿删**）。
 *
 * 累加已收字节（每个读块一次 `AtomicLong.addAndGet`，开销可忽略）、记住起点/块大小，供看门狗协程
 * 每 PREEMPT_TICK_MS 刷新一次瞬时速度（[lastBps]）；[preempt] / [preemptCount] / [lastPreemptAtMs]
 * 决定该路是否换连接续传——删掉它们收尾长尾就会回来（见 PREEMPT_MIN_BPS 注释）。
 */
private class InflightChunk(val start: Long, val size: Long) {
    val bytes = AtomicLong(0L)
    val startedAtMs = System.currentTimeMillis()

    /** 上一次快照时的字节数与时刻（只由看门狗协程读写） */
    var lastBytes = 0L
    var lastAtMs = startedAtMs

    /** 本次快照算出的瞬时速度（只由看门狗协程读写） */
    var lastBps = 0L

    /** ★ 慢连接抢占标志：置位后 ChunkDownloader 断开当前连接、换新连接从已收字节续传（不丢数据） */
    val preempt = AtomicBoolean(false)

    /** 本分片已被抢占次数（上限 PREEMPT_MAX） */
    var preemptCount = 0

    /** 上次被抢占的时刻（冷却期用） */
    var lastPreemptAtMs = 0L

    val elapsedMs: Long get() = System.currentTimeMillis() - startedAtMs
}

/**
 * 下载任务管理器：
 * - 任务持久化（Room），状态流转 PENDING → DOWNLOADING → COMPLETED / PAUSED / FAILED；
 * - 分片多线程下载（每片一个协程，信号量限并发）；
 * - 断点续传：part 文件保留，暂停/重启后从已有大小继续；
 * - 完成后合并分片并保存到公共 Download 目录。
 */
class DownloadManager(
    private val context: Context,
    private val dao: DownloadTaskDao,
    private val downloader: ChunkDownloader,
    /** 下载线程数提供者（按平台，可在设置中修改，动态生效），默认 32 */
    private val threadProvider: (String) -> Int = { 32 },
    /** 自定义下载保存目录提供者（SAF tree Uri，可空）；null 时保存到系统默认 Download */
    private val saveDirProvider: () -> String? = { null },
    /** 最大同时下载任务数提供者（默认 3）：限制后台并发任务，避免占满带宽/耗尽路由器连接 */
    private val concurrencyProvider: () -> Int = { 3 },
    /** 全局下载速度限制提供者（字节/秒；0 = 不限速） */
    private val speedLimitProvider: () -> Long = { 0L },
    /** 下载失败后自动重试次数提供者（默认 3，上限 10） */
    private val retryCountProvider: () -> Int = { 3 },
    /** 仅 Wi-Fi 下载开关；开启时移动网络进入等待，切回 Wi-Fi 自动续传。 */
    private val wifiOnlyProvider: () -> Boolean = { false },
    /** 锁屏后保持下载开关（开启时获取 WakeLock 维持 Wi-Fi/CPU） */
    private val keepWhenLockedProvider: () -> Boolean = { true },
    /** 通知栏显示下载速度开关（false 时仅显示通知，隐藏速度） */
    private val showSpeedProvider: () -> Boolean = { true }
) {
    /**
     * ★ 必须与其他调用方共用同一个 cipher 实例（见 [AndroidKeystoreCredentialCipher.shared]）：
     *   实例各自缓存密钥、各自持有失钥监听，分开建会在「密钥刚被重建」时判断错位。
     */
    private val credentialCipher: CredentialCipher = AndroidKeystoreCredentialCipher.shared
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 下载引擎开关等设置（DownloadManager 是应用级单例，读一次足够） */
    private val settings by lazy { SettingsRepository(context) }

    /** 引擎任务 ID 的内存索引：DB 的 `engineTaskId` 是持久化真源，这里只给 pause/start/remove 做同步判断 */
    private val taskEngineIds = ConcurrentHashMap<Long, String>()

    /**
     * 已经计入「前台服务保活」的引擎任务。
     *
     * 引擎任务不走 [onTaskStarted]/[onTaskFinished] 的下载协程路径，保活要自己配对：**加入时**才
     * 拉起前台服务 + WakeLock，**移除时**才允许释放。用集合的 add/remove 返回值去重，
     * 保证无论从哪条路径终结（完成 / 失败 / 用户暂停 / 用户删除 / 引擎侧自己变 pause）都只扣一次。
     */
    private val engineKeepAliveIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** 引擎任务进度同步协程（同一时刻只跑一个；没有可同步任务时自己退出） */
    private var engineSyncJob: Job? = null

    /** 引擎任务同步间隔（毫秒）：引擎调用是本进程内分发，不存在网络开销，可以问得勤一点 */
    private val engineSyncIntervalMs = 700L



    /** 当前实际下载中的任务数（用于最大同时下载任务数限制） */
    private val sharedSlots = SharedDownloadSlots()
    private val engineLaunchJobs = ConcurrentHashMap<Long, Job>()

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒 */
    private val speedLimiter = SpeedLimiter()

    /** Shared across tasks: Java read-buffer budget plus a conservative 128 thread/TLS ceiling.
     * HTTP/1.1 retains TCP backpressure. Task admission must occur before this permit.
     * Never release manually or replace this with a per-task limiter.
     */
    private val inflightLimiter = Semaphore(MAX_INFLIGHT_CHUNKS)

    /**
     * 保存前存储权限检查（Android 9- 写公共 Download 需 WRITE_EXTERNAL_STORAGE 运行时授权）。
     * UI 层注入：无权限时动态申请并等待授权结果；已授权/Android 10+ 直接返回 true。
     * 授权后会自动继续保存（同一协程 await 授权结果再往下走）。
     */
    var storagePermissionProvider: suspend () -> Boolean = { true }

    /** 临时下载直链刷新器：仅使用任务持久化的合法来源身份重新调用对应网盘 API。 */
    var sourceRefresher: DownloadSourceRefresher = { null }

    /**
     * 运行中的任务 Job：value 为 CompletableDeferred，注册/移除全程由 jobsLock 保护，
     * 保证 start/pause/remove 之间无 TOCTOU 竞态（防止"暂停/删除瞬间任务继续跑"）。
     */
    private val activeJobs = ConcurrentHashMap<Long, CompletableDeferred<Job>>()
    private val jobsLock = Any()

    /** 前台服务计数：有任务在下载时保持前台（避免切后台限速/进程被杀） */
    private val activeTaskCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 前台通知进度节流（毫秒）：2 秒更新一次，避免频繁刷新系统通知 */
    private val notifyThrottleMs = 2000L
    private val lastNotifyTs = AtomicLong(0)

    /** 合并阶段进度上报节流（毫秒）：分片合并回调很密，百分比不变时最多这么久报一次 */
    private val mergeReportIntervalMs = 300L

    /** 更新前台通知进度（2 秒节流；total<=0 时不确定进度，只更新标题；可显示下载速度） */
    private fun notifyProgress(id: Long, fileName: String, new: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTs.get() >= notifyThrottleMs) {
            lastNotifyTs.set(now)
            // ★ 内存观测（复用同一条 2 秒节流，无额外开销）：已用堆超 3/4 时打一行，
            //   让「日志导出」带上 OOM 前的堆时间线，事后不依赖 HPROF 也能看出趋势
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            if (used * 4 > rt.maxMemory() * 3) {
                Log.w(TAG, "内存高压 used=${used / 1024 / 1024}MB max=${rt.maxMemory() / 1024 / 1024}MB " +
                    "tasks=${_stats.value.size} inflightCap=$MAX_INFLIGHT_CHUNKS " +
                    "inflightUsed=${MAX_INFLIGHT_CHUNKS - inflightLimiter.availablePermits}")
            }
            val percent = if (total > 0) ((new * 100 / total).toInt().coerceIn(0, 100)) else -1
            val speed = _stats.value[id]?.speed ?: 0L
            val speedText = if (speed > 0) formatSpeed(speed) else ""
            DownloadService.update(context, fileName, percent, speedText, showSpeedProvider())
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSec.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }

    /**
     * 刷新每路在飞分片的瞬时速度（抢占判定的依据，不产生日志）。
     *
     * 每个采样周期调一次：`lastBps = 本周期新增字节 / 本周期耗时`，随后由 [preemptSlowChunks]
     * 挑出「远低于同伴」的那几路换连接续传。真实速度必须来自窗口差值（累计均速看不出刚变慢的连接）。
     */
    private fun sampleInflightChunks(diag: Map<String, InflightChunk>) {
        val now = System.currentTimeMillis()
        for (d in diag.values) {
            val bytes = d.bytes.get()
            d.lastBps = (bytes - d.lastBytes).coerceAtLeast(0L) * 1000 / (now - d.lastAtMs).coerceAtLeast(1L)
            d.lastBytes = bytes
            d.lastAtMs = now
        }
    }

    /**
     * ★ 慢连接抢占（永久逻辑）：把「跑得远低于同伴」的在飞分片换到新连接上续传。
     *
     * 判定阈值 = max(绝对下限 PREEMPT_MIN_BPS, 当前同伴窗口速度中位数 / 2)：
     * 用相对值是为了适配不同 CDN 的限速档次（夸克单连接几十 KB/s、迅雷更低），绝对下限兜住
     * 「收尾只剩一两路、平均值被自己拉低」的退化情况。命中后只把 [InflightChunk.preempt] 置位，
     * 由 ChunkDownloader 断开连接并从已收字节续传——不丢数据、不退避、不改变对外结果。
     *
     * 每轮最多抢 PREEMPT_PER_TICK 路、单路最多 PREEMPT_MAX 次，避免全局慢时变成重连风暴。
     * 收尾（在飞 ≤ PREEMPT_ENDGAME_INFLIGHT）时放宽年龄/剩余两条门槛——真机日志里最后几百 KB
     * 常常只剩 1~3 路在磨，那几路的瞬时速度就是用户看到的「掉到 KB 级」。
     */
    private fun preemptSlowChunks(
        id: Long,
        diag: Map<String, InflightChunk>
    ) {
        if (diag.isEmpty()) return
        // Use the current peer window, not all historical bytes divided by current tail workers.
        val peerSpeeds = diag.values.map { it.lastBps }.sorted()
        val median = peerSpeeds.getOrNull(peerSpeeds.size / 2) ?: return
        val floor = maxOf(PREEMPT_MIN_BPS, median / 2)
        val now = System.currentTimeMillis()
        var taken = 0
        for (d in diag.values.sortedBy { it.lastBps }) {
            if (taken >= PREEMPT_PER_TICK) break
            if (d.preemptCount >= PREEMPT_MAX) continue
            if (now - d.lastPreemptAtMs < PREEMPT_COOLDOWN_MS) continue
            // 收尾（在飞 ≤ PREEMPT_ENDGAME_INFLIGHT）时放宽「跑够久」和「剩余够多」两条门槛：
            // 此时其余连接已经没活干，再等下去毫无意义，重连握手（~0.5s）远比慢连接磨完剩余字节便宜。
            if (diag.size <= PREEMPT_ENDGAME_INFLIGHT) {
                if (d.elapsedMs < PREEMPT_ENDGAME_MIN_AGE_MS) continue
            } else {
                if (d.elapsedMs < PREEMPT_MIN_AGE_MS) continue
                if (d.size - d.bytes.get() < PREEMPT_MIN_REMAIN) continue
            }
            if (d.lastBps >= floor) continue
            d.preemptCount++
            d.lastPreemptAtMs = now
            d.preempt.set(true)
            taken++
            Log.w(TAG, "runTask: id=$id 抢占慢连接 起点=${d.start} 块=${diagSize(d.size)} 已收=${diagSize(d.bytes.get())} " +
                "瞬时=${formatSpeed(d.lastBps)} 阈值=${formatSpeed(floor)} 第${d.preemptCount}/$PREEMPT_MAX 次（换连接续传）")
        }
    }

    /** 字节数转可读文本（抢占日志用） */
    private fun diagSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2fGB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format("%.1fMB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    /**
     * 进度落盘节流：多 worker 并发回调下，每 progressPersistIntervalMs 最多写一次 DB。
     * - force / (total>0 且 new>=total)：完成时强制写，确保最终进度准确；
     * - total<=0（大小未知）时仅按时间节流；
     * - 用 lastAt 的 CAS 保证并发下同一任务只有一个回调写库（避免多线程重复 UPDATE）。
     */
    private suspend fun persistProgressIfDue(
        id: Long,
        new: Long,
        total: Long,
        force: Boolean,
        lastAt: AtomicLong
    ) {
        val now = System.currentTimeMillis()
        val last = lastAt.get()
        if (force || (total > 0 && new >= total) || now - last >= progressPersistIntervalMs) {
            if (lastAt.compareAndSet(last, now)) {
                dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
            }
        }
    }

    /** 完成任务并写入平均速度（字节/秒）：avg = total / 本次运行耗时 */
    private suspend fun completeWithAvg(id: Long, savedPath: String, total: Long) {
        val start = taskStartTimes.remove(id) ?: 0L
        val elapsedSec = ((System.currentTimeMillis() - start) / 1000.0).coerceAtLeast(1.0)
        val avg = if (total > 0 && elapsedSec > 0) (total / elapsedSec).toLong() else 0L
        DiagnosticLog.dbOp("download_task", "complete", rowsOf = { -1 }) {
            dao.complete(id, DownloadTaskEntity.STATUS_COMPLETED, savedPath, avg)
        }
    }

    /** 每个任务一把互斥锁：暂停后立即恢复时避免新旧协程并发写分片 */
    private val taskLocks = ConcurrentHashMap<Long, Mutex>()

    /** 任务请求头（Cookie/UA），暂停后恢复仍需使用 */
    private val taskHeaders = ConcurrentHashMap<Long, Map<String, String>>()

    /** 已知文件大小（API 返回，避免探测失败）；-1 表示未知 */
    private val taskSizes = ConcurrentHashMap<Long, Long>()

    /** 镜像主 URL 的回退直连（仅 GitHub 等显式传入）；主 URL 探测失败时整任务切到此 URL 重下 */
    private val taskFallbackUrls = ConcurrentHashMap<Long, String>()

    /** 任务开始时间（毫秒）：完成时计算平均速度用（暂停/恢复会重置，表示最近一次运行段均值） */
    private val taskStartTimes = ConcurrentHashMap<Long, Long>()

    /** 任务下载完成后的清理回调（如删除网盘临时转存文件；下载成功后才触发） */
    private val taskCallbacks = ConcurrentHashMap<Long, suspend () -> Unit>()

    /** 实时下载统计（速度/剩余时间/线程数） */
    private val _stats = MutableStateFlow<Map<Long, DownloadStats>>(emptyMap())
    val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    private fun updatePhase(id: Long, phase: String) {
        _stats.update { current ->
            val old = current[id] ?: DownloadStats()
            current + (id to old.copy(phase = phase))
        }
    }

    /** 进度落盘节流（毫秒）：updateProgress 写库会触发全表 Flow 重发 → 主线程全列表重组；
     *  按字节（256KB）节流时高速下载每秒写库几十次，主线程重组洪峰 → ANR。
     *  改为按时间节流落盘，UI 进度由内存 _stats 高频展示、DB 低频持久化（断点续传最多丢几百 ms 进度）。 */
    private val progressPersistIntervalMs = 500L

    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /** 入队并立即开始下载 */
    suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        /** 已知文件大小（字节）；-1 表示未知，需探测 */
        size: Long = -1L,
        /** 下载来源平台标识（按平台应用下载线程数设置）；通用/手动添加传空串 */
        platform: String = "",
        sourceFileId: String = "",
        sourceType: String = "",
        sourceContext: String = "",
        urlExpiresAt: Long = 0L,
        etag: String = "",
        lastModified: String = "",
        /** 镜像主 URL 不可达时的回退直连（默认空=不回退）；仅 GitHub 等镜像下载传入 */
        fallbackUrl: String = "",
        /** 下载成功完成后的清理回调（如删除网盘临时转存文件）；失败/取消不触发。
         * 注意：必须是最后一个参数（调用点有大量尾随 lambda 用法，放它之后会编译失败）。 */
        onComplete: suspend () -> Unit = {}
    ): Long {
        // 磁力链接只能交给 Gopeed 引擎（见 magnetBlockReason）；平台标识同时落库，便于事后区分
        val isMagnet = MagnetLink.isMagnet(url)
        val taskPlatform = if (isMagnet) DownloadPlatform.MAGNET else platform
        // 文件名兜底：空白时从 URL 推导，避免保存时变成时间戳。
        // 磁力链接走 dn= 里自带的显示名（元数据到手后再换成真正的种子名，见 completeEngineTask）
        val safeName = fileName.ifBlank {
            if (isMagnet) {
                MagnetLink.displayName(url)
            } else {
                url.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "download_${System.currentTimeMillis()}" }
            }
        }
        Log.d(TAG, "enqueue: origin=${LogRedactor.url(url)} fileName=$safeName headers=${headers.keys} size=$size")
        val id = DiagnosticLog.dbOp("download_task", "insert", rowsOf = { 1 }) {
            dao.insert(
                DownloadTaskEntity(
                    url = url, fileName = safeName, requestHeadersJson = encodeHeaders(headers),
                    platform = taskPlatform, sourceFileId = sourceFileId, sourceType = sourceType,
                    sourceContext = sourceContext, urlExpiresAt = urlExpiresAt,
                    etag = etag, lastModified = lastModified
                )
            )
        }
        // 诊断日志：任务入队（下载模块记体积/平台，操作模块记一笔「用户加了任务」）
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "task_enqueue", taskId = id, status = "pending",
            size = size.takeIf { it > 0 },
            summary = "platform=$taskPlatform name=$safeName url=${LogRedactor.url(url)}"
        )
        DiagnosticLog.event(
            DiagnosticLog.OPERATION, "download_enqueue",
            "id=$id platform=$taskPlatform size=$size name=$safeName"
        )
        // 保存请求头（Cookie/UA），暂停后恢复仍需携带
        if (headers.isNotEmpty()) taskHeaders[id] = headers
        if (size > 0) taskSizes[id] = size
        if (fallbackUrl.isNotBlank()) taskFallbackUrls[id] = fallbackUrl
        taskCallbacks[id] = onComplete
        // 磁力但引擎不可用：立刻落一条**带原因的失败任务**并结束。若照常走内置下载器，
        // 它会把 magnet: 当普通 URL 发 HTTP 请求，最后抛一个和「该去导入内核」毫无关系的协议错误。
        val blocked = magnetBlockReason(isMagnet)
        if (blocked != null) {
            Log.w(TAG, "磁力任务被拦下：id=$id 原因=$blocked")
            taskCallbacks.remove(id)
            // 不会有下载协程来消费这几份内存数据了，直接清掉
            taskHeaders.remove(id)
            taskSizes.remove(id)
            taskFallbackUrls.remove(id)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, blocked)
            DownloadService.notifyResult(
                context, id, safeName, success = false, error = blocked, promote = showSpeedProvider()
            )
            return id
        }
        if (shouldUseEngine(taskPlatform)) {
            // 任务登记、下载页展示、完成清理回调都与内置下载器完全一致，只有「执行」交给 Gopeed 引擎
            launchEngineTask(id) { startViaEngine(id, url, safeName, headers, taskPlatform) }
        } else {
            start(id, headers)
        }
        return id
    }

    suspend fun enqueue(
        source: CloudDownloadSource,
        onComplete: suspend () -> Unit = {}
    ): Long = enqueue(
        url = source.url,
        fileName = source.fileName,
        headers = source.headers,
        size = source.fileSize,
        platform = source.platform,
        sourceFileId = source.sourceFileId,
        sourceType = source.sourceType,
        sourceContext = source.sourceContext,
        urlExpiresAt = source.urlExpiresAt,
        etag = source.etag,
        lastModified = source.lastModified,
        onComplete = onComplete
    )

    /** 是否把任务交给 Gopeed 引擎：设置里选了引擎 + 引擎已导入；GitHub 走镜像回退（引擎不支持），始终用内置下载器 */
    private fun shouldUseEngine(platform: String): Boolean =
        platform != DownloadPlatform.GITHUB &&
            settings.downloadEngine == SettingsRepository.ENGINE_GOPEED &&
            GopeedEngine.isInstalled(context)

    /**
     * 磁力链接是否可下：返回 null = 可以（交给引擎），否则是给用户看的拦截原因。
     *
     * 磁力（BT）只有 Gopeed 内核里有实现（内核注册了 hls/http/bt/ed2k 四个协议），内置分片下载器
     * 是纯 HTTP Range 实现，喂它 magnet: 只会得到没意义的报错 —— 所以这里宁可拦住并说清楚要做什么。
     */
    private fun magnetBlockReason(isMagnet: Boolean): String? {
        if (!isMagnet) return null
        if (!GopeedEngine.isInstalled(context)) {
            return "磁力下载需要先在「设置 → 下载 → 下载引擎」里导入 Gopeed 内核"
        }
        if (settings.downloadEngine != SettingsRepository.ENGINE_GOPEED) {
            return "磁力下载需要先在「设置 → 下载」里把下载引擎切换为 Gopeed"
        }
        return null
    }

    /**
     * 用 Gopeed 引擎开始下载：确保引擎在跑 → 建任务 → 引擎任务 ID 写回本地记录 → 拉起进度同步。
     *
     * 建任务失败按普通失败任务落库（errorMsg 写引擎原文），不做静默回退到内置下载器——
     * 用户明确选了引擎，悄悄换下载器比报错更难排查。
     */
    private suspend fun startViaEngine(
        id: Long,
        url: String,
        fileName: String,
        headers: Map<String, String>,
        platform: String
    ) {
        var createdEngineId: String? = null
        val engineTaskId = try {
            withContext(Dispatchers.IO) {
                val dir = GopeedEngine.resolveDownloadDir(context)
                if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) {
                    GopeedEngine.start(context, dir)
                }
                GopeedEngine.createTask(
                    url = url,
                    saveDir = dir,
                    headers = headers,
                    // 磁力（BT）不吃连接数：那是 http 协议的分片并发参数
                    connections = if (platform == DownloadPlatform.MAGNET) 0 else threadProvider(platform),
                    // 磁力在元数据到手前没有名字：交给引擎自己命名（种子名稍后写回本地记录）
                    name = if (platform == DownloadPlatform.MAGNET) "" else fileName,
                    labels = mapOf("yunxTaskId" to id.toString())
                ).also { createdEngineId = it }
            }
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                createdEngineId?.let { engineId -> runCatching { GopeedEngine.deleteTask(engineId) } }
            }
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "引擎建任务失败：id=$id ${e.message}", e)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, DownloadFailurePolicy.userMessage(e))
            sharedSlots.release(id)
            if (engineKeepAliveIds.remove(id)) onTaskFinished()
            return
        }
        Log.d(TAG, "引擎任务已创建：yunxId=$id engineId=$engineTaskId")
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "engine_task_created", taskId = id, status = "downloading",
            summary = "engineId=$engineTaskId platform=$platform name=$fileName"
        )
        taskEngineIds[id] = engineTaskId
        // 记录本段运行起点：完成时用它算平均速度
        taskStartTimes[id] = System.currentTimeMillis()
        // 保活：第一个引擎任务拉起前台服务 + WakeLock，锁屏/退后台引擎才不会被系统收掉
        if (engineKeepAliveIds.add(id)) onTaskStarted(id)
        dao.updateEngineTaskId(id, engineTaskId)
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        startEngineSync()
    }

    /**
     * 引擎任务进度同步：把引擎侧状态回写到本地记录（下载页读的还是 Room，所以必须回写），
     * 完成时触发 onComplete 清理回调。没有可同步任务就自动退出，下次建引擎任务时再拉起。
     *
     * 每轮先确保引擎在运行：进程重启后引擎里可能还有未完成任务（引擎自己持久化在 bolt 里），
     * 引擎重启后这些任务一般是 pause 状态，会被回写成本地「已暂停」，由用户决定是否继续。
     */
    private fun startEngineSync() {
        if (engineSyncJob?.isActive == true) return
        engineSyncJob = scope.launch {
            while (isActive) {
                val pending = runCatching { dao.listSyncableEngineTasks() }.getOrDefault(emptyList())
                if (pending.isEmpty()) return@launch
                if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            GopeedEngine.start(context, GopeedEngine.resolveDownloadDir(context))
                        }
                    }.onFailure { Log.e(TAG, "引擎同步时启动引擎失败：${it.message}", it) }
                }
                for (task in pending) {
                    taskEngineIds[task.id] = task.engineTaskId
                    val status = runCatching {
                        withContext(Dispatchers.IO) { GopeedEngine.taskStatus(task.engineTaskId) }
                    }.getOrNull() ?: continue
                    when (status.status) {
                        "done" -> completeEngineTask(task, status.total)
                        "error" -> {
                            Log.e(TAG, "引擎任务失败：yunxId=${task.id} engineId=${task.engineTaskId}")
                            DiagnosticLog.error(
                                DiagnosticLog.DOWNLOAD, "engine_task_failed", code = "ENGINE_ERROR",
                                summary = "task=${task.id} engineId=${task.engineTaskId} name=${task.fileName}"
                            )
                            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_FAILED)
                            dao.updateError(task.id, "Gopeed 引擎下载失败")
                            _stats.update { it - task.id }
                            taskEngineIds.remove(task.id)
                            taskStartTimes.remove(task.id)
                            releaseEngineTaskMemory(task.id)
                            if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
                            DownloadService.notifyResult(
                                context, task.id, task.fileName,
                                success = false, error = "Gopeed 引擎下载失败",
                                promote = showSpeedProvider()
                            )
                        }
                        "pause" -> {
                            if (engineLaunchJobs[task.id]?.isActive == true) continue
                            sharedSlots.release(task.id)
                            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PAUSED)
                            _stats.update { it - task.id }
                            // 引擎侧自己变暂停（进程重启后未续传等）：保活到此为止，用户点继续时会重新拉起
                            if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
                        }
                        "wait" -> {
                            if (!sharedSlots.contains(task.id)) {
                                runCatching { withContext(Dispatchers.IO) { GopeedEngine.pauseTask(task.engineTaskId) } }
                                dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PAUSED)
                                continue
                            }
                            // 超出「最大同时下载任务数」，被引擎排在 waitTasks 里等空位（上限见 GopeedEngine.applyRuntimeConfig）
                            // → 本地记成「等待中」。★ 不能落到下面的 else：那会显示成 0% 的「下载中」，看着像卡死。
                            // 进度一律不动（排队中引擎报的是 0）：任务被挤出后重新上车时，库里的进度还是上次那份。
                            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PENDING)
                            _stats.update { it - task.id }
                            DiagnosticLog.log(
                                DiagnosticLog.DOWNLOAD, "task_queued", taskId = task.id, status = "wait",
                                summary = "engineId=${task.engineTaskId} 超出最大同时下载任务数，引擎排队中"
                            )
                        }
                        else -> {
                            if (!sharedSlots.contains(task.id)) {
                                withContext(Dispatchers.IO) { GopeedEngine.pauseTask(task.engineTaskId) }
                                dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PAUSED)
                                continue
                            }
                            val total = if (status.total > 0L) status.total else task.totalSize
                            dao.updateProgress(
                                task.id,
                                DownloadTaskEntity.STATUS_DOWNLOADING,
                                status.downloaded,
                                total
                            )
                            // 实时速度直接取引擎给的（TaskRuntimeStatus.speed），剩余时间自己算
                            _stats.update {
                                it + (task.id to DownloadStats(
                                    speed = status.speed,
                                    remainMillis = if (status.speed > 0L && total > status.downloaded) {
                                        (total - status.downloaded) * 1000L / status.speed
                                    } else {
                                        -1L
                                    },
                                    chunkCount = threadProvider(task.platform).coerceAtLeast(1)
                                ))
                            }
                            // 前台通知：与内置下载器共用同一条 2 秒节流和速度来源（_stats），所以写在 _stats 之后
                            notifyProgress(task.id, task.fileName, status.downloaded, total)
                        }
                    }
                }
                delay(engineSyncIntervalMs)
            }
        }
    }

    /** 引擎任务完成：状态/保存路径/平均速度落库 + 触发清理回调（语义与内置下载器的完成路径一致） */
    private suspend fun completeEngineTask(task: DownloadTaskEntity, size: Long) {
        val dir = GopeedEngine.resolveDownloadDir(context)
        // 磁力（BT）任务的真实名字/落盘结构只有引擎解析完元数据才知道：
        // 引擎侧 TorrentDirMaker 用 opts.path、FilePathMaker 返回「种子名/子路径」⇒ 多文件种子落成
        // <下载目录>/<种子名>/...，单文件种子落成 <下载目录>/<种子名>。本地记录里的 fileName 是元数据
        // 到手前自己起的显示名，直接拿它拼路径会指向一个不存在的文件（打开/删除本地文件全会错）。
        // 非磁力任务读一次详情也无害（名字与 fileName 一致，多一次 GET 而已），所以不做分支区别对待。
        val detail = runCatching { withContext(Dispatchers.IO) { GopeedEngine.taskDetail(task.engineTaskId) } }
            .onFailure { Log.w(TAG, "读取引擎任务详情失败，用本地文件名兜底：id=${task.id} ${it.message}") }
            .getOrNull()
        val realName = detail?.name?.takeIf { it.isNotBlank() } ?: task.fileName
        val output = File(dir, realName)
        val savedPath = output.absolutePath
        val outputError = GopeedEngine.completedOutputError(output, size, detail?.folder ?: output.isDirectory)
        if (outputError != null) {
            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(task.id, outputError)
            _stats.update { it - task.id }
            taskStartTimes.remove(task.id)
            taskEngineIds.remove(task.id)
            releaseEngineTaskMemory(task.id)
            if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
            return
        }
        // 平均速度 = 引擎给出的总大小 ÷ 本段运行时长（暂停/继续会重置起点，与内置下载器口径一致）
        val startedAt = taskStartTimes.remove(task.id) ?: 0L
        val avgSpeed = if (startedAt > 0L && size > 0L) {
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            size * 1000L / elapsed
        } else {
            0L
        }
        dao.complete(task.id, DownloadTaskEntity.STATUS_COMPLETED, savedPath, avgSpeed)
        _stats.update { it - task.id }
        Log.d(TAG, "引擎任务完成：yunxId=${task.id} engineId=${task.engineTaskId} path=$savedPath size=$size " +
            "avg=$avgSpeed files=${detail?.fileCount ?: -1} folder=${detail?.folder ?: false}")
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "engine_task_complete", taskId = task.id, status = "completed",
            size = size,
            summary = "path=$savedPath 文件数=${detail?.fileCount ?: -1} 目录=${detail?.folder ?: false}"
        )
        if (realName != task.fileName) {
            // 磁力：把界面上占位的显示名换成真正的种子名（失败只记日志，不影响已完成状态）
            runCatching { dao.updateFileName(task.id, realName) }
                .onFailure { Log.w(TAG, "回写种子名失败：id=${task.id} ${it.message}") }
        }
        taskEngineIds.remove(task.id)
        releaseEngineTaskMemory(task.id)
        // 保活收尾 + 结果通知（与内置下载器走同一条前台通知通道）
        if (engineKeepAliveIds.remove(task.id)) onTaskFinished()
        DownloadService.notifyResult(
            context, task.id, realName,
            success = true, promote = showSpeedProvider()
        )
        taskCallbacks.remove(task.id)?.let { cb -> runCatching { cb() } }
    }

    private fun releaseEngineTaskMemory(id: Long) {
        sharedSlots.release(id)
        taskHeaders.remove(id)
        taskSizes.remove(id)
        taskFallbackUrls.remove(id)
    }

    /** 引擎任务暂停：异步转发给引擎，状态由同步循环回写 */
    private fun pauseEngineTask(id: Long, engineId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { GopeedEngine.pauseTask(engineId) }
                dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
                _stats.update { it - id }
                sharedSlots.release(id)
                if (engineKeepAliveIds.remove(id)) onTaskFinished()
            } catch (e: Exception) {
                Log.e(TAG, "引擎暂停失败：id=$id", e)
                dao.updateError(id, "引擎暂停失败，请重试")
            }
        }
    }

    /** 引擎任务继续：转发 continue 并重新拉起同步 */
    private fun resumeEngineTask(id: Long, engineId: String) {
        launchEngineTask(id) {
            try {
                withContext(Dispatchers.IO) { GopeedEngine.continueTask(engineId) }
            } catch (e: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { GopeedEngine.pauseTask(engineId) }
                }
                throw e
            }
            // 本段运行起点重置：平均速度口径与内置下载器一致（最近一段运行）
            taskStartTimes[id] = System.currentTimeMillis()
            // 继续下载：重新拉起前台服务保活（暂停时刚刚收尾过）
            if (engineKeepAliveIds.add(id)) onTaskStarted(id)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
            startEngineSync()
        }
    }

    /**
     * 重新下载：优先复用仍有效的直链；个人网盘直链已失效时自动重新取链后再新建任务。
     * 分享链接目前缺少完整分享会话上下文，仍保持安全失败，不猜测/绕过来源校验。
     * 重新下载：用原直链新建任务（任务卡长按菜单「重新下载」）。
     * 先做 Range 探测校验直链有效性：403/404/网络错误视为直链已过期，返回 false 由 UI 提示。
     * 磁力链接跳过探测——getTotalSize 是 HTTP Range 探测，喂它 magnet: 必然失败，
     * 会让「重新下载」永远提示直链失效；磁力直接重新入队，引擎不可用时由 enqueue 的拦截说明原因。
     */
    suspend fun redownload(id: Long): Boolean {
        var task = dao.get(id) ?: return false
        var headers = loadPersistedHeaders(id)
        if (MagnetLink.isMagnet(task.url)) {
            enqueue(url = task.url, fileName = task.fileName, platform = DownloadPlatform.MAGNET)
            return true
        }

        var probedSize = runCatching { downloader.getTotalSize(task.url, headers) }.getOrNull()
        if (
            probedSize == null &&
            task.sourceType == DownloadSourceType.CLOUD &&
            task.sourceFileId.isNotBlank() &&
            tryRefreshSource(id, "redownload")
        ) {
            task = dao.get(id) ?: return false
            headers = loadPersistedHeaders(id)
            probedSize = runCatching { downloader.getTotalSize(task.url, headers) }.getOrNull()
        }

        if (probedSize == null) return false

        enqueue(
            url = task.url,
            fileName = task.fileName,
            headers = headers,
            size = probedSize.takeIf { it > 0 } ?: task.totalSize,
            platform = task.platform,
            sourceFileId = task.sourceFileId,
            sourceType = task.sourceType,
            sourceContext = task.sourceContext,
            urlExpiresAt = task.urlExpiresAt,
            etag = task.etag,
            lastModified = task.lastModified
        )
        return true
    }

    /** 开始/恢复下载（断点续传） */
    fun start(id: Long, headers: Map<String, String> = emptyMap()) {
        // 引擎任务绝不能落到内置分片下载器（会重复下载同一个 URL）：转发 continue 即可
        taskEngineIds[id]?.let { engineId ->
            resumeEngineTask(id, engineId)
            return
        }
        scope.launch {
            val task = dao.get(id)
            if (task?.engineTaskId?.isNotBlank() == true && taskEngineIds[id] == null) {
                taskEngineIds[id] = task.engineTaskId
                resumeEngineTask(id, task.engineTaskId)
            } else {
                startBuiltin(id, headers)
            }
        }
    }

    private fun startBuiltin(id: Long, headers: Map<String, String>) {
        // 恢复时未传 headers：沿用入队时保存的（Cookie/UA 对直链下载是必需的）
        val effectiveHeaders = headers.ifEmpty { taskHeaders[id] ?: emptyMap() }
        Log.d(TAG, "start: id=$id headers=${effectiveHeaders.keys}")
        synchronized(jobsLock) {
            // 原子注册：检查 + 占位 + launch + complete 在同一锁内完成，
            // pause/remove 要么拿到已注册的 job，要么拿不到（视为未运行）
            val existing = activeJobs[id]
            if (existing != null) {
                // job 仍活跃（正在下载/收尾）：忽略本次 start，避免重复启动
                if (existing.isCompleted && existing.getCompleted().isActive) return
                // job 已结束但 finally 尚未清理（暂停后立即恢复的残留）：
                // 移除旧引用，继续注册新 job，保证"点开始"立即生效
                activeJobs.remove(id)
            }
            val deferred = CompletableDeferred<Job>()
            activeJobs[id] = deferred
            updatePhase(id, "等待队列")
            val job = scope.launch {
                try {
                    // 用户明确开始/恢复：清除“手动暂停”标志和上一次失败文案。
                    // 避免恢复后 DB 仍残留 manualPaused=true / 旧 errorMsg。
                    dao.updateManualPaused(id, false)
                    dao.updateError(id, "")
                    // 任务开始：有任务在下载时保持前台服务（避免切后台限速/进程被杀）
                    onTaskStarted(id)
                    // 任务级互斥：同一任务串行执行，暂停后立刻恢复不会并发写分片
                    taskLocks.getOrPut(id) { Mutex() }.withLock {
                        val restoredHeaders = if (effectiveHeaders.isNotEmpty()) {
                            effectiveHeaders
                        } else {
                            loadPersistedHeaders(id)
                        }
                        if (restoredHeaders.isNotEmpty()) taskHeaders[id] = restoredHeaders
                        runTaskWithRetry(id, restoredHeaders)
                    }
                } catch (e: CancellationException) {
                    // 主动暂停/删除：part 文件保留（或由 remove 清理）；状态已由调用方设置
                    _stats.update { it - id }
                } catch (e: Exception) {
                    _stats.update { it - id }
                    // 协程已被取消（暂停/删除）：不标记失败，避免覆盖 PAUSED 状态
                    if (isTaskActive()) {
                        val reason = e.message ?: e.javaClass.simpleName
                        Log.e(TAG, "task $id failed: $reason", e)
                        dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                        dao.updateError(id, DownloadFailurePolicy.userMessage(e))
                        // 终态通知：失败同样先出流体云胶囊，随后转为可划掉的普通通知
                        DownloadService.notifyResult(
                            context, id, dao.get(id)?.fileName ?: "下载任务",
                            success = false, error = reason, promote = showSpeedProvider()
                        )
                    } else {
                        Log.w(TAG, "task $id cancelled: ${e.message}")
                    }
                } finally {
                    // 任务结束（成功/失败/暂停/删除）：无任务时停止前台服务
                    sharedSlots.release(id)
                    onTaskFinished()
                    // 只移除自己注册的 deferred：
                    // 若暂停后立即恢复（新 job 已注册到同一 id），不能误删新任务的注册，
                    // 否则新任务将无法再被暂停/删除（后台继续下载）
                    synchronized(jobsLock) {
                        if (activeJobs[id] === deferred) activeJobs.remove(id)
                    }
                    // 注意：taskLocks 不在此清理 —— 若新任务已 getOrPut 拿到锁，
                    // 旧任务 finally 的 remove 会误删新任务的锁导致并发写分片
                }
            }
            // launch 是同步返回 Job 的，锁内 complete，pause/remove 的 await 立即返回
            deferred.complete(job)
        }
    }

    /** 任务开始/结束计数：控制前台服务生命周期（有任务在下载即保持前台） */
    private suspend fun onTaskStarted(id: Long) {
        if (activeTaskCount.getAndIncrement() == 0) {
            val name = runCatching { dao.get(id)?.fileName }.getOrNull() ?: "下载任务"
            // 走引用计数版：内核包下载（KernelProvisioner）也在用同一条前台服务，谁都不能直接停
            DownloadService.acquire(context, name)
        }
        // 锁屏保持下载：开启时获取 PARTIAL_WAKE_LOCK（息屏维持 CPU/网络）
        acquireWakeLockIfNeeded()
    }

    private fun onTaskFinished() {
        if (activeTaskCount.decrementAndGet() <= 0) {
            activeTaskCount.set(0)
            DownloadService.release(context)
            releaseWakeLock()
        }
    }

    // ---------- 锁屏保持下载（WakeLock） ----------

    @Volatile
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLockIfNeeded() {
        if (!keepWhenLockedProvider()) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "yunx:download"
            ).apply { setReferenceCounted(false) }
        }
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /** 暂停下载（保留 part 文件与请求头） */
    fun pause(id: Long) {
        Log.d(TAG, "pause: id=$id")
        DiagnosticLog.event(DiagnosticLog.OPERATION, "download_pause", "id=$id")
        engineLaunchJobs.remove(id)?.cancel()
        // 引擎任务：额外通知引擎暂停；本地状态与清理继续走下面的原逻辑（引擎任务没有分片文件，两步互不干扰）
        taskEngineIds[id]?.let { engineId ->
            scope.launch { dao.updateManualPaused(id, true) }
            pauseEngineTask(id, engineId)
            return
        }
        // 立即中断该任务所有分片网络请求（不依赖协程取消传播，阻塞 IO 马上停止）
        downloader.cancelCalls(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        _stats.update { it - id }
        scope.launch {
            dao.updateManualPaused(id, true)
            // 等协程真正退出（确保没有半截写入）后，以磁盘 part/seg 真实大小为准回写进度：
            // 暂停瞬间最后一次 onBytes 可能被取消丢弃，DB 落后于磁盘 → 恢复时进度回跳
            cancelAndAwaitExit(id, deferred)
            val real = chunkDirOf(id).listFiles()
                ?.filter {
                    it.name.startsWith("part_") ||
                        (it.name.startsWith("seg_") && it.name.endsWith(".part"))
                }
                ?.sumOf { it.length() } ?: 0L
            val t = dao.get(id)
            if (t != null && real > t.downloadedSize) {
                dao.updateProgress(id, DownloadTaskEntity.STATUS_PAUSED, real, t.totalSize)
            } else {
                dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
            }
        }
    }

    /**
     * 删除任务：取消下载 + 清 DB + 清 part 文件。
     * @param deleteLocal 同时删除已保存到本地的文件（savePath）
     */
    fun remove(id: Long, deleteLocal: Boolean = false) {
        Log.d(TAG, "remove: id=$id deleteLocal=$deleteLocal")
        engineLaunchJobs.remove(id)?.cancel()
        // 引擎任务：额外通知引擎删除；本地清理（DB 记录 + 已下载文件）继续走下面的原逻辑
        taskEngineIds[id]?.let { engineId ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { GopeedEngine.deleteTask(engineId) }
                    taskEngineIds.remove(id)
                    releaseEngineTaskMemory(id)
                    if (engineKeepAliveIds.remove(id)) onTaskFinished()
                    if (deleteLocal) dao.get(id)?.savePath?.let { DownloadSaver.delete(context, it) }
                    dao.delete(id)
                    _stats.update { it - id }
                    taskCallbacks.remove(id)?.let { runCatching { it() } }
                } catch (e: Exception) {
                    Log.e(TAG, "引擎删除失败：id=$id", e)
                    dao.updateError(id, "引擎删除失败，请重试")
                }
            }
            return
        }
        // 立即中断该任务所有分片网络请求
        downloader.cancelCalls(id)
        _stats.update { it - id }
        taskHeaders.remove(id)
        taskFallbackUrls.remove(id)
        // 删除任务同样触发清理回调（如删除网盘临时转存文件）：
        // 用户放弃下载时云盘里已转存的临时文件也应一并清理
        val cleanup = taskCallbacks.remove(id)
        taskLocks.remove(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        scope.launch {
            // 若任务正在下载：取消并**有界**等待协程退出，
            // 确保没有后台残留下载、part 文件无 fd 占用（否则删了仍占空间）。
            // 超时也继续清理：写满磁盘时 MediaProvider/FUSE 操作可能长时间不返回，
            // 旧实现的无界等待会让「删除」看起来完全没反应（幽灵任务）。
            cancelAndAwaitExit(id, deferred)
            if (deleteLocal) {
                dao.get(id)?.savePath?.let {
                    val deleted = DownloadSaver.delete(context, it)
                    Log.d(TAG, "remove: id=$id 删除本地文件 ${if (deleted) "成功" else "失败/未找到"} ($it)")
                }
            }
            DiagnosticLog.dbOp("download_task", "delete", rowsOf = { -1 }) { dao.delete(id) }
            DiagnosticLog.event(
                DiagnosticLog.OPERATION, "download_remove",
                "id=$id deleteLocal=$deleteLocal"
            )
            chunkDirOf(id).deleteRecursively()
            // 旧版本遗留的私有合并副本（本版本已不再产生，见 finishDownload）；失败不阻断
            File(context.cacheDir, "merged_$id").delete()
            // 删除任务后清理云盘转存（与下载成功完成同语义）；失败不阻断
            cleanup?.let { runCatching { it() } }
        }
    }

    /**
     * 取消任务协程并等待其退出。
     * 阻塞式 IO（大文件写盘、MediaProvider 调用）不响应取消，无界等待会让暂停/删除按钮「点不动」，
     * 故等待设上限：超时后照常执行后续清理，残留协程会因分片目录被删而自行失败。
     */
    private suspend fun cancelAndAwaitExit(id: Long, deferred: CompletableDeferred<Job>?) {
        val job = deferred?.await() ?: return
        job.cancel()
        if (withTimeoutOrNull(JOB_EXIT_WAIT_MS) { job.join() } == null) {
            Log.w(TAG, "等待任务协程退出超时 id=$id（阻塞式 IO 未响应取消），继续清理")
        }
    }

    // ---------- 内部实现 ----------

    private fun encodeHeaders(headers: Map<String, String>): String {
        val json = JSONObject().apply { headers.forEach { (name, value) -> put(name, value) } }.toString()
        return credentialCipher.encrypt(json, "download.requestHeaders")
    }

    private suspend fun loadPersistedHeaders(id: Long): Map<String, String> {
        val stored = dao.get(id)?.requestHeadersJson.orEmpty()
        if (stored.isBlank()) return emptyMap()
        return runCatching {
            val jsonText = credentialCipher.decrypt(stored, "download.requestHeaders")
            val json = JSONObject(jsonText)
            buildMap {
                json.keys().forEach { name -> put(name, json.getString(name)) }
            }.also {
                if (!credentialCipher.isEncrypted(stored)) {
                    dao.updateRequestHeaders(id, encodeHeaders(it))
                }
            }
        }.getOrElse { error ->
            if (!com.yunx.app.data.security.CredentialStore.isKeyLost(error)) {
                // A locked or temporarily unavailable Keystore must not erase resumable headers.
                throw error
            }
            com.yunx.app.data.security.CredentialStore.markKeyLost()
            dao.updateRequestHeaders(id, "{}")
            throw error
        }
    }

    /** 当前协程是否仍活跃（暂停/删除触发取消后为 false） */
    private suspend fun isTaskActive(): Boolean = coroutineContext[Job]?.isActive == true

    private fun isNetworkAvailable(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!validated) return false
        return !wifiOnlyProvider() ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun networkWaitText(): String =
        if (wifiOnlyProvider()) "仅 Wi-Fi 下载：等待 Wi-Fi…" else "网络已断开，等待网络恢复…"

    private fun networkWaitPhase(): String =
        if (wifiOnlyProvider()) "等待 Wi-Fi" else "等待网络"

    /**
     * 网络断开时等待系统恢复网络；用户暂停/删除会取消当前协程并立即退出等待。
     * 等待阶段不消耗失败重试次数。
     */
    private suspend fun awaitNetworkIfNeeded(id: Long) {
        var waiting = false
        while (isTaskActive() && !isNetworkAvailable()) {
            if (!waiting) {
                waiting = true
                dao.updateError(id, networkWaitText())
                updatePhase(id, networkWaitPhase())
                Log.d(TAG, "networkWait: id=$id waiting")
            }
            delay(1000L)
        }
        if (waiting && isTaskActive()) {
            dao.updateError(id, "")
            updatePhase(id, "等待队列")
            Log.d(TAG, "networkWait: id=$id recovered")
        }
    }

    /**
     * App 进程被系统结束后，Room 中可能残留 PENDING / DOWNLOADING。
     * 再次进入应用时把这些任务恢复到安全状态，并自动续传非手动暂停任务。
     */
    suspend fun recoverInterruptedTasks() {
        cleanupOrphanTempFiles()
        val interrupted = dao.getInterruptedTasks()
        val engineTasks = dao.listSyncableEngineTasks()
        val resumableEngineIds = mutableSetOf<Long>()
        // Stop restored native tasks before allowing either engine to acquire new shared slots.
        if (engineTasks.isNotEmpty() && GopeedEngine.isInstalled(context)) {
            withContext(Dispatchers.IO) {
                runCatching { GopeedEngine.start(context, GopeedEngine.resolveDownloadDir(context)) }
            }.onSuccess {
                for (task in engineTasks) {
                    val status = runCatching { withContext(Dispatchers.IO) { GopeedEngine.taskStatus(task.engineTaskId) } }.getOrNull()
                    if (status?.status == "done") {
                        completeEngineTask(task, status.total)
                        continue
                    }
                    val paused = runCatching { withContext(Dispatchers.IO) { GopeedEngine.pauseTask(task.engineTaskId) } }.isSuccess
                    dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PAUSED)
                    if (paused && !task.manualPaused) resumableEngineIds.add(task.id)
                }
            }
        }
        interrupted.filter { it.engineTaskId.isBlank() }.forEach { task ->
            dao.updateProgress(task.id, DownloadTaskEntity.STATUS_PAUSED, task.downloadedSize, task.totalSize)
        }
        startEngineSync()
        interrupted.filter { !it.manualPaused && it.engineTaskId.isBlank() }.forEach { start(it.id) }
        resumableEngineIds.forEach { start(it) }
    }

    /** Update native limits without waking tasks that do not own the shared budget. */
    fun updateConcurrencyLimit() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                GopeedEngine.applyMaxRunning(context) { engineId ->
                    taskEngineIds.entries.any { (id, value) -> value == engineId && sharedSlots.contains(id) }
                }
            }.onFailure { Log.w(TAG, "更新引擎并发上限失败", it) }
        }
    }

    /** Acquire one shared slot atomically; read the current limit each iteration. */
    private suspend fun awaitConcurrencySlot(id: Long) {
        while (true) {
            coroutineContext.ensureActive()
            if (sharedSlots.tryAcquire(id, concurrencyProvider())) break
            updatePhase(id, "等待队列")
            delay(150)
        }
        updatePhase(id, "检查下载源")
    }

    private fun launchEngineTask(id: Long, operation: suspend () -> Unit) {
        synchronized(jobsLock) {
            if (engineLaunchJobs[id]?.isActive == true) return
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    dao.updateManualPaused(id, false)
                    dao.updateStatus(id, DownloadTaskEntity.STATUS_PENDING)
                    if (engineKeepAliveIds.add(id)) onTaskStarted(id)
                    awaitConcurrencySlot(id)
                    operation()
                } catch (e: CancellationException) {
                    sharedSlots.release(id)
                    if (engineKeepAliveIds.remove(id)) onTaskFinished()
                    throw e
                } catch (e: Exception) {
                    sharedSlots.release(id)
                    dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                    dao.updateError(id, DownloadFailurePolicy.userMessage(e))
                    if (engineKeepAliveIds.remove(id)) onTaskFinished()
                } finally {
                    synchronized(jobsLock) {
                        if (engineLaunchJobs[id] == coroutineContext[Job]) engineLaunchJobs.remove(id)
                    }
                }
            }
            engineLaunchJobs[id] = job
            job.start()
        }
    }

    /**
     * 执行任务并支持失败自动重试（断点续传，part 文件保留）。
     * 同时负责「最大同时下载任务数」并发许可的获取/释放。
     */
    private suspend fun runTaskWithRetry(id: Long, headers: Map<String, String>) {
        var attempts = 0
        var currentHeaders = headers
        val maxRetries = retryCountProvider().coerceIn(0, 10)
        while (true) {
            // 无网络时保持任务并等待；网络恢复后从现有 part/seg 继续，不消耗普通失败重试次数。
            awaitNetworkIfNeeded(id)
            if (!isTaskActive()) return
            // 并发许可：排队等待，直到有空闲下载槽位（或任务被暂停/取消）
            awaitConcurrencySlot(id)
            if (!isTaskActive()) return

            try {
                try {
                    val taskBeforeRun = dao.get(id)
                    if (
                        taskBeforeRun != null &&
                        DownloadSourceRefreshPolicy.shouldRefreshBeforeStart(taskBeforeRun.urlExpiresAt) &&
                        tryRefreshSource(id, "expiresAt/preemptive")
                    ) {
                        currentHeaders = loadPersistedHeaders(id)
                    }
                    runTask(id, currentHeaders)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is SourceChangedException) throw e
                    val expiredStatus = downloader.consumeSourceExpiryFailure(id)
                    if (
                        expiredStatus != null &&
                        isTaskActive() &&
                        tryRefreshSource(id, "HTTP $expiredStatus")
                    ) {
                        currentHeaders = loadPersistedHeaders(id)
                        attempts = 0
                        Log.d(TAG, "runTaskWithRetry: id=$id 已刷新临时直链，保留分片继续下载")
                        continue
                    }
                    if (DownloadFailurePolicy.isNetworkFailure(e) && !isNetworkAvailable()) {
                        dao.updateError(id, networkWaitText())
                        updatePhase(id, networkWaitPhase())
                        Log.d(TAG, "runTaskWithRetry: id=$id 网络不可用，等待恢复后续传")
                        continue
                    }
                    attempts++
                    if (isTaskActive() && attempts <= maxRetries) {
                        Log.d(TAG, "runTaskWithRetry: id=$id 失败，自动重试 $attempts/$maxRetries：${e.message}")
                        DiagnosticLog.warn(
                            DiagnosticLog.DOWNLOAD, "task_retry",
                            "task=$id | attempt=$attempts/$maxRetries | err=${e.message}"
                        )
                        // 逐次递增延迟，避免失败风暴
                        delay(1200L * attempts)
                    } else {
                        throw e
                    }
                }
            } finally {
                sharedSlots.release(id)
            }
        }
    }

    private suspend fun tryRefreshSource(id: Long, reason: String): Boolean {
        val task = dao.get(id) ?: return false
        if (task.sourceType != DownloadSourceType.CLOUD) return false
        if (task.sourceFileId.isBlank()) return false
        if (task.refreshCount >= DownloadSourceRefreshPolicy.MAX_REFRESH_COUNT) {
            Log.w(TAG, "refreshSource: id=$id 已达到刷新上限 ${task.refreshCount}")
            return false
        }

        updatePhase(id, "重新获取链接")
        val refreshed = runCatching { sourceRefresher(task) }
            .onFailure { Log.w(TAG, "refreshSource: id=$id 重新取链失败：${it.message}") }
            .getOrNull()
            ?: return false

        if (refreshed.url.isBlank()) return false

        val oldSize = task.totalSize.takeIf { it > 0 }
        val newSize = refreshed.fileSize.takeIf { it > 0 }
        if (oldSize != null && newSize != null && oldSize != newSize) {
            throw SourceChangedException("文件大小发生变化，拒绝续传")
        }

        if (task.etag.isNotBlank() && refreshed.etag.isNotBlank() && task.etag != refreshed.etag ||
            task.lastModified.isNotBlank() && refreshed.lastModified.isNotBlank() && task.lastModified != refreshed.lastModified) {
            throw SourceChangedException("文件内容已发生变化，拒绝续传")
        }
        val encryptedHeaders = encodeHeaders(refreshed.headers)
        dao.updateRefreshedSource(
            id = id,
            url = refreshed.url,
            encryptedHeaders = encryptedHeaders,
            sourceContext = refreshed.sourceContext.ifBlank { task.sourceContext },
            urlExpiresAt = refreshed.urlExpiresAt,
            etag = refreshed.etag,
            lastModified = refreshed.lastModified
        )
        taskHeaders[id] = refreshed.headers
        if (newSize != null) taskSizes[id] = newSize
        dao.updateError(id, "")
        Log.d(TAG, "refreshSource: id=$id reason=$reason count=${task.refreshCount + 1}")
        updatePhase(id, "检查下载源")
        return true
    }

    private suspend fun runTask(id: Long, headers: Map<String, String>) {
        // 协程已被取消（暂停/删除）：直接退出，不写状态
        if (!isTaskActive()) return
        var task = dao.get(id) ?: return
        downloader.consumeSourceExpiryFailure(id) // discard a stale marker from a previous cancelled run
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        updatePhase(id, "下载中")
        taskStartTimes[id] = System.currentTimeMillis()
        Log.d(TAG, "runTask: id=$id fileName=${task.fileName}")

        // HLS（m3u8 转码流，如 UC play）：不走 Range 分片，直接拉分片合并
        if (task.url.contains(".m3u8", true) || task.url.contains(".m3u", true)) {
            Log.d(TAG, "runTask: id=$id HLS 转码流下载 origin=${LogRedactor.url(task.url)}")
            hlsDownload(id, task, headers)
            return
        }

        // 总大小以服务器探测为准（Range0-0 的 Content-Range 是真实总大小），
        // 避免各平台传入的 size 与实际不符导致分片区间错误 → 文件截断/膨胀损坏
        // 镜像主 URL 探测失败且带回退直连时（GitHub 下载），整任务切到原始直连重下，避免镜像挂掉整任务失败
        val fallbackUrl = taskFallbackUrls[id]
        var metadata = downloader.probeResource(task.url, headers)
        var probedSize = metadata?.total
        if (probedSize == null && !fallbackUrl.isNullOrBlank()) {
            Log.w(TAG, "runTask: id=$id 镜像主 URL 不可达，回退原始直连下载")
            task = task.copy(url = fallbackUrl)
            metadata = downloader.probeResource(task.url, headers)
            probedSize = metadata?.total
        }
        val total = probedSize
            ?: taskSizes[id]?.takeIf { it > 0 }
        if (total == null) {
            // 服务器不返回文件大小（Range/Content-Length 均缺失）：降级为流式下载（开放区间 Range）
            Log.w(TAG, "runTask: id=$id 无法获取总大小，降级流式下载 origin=${LogRedactor.url(task.url)}")
            DiagnosticLog.warn(DiagnosticLog.DOWNLOAD, "task_stream_fallback", "task=$id | 服务器没给大小，降级流式下载")
            streamDownload(id, task, headers)
            return
        }
        Log.d(TAG, "getTotalSize: id=$id total=$total origin=${LogRedactor.url(task.url)}")
        ensureTempSpace(total, task.downloadedSize)
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "task_size_probed", taskId = id, status = "downloading", size = total,
            summary = "已有进度=${task.downloadedSize} url=${LogRedactor.url(task.url)}"
        )
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, total)
        // 取到大小后再次检查取消（暂停可能发生在 getTotalSize 期间）
        if (!isTaskActive()) return

        val threadCount = threadProvider(task.platform).coerceAtLeast(1)
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        val planFile = File(chunkDir, "plan.txt")
        val savedPlan = if (planFile.exists()) TransferPlan.parse(planFile.readText(), total) else null
        if (planFile.exists() && savedPlan == null) {
            Log.w(TAG, "runTask: id=$id 旧计划无效或文件大小变化，清空不可信分片")
            check(chunkDir.deleteRecursively()) { "无法清理旧下载分片" }
            check(chunkDir.mkdirs()) { "无法创建下载缓存" }
        }
        // Reuse valid legacy geometry even when the user changes connection count.
        val plan = savedPlan ?: chunkCountFor(total, threadCount).let {
            TransferPlan(it, total, (it * 0.7).toInt().coerceIn(1, it))
        }
        planFile.writeText(plan.encode())
        val mainPoolCount = plan.main
        val chunkSize = plan.chunkSize
        val elasticStart = plan.elasticStart
        val resourceFile = File(chunkDir, "resource.json")
        val resource = metadata
        val old = if (resourceFile.exists()) JSONObject(resourceFile.readText()) else JSONObject()
        val oldEtag = old.optString("etag")
        val oldModified = old.optString("modified")
        if (resourceFile.exists()) {
            if (oldEtag.isNotBlank() && resource?.etag?.isNotBlank() == true && oldEtag != resource.etag ||
                oldModified.isNotBlank() && resource?.lastModified?.isNotBlank() == true && oldModified != resource.lastModified) {
                throw SourceChangedException("文件内容已发生变化，拒绝拼接旧分片")
            }
        }
        val expectedEtag = resource?.etag?.takeIf { it.isNotBlank() } ?: oldEtag.takeIf { it.isNotBlank() } ?: task.etag.takeIf { it.isNotBlank() }
        val modified = resource?.lastModified?.takeIf { it.isNotBlank() } ?: oldModified.takeIf { it.isNotBlank() } ?: task.lastModified
        resourceFile.writeText(JSONObject().put("etag", expectedEtag.orEmpty()).put("modified", modified).toString())
        val mainParts = (0 until mainPoolCount).map { i ->
            val start = i * chunkSize
            TransferPart(start, minOf(start + chunkSize - 1, total - 1), File(chunkDir, "part_$i")).also { it.validate() }
        }
        val tail = ResumableTail(chunkDir, elasticStart, total)
        val downloaded = AtomicLong(mainParts.sumOf { it.file.length() } + tail.existingBytes())
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, downloaded.get(), total)
        val requestHeaders = headers.filterKeys { !it.equals("Range", true) && !it.equals("If-Range", true) }.toMutableMap()
        if (downloaded.get() > 0) TransferHttpPolicy.validator(expectedEtag.orEmpty(), modified)?.let { requestHeaders["If-Range"] = it }
        val isXunlei = task.platform == DownloadPlatform.XUNLEI ||
            headers.entries.any { it.key.equals("User-Agent", true) && it.value.contains("xunlei", true) } ||
            task.url.contains("xunlei", true)
        val maximum = minOf(threadCount, MAX_INFLIGHT_CHUNKS, if (isXunlei) RANGE_WORKERS_CAP else MAX_INFLIGHT_CHUNKS)
        val connections = AdaptiveConnections(maximum)
        _stats.update { it + (id to DownloadStats(chunkCount = connections.limit, phase = "下载中")) }
        DiagnosticLog.log(DiagnosticLog.DOWNLOAD, "chunk_plan", taskId = id, size = total,
            summary = "持久分片=${plan.chunks} 并发上限=$maximum 起始并发=${connections.limit} 已有=${downloaded.get()}")
        val nextIdx = AtomicInteger(0)
        val failed = ConcurrentHashMap<Long, TransferPart>()
        val inflightChunks = ConcurrentHashMap<String, InflightChunk>()
        val lastPersistAt = AtomicLong(0)
        val speedRecorder = SpeedRecorder()
        val currentSpeed = AtomicLong(0)
        val newBytes = AtomicLong(0)
        val rangeIgnored = AtomicBoolean(false)
        val confirmedNoRange = AtomicBoolean(false)

        suspend fun transfer(part: TransferPart): ChunkResult = connections.withPermit {
            inflightLimiter.withPermit {
                val key = "seg@${part.start}"
                val diag = InflightChunk(part.start, part.size).also { it.bytes.set(part.file.length()); it.lastBytes = it.bytes.get() }
                inflightChunks[key] = diag
                try {
                    downloader.downloadChunk(id, task.url, part.start, part.end, part.file, requestHeaders,
                        preempt = diag.preempt, expectedTotal = total, expectedEtag = expectedEtag,
                        onCongestion = { wait -> connections.congested(wait) }) { bytes ->
                        speedLimiter.awaitAllow(bytes)
                        diag.bytes.addAndGet(bytes)
                        newBytes.addAndGet(bytes)
                        val new = downloaded.addAndGet(bytes)
                        if (!isTaskActive()) return@downloadChunk
                        speedRecorder.onBytes(new)?.let { speed ->
                            currentSpeed.set(speed)
                            val remain = if (speed > 0) (total - new).coerceAtLeast(0) * 1000 / speed else -1L
                            _stats.update { it + (id to DownloadStats(speed, remain, connections.limit)) }
                        }
                        notifyProgress(id, task.fileName, new, total)
                        persistProgressIfDue(id, new, total, false, lastPersistAt)
                    }.also {
                        if (downloader.hasSourceExpiryFailure(id)) {
                            throw java.io.IOException("临时下载链接失效，准备刷新")
                        }
                    }
                } finally { inflightChunks.remove(key) }
            }
        }

        coroutineScope {
            // A single watchdog covers primary downloads AND repair passes; cancellation is immediate on exit.
            var sampledBytes = 0L
            val watchdog = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(PREEMPT_TICK_MS)
                    sampleInflightChunks(inflightChunks)
                    val allBytes = newBytes.get()
                    val windowSpeed = (allBytes - sampledBytes) * 1000 / PREEMPT_TICK_MS
                    sampledBytes = allBytes
                    val runtime = Runtime.getRuntime()
                    connections.sample(windowSpeed, nextIdx.get() < mainParts.size || tail.hasPending(),
                        (runtime.totalMemory() - runtime.freeMemory()) > runtime.maxMemory() * 3 / 4)
                    preemptSlowChunks(id, inflightChunks)
                }
            }
            try {
                List(maximum) { worker ->
                    async(chunkIoDispatcher) {
                        // Only the initial connection is staggered, never subsequent chunks.
                        if (worker > 0) delay(minOf(worker, STAGGER_CAP) * STAGGER_MS)
                        while (!rangeIgnored.get()) {
                            val idx = nextIdx.getAndIncrement()
                            val part = if (idx < mainParts.size) mainParts[idx]
                                else tail.take(connections.blockBytes(currentSpeed.get())) ?: break
                            if (part.file.length() == part.size) continue
                            val result = transfer(part)
                            if (result != ChunkResult.OK) failed[part.start] = part
                            if (result == ChunkResult.RANGE_IGNORED) {
                                // Stop allocating NEW work. Drain active connections, then verify serially.
                                rangeIgnored.set(true)
                                connections.congested()
                            }
                        }
                    }
                }.awaitAll()

                if (rangeIgnored.get()) {
                    connections.serialVerification()
                    delay(1000)
                    val probe = failed.values.sortedBy { it.start }.firstOrNull()
                    if (probe != null) {
                        var result = transfer(probe)
                        if (result == ChunkResult.RANGE_IGNORED) { delay(1000); result = transfer(probe) }
                        if (result == ChunkResult.RANGE_IGNORED) confirmedNoRange.set(true)
                        else if (result == ChunkResult.OK) failed.remove(probe.start)
                    }
                }
                if (!confirmedNoRange.get()) {
                    // Re-enumerate holes: workers may have stopped before claiming all planned work.
                    mainParts.filter { it.file.length() < it.size }.forEach { failed[it.start] = it }
                    while (true) {
                        val part = tail.take(connections.blockBytes(currentSpeed.get())) ?: break
                        if (part.file.length() < part.size) failed[part.start] = part
                    }
                    val pending = failed.values.sortedBy { it.start }
                    val repairIndex = AtomicInteger(0)
                    val repaired = ConcurrentHashMap<Long, Boolean>()
                    List(minOf(maximum, pending.size)) {
                        async(chunkIoDispatcher) {
                            while (true) {
                                val part = pending.getOrNull(repairIndex.getAndIncrement()) ?: break
                                repaired[part.start] = transfer(part) == ChunkResult.OK
                            }
                        }
                    }.awaitAll()
                    if (repaired.values.any { !it }) {
                        // A timeout/expired URL is NOT proof that Range is unsupported. Preserve all parts.
                        throw java.io.IOException("分片补传失败，保留进度等待重试")
                    }
                }
            } finally {
                watchdog.cancel()
            }
        }
        if (confirmedNoRange.get()) {
            // Full GET uses its own file and is never appended to an old Range fragment.
            singleStreamFallback(id, task, headers.filterKeys { !it.equals("Range", true) && !it.equals("If-Range", true) },
                total, chunkDir, java.util.concurrent.atomic.AtomicReference(null))
            return
        }
        persistProgressIfDue(id, downloaded.get(), total, true, lastPersistAt)
        val files = finalChunkFiles(chunkDir, mainPoolCount)
        check(files.sumOf { it.length() } == total) { "文件大小校验失败，拒绝合并" }
        finishDownload(id, chunkDir, files, task.fileName, total)
    }

    /** 最终合并文件列表：主池 part_0..part_{n-1}（连续前半段）+ 弹性区 seg_{start}_{end} 按 start 排序（后半段） */
    private fun finalChunkFiles(chunkDir: File, mainPoolCount: Int): List<File> {
        val mainFiles = (0 until mainPoolCount).map { File(chunkDir, "part_$it") }
        val elasticFiles = chunkDir.listFiles { f ->
            f.name.startsWith("seg_") && f.name.endsWith(".part")
        }?.sortedBy { it.name.removePrefix("seg_").substringBefore('_').toLong() }
            ?: emptyList()
        return mainFiles + elasticFiles
    }

    /**
     * 回退：单条整文件流下载（服务器忽略 Range 时）。
     * 写入**独立**的 full_single.bin（从 0 开始），不复用 part_0，避免与已下分片错位/重复。
     */
    private suspend fun singleStreamFallback(
        id: Long,
        task: DownloadTaskEntity,
        headers: Map<String, String>,
        total: Long,
        chunkDir: File,
        failReason: java.util.concurrent.atomic.AtomicReference<String?>
    ) {
        val fullFile = File(chunkDir, "full_single.bin").apply { delete() } // 全新整文件，从 0 开始
        val fullDownloaded = AtomicLong(0)
        val fullLastAt = AtomicLong(0L)
        val ok = downloader.downloadFull(id, task.url, fullFile, headers, total) { bytes ->
            speedLimiter.awaitAllow(bytes)
            // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
            val new = minOf(fullDownloaded.addAndGet(bytes), total)
            if (!isTaskActive()) return@downloadFull
            persistProgressIfDue(id, new, total, force = false, lastAt = fullLastAt)
            notifyProgress(id, task.fileName, new, total)
        }
        if (!ok) throw IllegalStateException(failReason.get() ?: "分片与单流下载均失败")
        finishDownload(id, chunkDir, listOf(fullFile), task.fileName, total)
    }

    /** 流式降级下载：总大小未知时单分片开放区间下载（Range: bytes=from-），读到 EOF */
    private suspend fun streamDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, 0)
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1, phase = "下载中")) }
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        val partFile = File(chunkDir, "part_0")
        val downloaded = AtomicLong(partFile.length())
        val streamLastAt = AtomicLong(0L)
        val ok = downloader.downloadChunk(
            taskId = id,
            url = task.url,
            start = 0,
            end = Long.MAX_VALUE,
            partFile = partFile,
            headers = headers
        ) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            if (!isTaskActive()) return@downloadChunk
            // 大小未知：只更新已下载量（total=0 表示未知）
            persistProgressIfDue(id, new, 0, force = false, lastAt = streamLastAt)
            // 前台通知进度（2 秒节流，total 未知时仅更新标题）
            notifyProgress(id, task.fileName, new, 0)
        }
        if (ok != ChunkResult.OK) {
            // Range 被 CDN 拒绝（416/403）或忽略（200 整文件）：回退为无 Range 完整 GET
            Log.w(TAG, "streamDownload: id=$id Range 失败，回退完整 GET 下载")
            downloaded.set(0)
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, 0, 0)
            val ok2 = downloader.downloadFull(
                taskId = id,
                url = task.url,
                partFile = partFile,
                headers = headers
            ) { bytes ->
                speedLimiter.awaitAllow(bytes)
                val new = downloaded.addAndGet(bytes)
                if (!isTaskActive()) return@downloadFull
                persistProgressIfDue(id, new, 0, force = false, lastAt = streamLastAt)
            }
            if (!ok2) throw IllegalStateException("下载失败（Range 与完整下载均失败）")
        }
        if (!isTaskActive()) return
        finishDownload(id, chunkDir, listOf(partFile), task.fileName, 0)
    }

    /** HLS（m3u8 转码流，如 UC play）下载：拉取分片合并 → 保存 → 完成回调 */
    private suspend fun hlsDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1, phase = "下载中")) }
        val hlsFile = File(context.cacheDir, "hls_$id")
        hlsFile.delete()
        val downloaded = AtomicLong(0)
        val hlsLastAt = AtomicLong(0L)
        val ok = HlsDownloader.download(task.url, headers, hlsFile) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            persistProgressIfDue(id, new, 0, force = false, lastAt = hlsLastAt)
            notifyProgress(id, task.fileName, new, 0)
        }
        if (!isTaskActive()) return
        if (!ok) {
            hlsFile.delete()
            throw IllegalStateException("HLS 转码流下载失败")
        }
        // Android 9- 保存前检查存储权限（动态申请，授权后继续；无权限则报错提示）
        if (!storagePermissionProvider()) {
            hlsFile.delete()
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        updatePhase(id, "保存中")
        val savedPath = withContext(Dispatchers.IO) {
            DownloadSaver.save(context, task.fileName, hlsFile, saveDirProvider())
        }
            ?: throw IllegalStateException("保存到下载目录失败")
        val hlsTotal = dao.get(id)?.totalSize ?: 0L
        completeWithAvg(id, savedPath, hlsTotal)
        DownloadService.notifyCompleted(context, task.fileName)
        Log.d(TAG, "hlsDownload: id=$id 下载完成 savedPath=$savedPath size=${hlsFile.length()}")
        // 终态通知：完成后流体云先显示「下载完成」胶囊，随后转为可划掉的普通通知
        DownloadService.notifyResult(
            context, id, task.fileName, success = true, promote = showSpeedProvider()
        )
        taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        _stats.update { it - id }
        hlsFile.delete()
    }

    /**
     * 分片流式合并 → **直接写入最终保存位置** → 完成后清空分片目录。
     *
     * ★ 旧实现先合并到私有缓存 `merged_$id` 再复制到公共目录，峰值占用 3 份
     *   （分片 + 合并副本 + 公共副本），6GB 级文件即便预留 2.4 倍空间也会 ENOSPC；
     *   现改为边合并边删分片（见 [ChunkDownloader.mergeChunksToStream]），峰值 ≈ 1 份。
     * ★ 完整性校验：分片非空 + 写入字节 == total，任一不符即 abort 半成品并抛错，绝不保存损坏文件。
     * ★ 失败/取消一律 abort：MediaStore 的 IS_PENDING 半成品在「下载」里看不见，却真实占空间。
     */
    private suspend fun finishDownload(
        id: Long,
        chunkDir: File,
        chunkFiles: List<File>,
        fileName: String,
        total: Long
    ) {
        if (!isTaskActive()) return
        // 1) 分片完整性
        for (part in chunkFiles) {
            if (!part.exists() || part.length() <= 0) {
                Log.e(TAG, "finishDownload: id=$id 分片缺失/为空 $part")
                throw IllegalStateException("分片文件缺失或为空，拒绝合并（防止文件损坏）")
            }
        }
        // 2) Android 9- 保存前检查存储权限（动态申请，授权后继续；无权限则报错提示）
        if (!storagePermissionProvider()) {
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        // 3) 流式写入最终位置（自定义目录经 SAF；默认目录走 MediaStore/传统路径）
        // ★ 同步阻塞写入必须切 IO 线程：任务跑在 Dispatchers.Default（CPU 池），
        //   大文件写盘若占满 Default 线程会让整个下载器协程饿死（"100% 卡死保存不了"）
        // 合并阶段单独上报进度（界面/通知显示「合并中 n%」）：大文件合并要几十秒，
        // 一直停在 100% 不动会让用户以为卡死。进度只走内存态 stats、不写 DB ——
        // 进程被杀时合并本就中断，库里不需要再多一个会卡住的状态。
        val mergeTotal = if (total > 0) total else chunkFiles.sumOf { it.length() }
        var mergeLastPercent = -1
        var mergeLastAtMs = 0L
        fun reportMergeProgress(done: Long) {
            if (mergeTotal <= 0) return
            val percent = (done * 100 / mergeTotal).toInt().coerceIn(0, 100)
            val now = System.currentTimeMillis()
            if (percent == mergeLastPercent && now - mergeLastAtMs < mergeReportIntervalMs) return
            mergeLastPercent = percent
            mergeLastAtMs = now
            _stats.update { it + (id to DownloadStats(mergePercent = percent, phase = "合并中")) }
            // 诊断日志：合并回调很密，按 10% 采样（高频日志必须降频，见 DiagnosticLog 的限流说明）
            if (percent % 10 == 0) {
                DiagnosticLog.log(
                    DiagnosticLog.DOWNLOAD, "merge_progress", taskId = id, status = "merging",
                    size = done, summary = "percent=$percent%"
                )
            }
            // 通知沿用下载中那条 2 秒节流（合并回调很密，别把系统通知刷爆）
            if (now - lastNotifyTs.get() >= notifyThrottleMs) {
                lastNotifyTs.set(now)
                DownloadService.update(
                    context, fileName, percent, DownloadService.MERGE_TEXT, showSpeedProvider()
                )
            }
        }
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "merge_start", taskId = id, status = "merging", size = mergeTotal,
            summary = "分片数=${chunkFiles.size} 目标=$fileName"
        )
        reportMergeProgress(0L)
        val savedPath = withContext(Dispatchers.IO) {
            val dest = DownloadSaver.openDestination(context, fileName, saveDirProvider())
                ?: throw IllegalStateException("无法创建下载目标（下载目录不可用）")
            try {
                val out = dest.open() ?: throw IllegalStateException("无法打开下载目标输出流")
                val written = out.use {
                    downloader.mergeChunksToStream(chunkFiles, it, ::reportMergeProgress)
                }
                if (total > 0 && written != total) {
                    throw IllegalStateException("文件大小校验失败：期望 $total 字节，实际 $written 字节（已拒绝保存损坏文件）")
                }
                dest.commit()
                dest.path
            } catch (e: Exception) {
                // 失败（含 ENOSPC）/取消：删掉半成品，否则残留数据会一直占空间，重试时空间只减不增
                dest.abort()
                throw e
            }
        }
        completeWithAvg(id, savedPath, total)
        Log.d(TAG, "finishDownload: id=$id 下载完成 savedPath=$savedPath size=$total")
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "task_complete", taskId = id, status = "completed", size = total,
            summary = "合并完成 path=$savedPath 分片数=${chunkFiles.size}"
        )
        // 终态通知：完成后流体云先显示「下载完成」胶囊，随后转为可划掉的普通通知
        DownloadService.notifyResult(
            context, id, fileName, success = true, promote = showSpeedProvider()
        )
        taskCallbacks.remove(id)?.let { cb ->
            runCatching { cb() }
        }
        _stats.update { it - id }
        chunkDir.deleteRecursively()
    }

    /**
     * 速度采样器：取近 [WINDOW_MS] 秒滑动窗口的平均速度，平滑多线程下载的速度波动。
     * 多线程并发下瞬时速率波动大，短窗口估算剩余时长会剧烈跳动；
     * 改用 5 秒窗口均值后，剩余时长更稳定可靠。
     */
    private class SpeedRecorder {
        private data class Sample(val timeMs: Long, val bytes: Long)

        private val samples = ArrayDeque<Sample>()
        private var lastEmit = 0L

        @Synchronized
        fun onBytes(total: Long): Long? {
            val now = System.currentTimeMillis()
            samples.addLast(Sample(now, total))
            // 剔除窗口外的旧样本，但始终保留至少 2 个（下载起步阶段窗口尚短）
            while (samples.size > 2 && now - samples.first().timeMs > WINDOW_MS) {
                samples.removeFirst()
            }
            // 250ms 发射一次，避免高频刷新 UI/通知
            if (now - lastEmit < 250) return null
            val first = samples.first()
            val elapsed = now - first.timeMs
            val speed = if (elapsed > 0) {
                ((total - first.bytes) * 1000 / elapsed).coerceAtLeast(0)
            } else 0L
            lastEmit = now
            return speed
        }

        private companion object {
            const val WINDOW_MS = 5000L
        }
    }

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒；0 = 不限速 */
    private inner class SpeedLimiter {
        @Volatile
        private var tokens = 0L
        @Volatile
        private var lastRefillNanos = System.nanoTime()

        @Synchronized
        private fun refill(limit: Long) {
            val now = System.nanoTime()
            val elapsedSec = ((now - lastRefillNanos).coerceAtLeast(0) / 1_000_000_000.0)
            lastRefillNanos = now
            tokens = minOf(limit, tokens + (elapsedSec * limit).toLong())
        }

        /** 消耗 bytes 字节额度；不足则挂起等待（限速生效） */
        suspend fun awaitAllow(bytes: Long) {
            val limit = speedLimitProvider().coerceAtLeast(0L)
            if (limit <= 0L) return
            while (true) {
                val waitMs = synchronized(this) {
                    refill(limit)
                    if (bytes <= tokens) {
                        tokens -= bytes
                        return
                    }
                    ((bytes - tokens) * 1000 / limit).coerceIn(1L, 200L)
                }
                // 锁外挂起等待，避免持锁阻塞其他任务
                delay(waitMs)
            }
        }
    }

    /** 下载临时文件缓存根目录：外部缓存（/storage/emulated/0/Android/data/com.yunx.app/cache），
     *  与最终保存目录解耦，系统可自动清理；外部存储不可用时回退内部缓存目录。 */
    private fun cacheBase(): File = context.externalCacheDir ?: context.cacheDir

    /**
     * 已知文件大小时提前检查临时空间。
     * 当前下载流程需要保存 part/seg，并在完成阶段生成 merged 文件，所以分别检查两处缓存空间。
     */
    private fun ensureTempSpace(total: Long, downloaded: Long) {
        if (total <= 0L) return
        val reserve = 64L * 1024 * 1024
        val remaining = (total - downloaded.coerceAtLeast(0L)).coerceAtLeast(0L)
        val partBase = cacheBase()
        val mergedBase = context.cacheDir

        val sameBase = runCatching {
            partBase.canonicalPath == mergedBase.canonicalPath
        }.getOrDefault(partBase.absolutePath == mergedBase.absolutePath)

        if (sameBase) {
            val need = remaining + total + reserve
            val free = partBase.usableSpace
            if (free > 0L && free < need) {
                throw IllegalStateException(
                    "临时空间不足：至少还需要 ${need - free} 字节可用空间"
                )
            }
        } else {
            val partNeed = remaining + reserve
            val partFree = partBase.usableSpace
            if (partFree > 0L && partFree < partNeed) {
                throw IllegalStateException(
                    "下载临时空间不足：至少还需要 ${partNeed - partFree} 字节可用空间"
                )
            }

            val mergeNeed = total + reserve
            val mergeFree = mergedBase.usableSpace
            if (mergeFree > 0L && mergeFree < mergeNeed) {
                throw IllegalStateException(
                    "合并文件临时空间不足：至少还需要 ${mergeNeed - mergeFree} 字节可用空间"
                )
            }
        }
    }

    /**
     * 只清理“不再对应任何数据库任务”的孤儿缓存，绝不删除仍存在任务的 part/seg。
     */
    private suspend fun cleanupOrphanTempFiles() = withContext(Dispatchers.IO) {
        val liveIds = runCatching { dao.getAllTaskIds().toSet() }.getOrElse { return@withContext }
        val root = File(cacheBase(), "download_tmp")
        root.listFiles()?.forEach { child ->
            val id = child.name.toLongOrNull()
            if (id != null && id !in liveIds) {
                runCatching { child.deleteRecursively() }
            }
        }

        context.cacheDir.listFiles()?.forEach { file ->
            val id = when {
                file.name.startsWith("merged_") ->
                    file.name.removePrefix("merged_").toLongOrNull()
                file.name.startsWith("hls_") ->
                    file.name.removePrefix("hls_").toLongOrNull()
                else -> null
            }
            if (id != null && id !in liveIds) {
                runCatching { file.deleteRecursively() }
            }
        }
    }

    /** 分片临时文件目录：cacheBase()/download_tmp/$id */
    private fun chunkDirOf(id: Long): File = File(cacheBase(), "download_tmp/$id")

    /** 分片数规划（任务池模型）：分片数 = 线程数 × 8，远多于并发线程数。
     *  worker 循环领取盈余块，任一分片慢时其他线程继续领新片，根治"尾部并发塌缩"；
     *  保留 1MB 单片下限（避免过多小片）与 512 封顶。 */
    private fun chunkCountFor(total: Long, threads: Int): Int {
        if (total <= 0) return 1
        val minChunkBytes = 1 * 1024 * 1024L
        val bySize = when {
            total < 5 * 1024 * 1024 -> 1          // < 5MB 不分片
            total < 50 * 1024 * 1024 -> 8         // < 50MB
            total < 500 * 1024 * 1024 -> 32       // < 500MB
            else -> 64                            // ≥ 500MB 基础值
        }
        // 任务池：每线程平均领 8 片，天然抗慢片拖尾（比 1:1 映射多 8 倍盈余）
        val want = maxOf(bySize, threads * 8)
        return minOf(want.toLong(), (total / minChunkBytes).coerceAtLeast(1), 512L).toInt()
    }

    companion object {
        /** Java read buffers get at most 1/8 heap; native threads/TLS impose a separate 128 ceiling. */
        private const val BUFFER_BUDGET_DIVISOR = 8
        private const val INFLIGHT_MIN = 8

        internal fun inflightChunksFor(maxHeapBytes: Long, bufferSize: Int = BUFFER_SIZE): Int =
            (maxHeapBytes / BUFFER_BUDGET_DIVISOR / bufferSize.coerceAtLeast(1))
                .toInt()
                .coerceIn(INFLIGHT_MIN, 128)

        /**
         * 全进程在飞分片上限（进程启动时按堆预算推导，保守封顶 128）。
         * 由 `inflightLimiter` 用作信号量容量：无论用户怎么调线程数、同时开几个任务，
         * 同时在飞的下载请求数都不会超过它；不代表所有机型都不会出现资源不足。
         */
        val MAX_INFLIGHT_CHUNKS: Int = inflightChunksFor(Runtime.getRuntime().maxMemory())

        /**
         * 分片阻塞 IO 的专用线程池（进程级），worker 与 [ChunkDownloader] 内部的 `withContext` 都跑在它上面。
         *
         * 为什么不能直接用 `Dispatchers.IO`：它的并行度被钉在 `max(64, 核数)`，超出的 worker 只能在队列里
         * 干等 —— 于是「设置里 256/512 线程」永远只跑得出 64 路。线程**按需创建**（最多 [MAX_INFLIGHT_CHUNKS]
         * 条）、空闲 30 秒回收，只有真用到大并发时才会存在那么多线程。
         * 必须 core = max 而不是 core = 0 + 无界队列：后者在 ThreadPoolExecutor 里只会养出 1 个 worker。
         */
        internal val chunkIoDispatcher: CoroutineDispatcher =
            ThreadPoolExecutor(
                MAX_INFLIGHT_CHUNKS,
                MAX_INFLIGHT_CHUNKS,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>()
            ) { r -> Thread(r, "yunx-chunk-io").apply { isDaemon = true } }
                .apply { allowCoreThreadTimeOut(true) }
                .asCoroutineDispatcher()
    }
}
