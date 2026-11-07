package com.xingchen.desktop

import androidx.compose.runtime.*
import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class DesktopModel(val store: Store, val scope: CoroutineScope) {
    val drives=DriveService(store)
    val downloads=Downloads(store,scope)
    var page by mutableStateOf("解析")
    var input by mutableStateOf("")
    var platform by mutableStateOf(SharePlatform.QUARK)
    var files by mutableStateOf<List<ShareFile>>(emptyList())
    var session by mutableStateOf<ShareSession?>(null)
    var cloudMode by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var dir by mutableStateOf("0")
    var title by mutableStateOf("")
    var selected by mutableStateOf<Set<String>>(emptySet())
    var recent by mutableStateOf<List<JSONObject>>(emptyList())
    var accounts by mutableStateOf<Map<SharePlatform,String>>(emptyMap())
    var settingRevision by mutableStateOf(0)
    private var stack=ArrayDeque<Pair<String,String>>()
    init {
        loadRecent()
        downloads.sourceRefresh = { task ->
            val source=JSONObject(task.source);val p=SharePlatform.valueOf(task.platform)
            val file=fileFromJson(source.getJSONObject("file"))
            val link=if(source.optBoolean("cloud")) drives.cloudLink(p,file) else {
                val s=source.getJSONObject("session")
                drives.refreshCredential(p)
                drives.repository(p).getShareDownloadLink(ShareSession(s.getString("id"),s.getString("token"),s.getString("title")),file,drives.credential(p)).getOrThrow()
            }
            link to drives.headers(p)
        }
        downloads.onCompleted = { task ->
            WindowsNative.notifyCompleted(task.name)
            val cleanup = runCatching { JSONObject(task.source).optString("cleanupDir") }.getOrDefault("")
            if (cleanup.isNotBlank()) {
                val p = SharePlatform.valueOf(task.platform)
                runCatching { drives.repository(p).cleanupTempDir(cleanup, drives.credential(p)) }
            }
        }
    }
    fun loadRecent() {
        val list=store.data.optJSONArray("recent") ?: JSONArray()
        recent=(0 until list.length()).map { list.getJSONObject(it) }
    }
    fun saveRecent(list: List<JSONObject>) { recent=list;store.data.put("recent",JSONArray(list));store.save() }
    fun action(block: suspend ()->Unit) {
        if(busy) return
        scope.launch {
            busy=true;message=""
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { message="操作失败，请检查认证、提取码、网络或平台限制后重试" }
            finally { busy=false }
        }
    }
    fun resolve(link: String = input) = action {
        val parsed=ShareLinkParser.parse(link) ?: error("无法识别链接")
        platform=parsed.platform;cloudMode=false;session=null;files=emptyList();selected=emptySet();stack.clear()
        val record=JSONObject().put("link",link).put("platform",platform.name).put("time",System.currentTimeMillis()).put("title",parsed.shareId).put("status","解析中")
        saveRecent((listOf(record)+recent.filterNot { it.optString("link")==link }).take(100))
        try {
            drives.refreshCredential(platform)
            val s=drives.repository(platform).createSession(link,parsed.pwd,drives.credential(platform)).getOrThrow()
            val result=drives.repository(platform).listFiles(s,drives.root(platform),drives.credential(platform)).getOrThrow()
            session=s;dir=drives.root(platform);title=s.title;files=result;page="解析"
            record.put("title",s.title).put("status","成功");saveRecent(recent)
        } catch(e: Exception) {
            record.put("status","失败");saveRecent(recent);throw e
        }
    }
    fun openCloud(p: SharePlatform = platform) = action {
        platform=p;cloudMode=true;session=null;stack.clear();dir=drives.root(p);title=platformName(p)
        files=emptyList();selected=emptySet();files=drives.cloud(p,dir)
    }
    private suspend fun list(directory: String) = if(cloudMode) drives.cloud(platform,directory)
        else drives.repository(platform).listFiles(session ?: error("请重新解析"),directory,drives.credential(platform)).getOrThrow()
    fun folder(file: ShareFile) = action {
        val next=if(platform==SharePlatform.BAIDU && cloudMode) file.fid else file.fid
        val result=list(next);stack.addLast(dir to title);dir=next;title=file.fname;files=result;selected=emptySet()
    }
    fun up() = action {
        if(stack.isNotEmpty()) { val target=stack.last();val result=list(target.first);stack.removeLast();dir=target.first;title=target.second;files=result;selected=emptySet() }
    }
    fun refreshFiles() = action { files=list(dir);selected=emptySet() }
    fun download(items: List<ShareFile>) = action {
        val p=platform;val s=session;val cloud=cloudMode
        suspend fun add(file: ShareFile, depth: Int) {
            require(depth<=12) { "文件夹层数过深" }
            if(file.isdir) {
                val nested=if(cloud) drives.cloud(p,file.fid) else drives.repository(p).listFiles(s!!,file.fid,drives.credential(p)).getOrThrow()
                nested.forEach { add(it,depth+1) };return
            }
            drives.refreshCredential(p)
            val link=if(cloud) drives.cloudLink(p,file) else drives.repository(p).getShareDownloadLink(s!!,file,drives.credential(p)).getOrThrow()
            val source=JSONObject().put("cloud",cloud).put("file",fileToJson(file)).put("cleanupDir",link.cleanupDirFid.orEmpty())
            if(s!=null) source.put("session",JSONObject().put("id",s.shareId).put("token",s.stoken).put("title",s.title))
            downloads.enqueue(link,drives.headers(p),p.name,source.toString())
        }
        items.forEach { add(it,0) };message="已加入下载队列";selected=emptySet()
    }
    fun saveShare(file: ShareFile) = action {
        val s=session ?: error("请先解析分享")
        drives.repository(platform).transferFile(s,file,drives.root(platform),drives.credential(platform)).getOrThrow()
        message="已保存到网盘根目录"
    }
    fun checkAccount(p: SharePlatform) = action {
        accounts=accounts+(p to "验证中")
        val status=try { drives.accountStatus(p) } catch(_: Exception) { "未验证 / 认证失效 / 网络不可用" }
        accounts=accounts+(p to status)
    }
    fun accountSave(p: SharePlatform, text: String) = action {
        // Raw Cookie/Token or Android-compatible account JSON including optional refresh/device/captcha fields.
        val account=if(text.trim().startsWith("{")) JSONObject(text) else JSONObject().put("credential",text.trim().removePrefix("Bearer "))
        if(!account.has("credential")) account.put("credential",account.optString("cookie").ifBlank { account.optString("accessToken") })
        require(account.optString("credential").isNotBlank())
        store.credentials().put(p.name,account);store.save()
        accounts=accounts+(p to try { drives.accountStatus(p) } catch(_: Exception) { "已导入，验证失败" })
    }
    fun logout(p: SharePlatform) { store.credentials().remove(p.name);store.save();accounts=accounts+(p to "未登录") }
    fun setting(key: String, value: Any) { store.settings().put(key,value);store.save();settingRevision++ }
    companion object {
        fun fileToJson(f: ShareFile) = JSONObject().put("id",f.fid).put("name",f.fname).put("size",f.fsize).put("dir",f.isdir).put("parent",f.pdirFid).put("token",f.fidToken)
        fun fileFromJson(j: JSONObject) = ShareFile(j.getString("id"),j.getString("name"),j.getLong("size"),j.getBoolean("dir"),j.getString("parent"),j.getString("token"))
    }
}
