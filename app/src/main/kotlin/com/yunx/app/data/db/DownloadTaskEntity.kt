package com.yunx.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 下载任务（Room 持久化，断点续传依赖 part 文件 + 已下载大小）。
 */
@Entity(tableName = "download_task")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val fileName: String,
    val totalSize: Long = 0L,
    val downloadedSize: Long = 0L,
    val status: Int = STATUS_PENDING,
    /** 失败原因（服务端/网络/分片等具体错误信息），成功或进行中为空 */
    val errorMsg: String = "",
    /** 完成后的保存位置：MediaStore uri 或文件绝对路径 */
    val savePath: String = "",
    /** 恢复任务所需的请求头 JSON（Cookie/Referer/UA 等） */
    @ColumnInfo(defaultValue = "'{}'")
    val requestHeadersJson: String = "{}",
    /** 首次探测大小后固定的分片数，恢复时不随设置变化 */
    @ColumnInfo(defaultValue = "0")
    val chunkCount: Int = 0,
    /** 与 chunkCount 对应的服务器总大小 */
    @ColumnInfo(defaultValue = "0")
    val plannedTotalSize: Long = 0L,
    /** 下载完成/删除任务后应清理的云端临时目录 ID（当前为夸克） */
    @ColumnInfo(defaultValue = "''")
    val cleanupId: String = "",
    /** 下载来源平台标识（用于按平台应用下载线程数设置）；通用/手动添加为空串 */
    @ColumnInfo(defaultValue = "''")
    val platform: String = "",
    /** 原网盘文件 ID：临时下载 URL 失效后用于重新获取直链。 */
    @ColumnInfo(defaultValue = "''")
    val sourceFileId: String = "",
    /** 来源类型：share / cloud / generic。 */
    @ColumnInfo(defaultValue = "''")
    val sourceType: String = "",
    /** 来源补充信息：仅保存重新取链所需的非敏感文件元数据。 */
    @ColumnInfo(defaultValue = "''")
    val sourceContext: String = "",
    /** 临时下载 URL 预计过期时间（Unix 毫秒）；0 表示未知。 */
    @ColumnInfo(defaultValue = "0")
    val urlExpiresAt: Long = 0L,
    /** HTTP ETag。 */
    @ColumnInfo(defaultValue = "''")
    val etag: String = "",
    /** HTTP Last-Modified。 */
    @ColumnInfo(defaultValue = "''")
    val lastModified: String = "",
    /** 是否由用户主动暂停。 */
    @ColumnInfo(defaultValue = "0")
    val manualPaused: Boolean = false,
    /** 临时直链刷新次数。 */
    @ColumnInfo(defaultValue = "0")
    val refreshCount: Int = 0,
    /**
     * 走 Gopeed 引擎下载时的引擎任务 ID；空串 = 这条任务由内置分片下载器执行。
     * 有值时进度由引擎同步协程回写（见 DownloadManager.startEngineSync），暂停/继续/删除都要转发给引擎。
     */
    @ColumnInfo(defaultValue = "''")
    val engineTaskId: String = "",
    /** 下载完成时的平均速度（字节/秒）；完成态展示用，进行中为 0 */
    @ColumnInfo(defaultValue = "0")
    val avgSpeed: Long = 0,
    /** 完成时间；旧版本记录迁移时为 0，界面以创建时间兜底。 */
    @ColumnInfo(defaultValue = "0")
    val completedTime: Long = 0L,
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_COMPLETED = 3
        const val STATUS_FAILED = 4

        fun statusText(status: Int): String = when (status) {
            STATUS_PENDING -> "等待中"
            STATUS_DOWNLOADING -> "下载中"
            STATUS_PAUSED -> "已暂停"
            STATUS_COMPLETED -> "已完成"
            STATUS_FAILED -> "失败"
            else -> "未知"
        }
    }
}
