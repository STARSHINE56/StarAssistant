package com.xingchen.desktop

import com.yunx.app.data.download.HlsDownloader
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.model.DownloadLink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class Downloads(private val store: Store, private val scope: CoroutineScope) {
    data class Task(val id: String, val name: String, val path: String, val platform: String,
                    val url: String, val headers: Map<String,String>, val total: Long,
                    val done: Long = 0, val speed: Long = 0, val status: String = "等待中",
                    val completed: Long = 0, val etag: String = "", val error: String = "",
                    val hls: Boolean = false, val source: String = "")
    private val mutable = MutableStateFlow<List<Task>>(emptyList())
    val tasks = mutable.asStateFlow()
    private val jobs = ConcurrentHashMap<String,Job>()
    private val calls = ConcurrentHashMap<String,Call>()
    var sourceRefresh: (suspend (Task) -> Pair<DownloadLink,Map<String,String>>)? = null
    var onCompleted: (suspend (Task) -> Unit)? = null
    init {
        val saved = store.data.optJSONArray("downloads") ?: JSONArray()
        mutable.value = (0 until saved.length()).map { decode(saved.getJSONObject(it)) }.map {
            if (it.status in listOf("下载中","等待中")) it.copy(status="已暂停",speed=0) else it
        }
    }
    @Synchronized private fun publish(list: List<Task>, persist: Boolean = true) {
        mutable.value = list
        if (persist) {
            store.data.put("downloads",JSONArray(list.map { encode(it) })); store.save()
        }
    }
    @Synchronized private fun update(id: String, persist: Boolean = true, action: (Task)->Task) = publish(mutable.value.map { if (it.id==id) action(it) else it },persist)
    fun get(id: String) = mutable.value.first { it.id == id }
    fun enqueue(link: DownloadLink, headers: Map<String,String>, platform: String, source: String = ""): String {
        val directory = Path.of(store.settings().optString("directory",Path.of(System.getProperty("user.home"),"Downloads","星辰助手").toString())).toAbsolutePath().normalize()
        Files.createDirectories(directory)
        val name = safeName(link.filename)
        var path = directory.resolve(name)
        val overwrite = store.settings().optBoolean("overwrite",false)
        var suffix = 1
        while (mutable.value.any { it.path == path.toString() && it.status != "已取消" } || (!overwrite && (Files.exists(path) || Files.exists(Path.of("$path.part"))))) {
            val ext = name.substringAfterLast('.',"");val base = if (ext.isBlank()) name else name.substringBeforeLast('.')
            path=directory.resolve("$base (${suffix++})" + if(ext.isBlank()) "" else ".$ext")
        }
        val task = Task(UUID.randomUUID().toString(),path.fileName.toString(),path.toString(),platform,link.downloadUrl,headers,link.size,hls=link.isHls,source=source)
        publish(mutable.value + task); schedule(); return task.id
    }
    @Synchronized fun resume(id: String) {
        if (jobs.containsKey(id)) return
        update(id) { it.copy(status="等待中",error="",speed=0) }; schedule()
    }
    @Synchronized private fun schedule() {
        val maxConcurrent = store.settings().optInt("concurrency",3).coerceIn(1,16)
        mutable.value.filter { it.status=="等待中" && !jobs.containsKey(it.id) }.take(max(0,maxConcurrent-jobs.size)).forEach { t ->
            val job = scope.launch(Dispatchers.IO, start=CoroutineStart.LAZY) {
                try { transfer(t.id) } finally { jobs.remove(t.id); schedule() }
            }
            jobs[t.id]=job;job.start()
        }
    }
    fun pause(id: String) {
        update(id) { it.copy(status="已暂停",speed=0) };calls[id]?.cancel();jobs[id]?.cancel()
    }
    fun cancel(id: String) {
        update(id) { it.copy(status="已取消",speed=0) };calls[id]?.cancel();jobs[id]?.cancel()
    }
    fun remove(id: String) {
        val job = jobs[id]
        if (job != null) {
            cancel(id)
            job.invokeOnCompletion { synchronized(this) { publish(mutable.value.filterNot { it.id==id }) } }
        } else publish(mutable.value.filterNot { it.id==id })
    }
    fun clearCompleted() = publish(mutable.value.filterNot { it.status=="已完成" })
    fun shutdown() {
        mutable.value.filter { it.status in listOf("下载中","等待中") }.forEach { pause(it.id) }
    }
    private suspend fun transfer(id: String) {
        update(id) { it.copy(status="下载中",error="") }
        val retries = store.settings().optInt("retries",3).coerceIn(0,20)
        var success = false
        var attempt = 0
        while (!success) {
            try {
                currentCoroutineContext().ensureActive()
                downloadOnce(id)
                success=true
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt++ >= retries) {
                    update(id) { it.copy(status="失败",speed=0,error="下载失败：请检查认证、链接、网络和磁盘空间后重试") }; return
                }
                if (sourceRefresh != null && get(id).source.isNotBlank()) {
                    try {
                        val (link,headers)=sourceRefresh!!.invoke(get(id))
                        update(id) { it.copy(url=link.downloadUrl,headers=headers,total=link.size,hls=link.isHls) }
                    } catch(e: CancellationException) { throw e } catch(_: Exception) { /* bounded retry below */ }
                }
                delay(1000L * attempt)
            }
        }
        update(id) { it.copy(status="已完成",completed=System.currentTimeMillis(),speed=0) }
        onCompleted?.invoke(get(id))
    }
    private suspend fun downloadOnce(id: String) {
        val task=get(id);val partial=Path.of(task.path+".part")
        if (task.hls) {
            Files.deleteIfExists(partial)
            update(id) { it.copy(done=0,total=-1) }
            var count=0L
            val ok=HlsDownloader.download(task.url,task.headers,partial.toFile()) { n ->
                currentCoroutineContext().ensureActive(); count+=n
                update(id,false) { it.copy(done=count) }
            }
            currentCoroutineContext().ensureActive()
            if (!ok) throw IOException("HLS failed")
            update(id) { it.copy(done=count,total=count) }
        } else {
            var offset=if(Files.exists(partial)) Files.size(partial) else 0L
            val builder=Request.Builder().url(task.url)
            task.headers.forEach { (k,v) -> builder.header(k,v) }
            if(offset>0) {
                // A previous server must supply a validator before safe resumption.
                if (task.etag.isBlank()) { Files.deleteIfExists(partial);offset=0 }
                else builder.header("Range","bytes=$offset-").header("If-Range",task.etag)
            }
            val origin=builder.build().url
            val client=HttpClients.downloadClient().newBuilder().addNetworkInterceptor { chain ->
                var request=chain.request()
                if(request.url.scheme!=origin.scheme || request.url.host!=origin.host || request.url.port!=origin.port) {
                    request=request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").removeHeader("Proxy-Authorization").removeHeader("Origin").removeHeader("Referer").build()
                }
                chain.proceed(request)
            }.build()
            val call=client.newCall(builder.build()); calls[id]=call
            val cancelHandle=currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            try { call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body=response.body ?: throw IOException("Empty body")
                val range=response.header("Content-Range").orEmpty()
                if (offset>0 && response.code==206 && !range.startsWith("bytes $offset-")) throw IOException("Invalid Range")
                if (response.code==206 && !range.startsWith("bytes $offset-")) throw IOException("Invalid Range")
                if (response.code==200) offset=0L
                val advertised=if(response.code==206) range.substringAfterLast('/').toLongOrNull() ?: -1L else body.contentLength()
                if(task.total>0 && advertised>0 && task.total!=advertised) throw IOException("Changed file size")
                val validator=response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified").orEmpty()
                update(id) { it.copy(done=offset,total=if(advertised>0) advertised else task.total,etag=validator) }
                RandomAccessFile(partial.toFile(),"rw").use { output ->
                    output.setLength(offset);output.seek(offset)
                    body.byteStream().use { input ->
                        val buffer=ByteArray(64*1024);var done=offset;val started=System.nanoTime();var tick=started;var last=done
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            val n=input.read(buffer);if(n<0) break
                            output.write(buffer,0,n);done+=n
                            val now=System.nanoTime()
                            if(now-tick>250_000_000) {
                                val speed=((done-last)*1_000_000_000/(now-tick)).coerceAtLeast(0)
                                update(id,false) { it.copy(done=done,speed=speed) };tick=now;last=done
                            }
                            val limit=store.settings().optLong("limitKB",0)*1024
                            if(limit>0) {
                                val expected=(done-offset)*1000/limit;val elapsed=(System.nanoTime()-started)/1_000_000
                                if(expected>elapsed) delay(expected-elapsed)
                            }
                        }
                        val total=get(id).total
                        if(total>0 && done!=total) throw IOException("Truncated download")
                        update(id) { it.copy(done=done,total=done) };output.fd.sync()
                    }
                }
            } } finally { calls.remove(id); cancelHandle.dispose() }
        }
        currentCoroutineContext().ensureActive()
        Files.move(partial,Path.of(task.path),StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        fun safeName(raw: String): String {
            require(!raw.replace('\\','/').split('/').any { it==".." || it=="." }) { "无效文件名" }
            var name=raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"),"_").trim().trimEnd('.',' ')
            if(name.isBlank()) name="download"
            if(Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$",RegexOption.IGNORE_CASE).matches(name)) name="_$name"
            return name.take(180)
        }
        fun encode(t: Task): JSONObject = JSONObject().put("id",t.id).put("name",t.name).put("path",t.path).put("platform",t.platform)
            .put("url",t.url).put("headers",JSONObject(t.headers)).put("total",t.total).put("done",t.done).put("status",t.status)
            .put("completed",t.completed).put("etag",t.etag).put("hls",t.hls).put("source",t.source)
        fun decode(j: JSONObject): Task {
            val headers=j.optJSONObject("headers") ?: JSONObject()
            return Task(j.getString("id"),j.getString("name"),j.getString("path"),j.getString("platform"),j.getString("url"),
                headers.keys().asSequence().associateWith { headers.getString(it) },j.optLong("total",-1),j.optLong("done"),status=j.getString("status"),
                completed=j.optLong("completed"),etag=j.optString("etag"),hls=j.optBoolean("hls"),source=j.optString("source"))
        }
    }
}
