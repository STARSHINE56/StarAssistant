package com.xingchen.desktop

import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.*
import com.yunx.app.data.repository.*
import org.json.JSONObject

class DriveService(private val store: Store) {
    val quark = QuarkApi()
    val uc = UCApi()
    val baidu = BaiduApi()
    val mobile = C139Api()
    val pan123 = Pan123Api()
    val xunlei = XunleiApi()
    init {
        quark.cookieSink = { cookie -> store.credentials().optJSONObject("QUARK")?.put("credential",cookie); store.save() }
        uc.cookieSink = { cookie -> store.credentials().optJSONObject("UC")?.put("credential",cookie); store.save() }
    }
    fun credential(p: SharePlatform) = store.credential(p.name)
    private fun account() = store.credentials().optJSONObject("XUNLEI") ?: JSONObject()
    fun deviceId() = account().optString("deviceId").ifBlank { XunleiDeviceFingerprint.deviceId() }
    fun captcha() = account().optString("captchaToken")
    private val repositories: Map<SharePlatform,ShareResolveRepository> = mapOf(
        SharePlatform.QUARK to QuarkResolveRepository(quark),
        SharePlatform.UC to UCResolveRepository(uc),
        SharePlatform.BAIDU to BaiduResolveRepository(baidu),
        SharePlatform.C139 to C139ResolveRepository(mobile),
        SharePlatform.PAN123 to Pan123ResolveRepository(pan123) { credential(SharePlatform.PAN123) },
        SharePlatform.XUNLEI to XunleiResolveRepository(xunlei,
            { credential(SharePlatform.XUNLEI).takeIf { it.isNotBlank() } }, { deviceId() }, { captcha() }, {
                val refresh = account().optString("refreshToken")
                if (refresh.isBlank()) null else xunlei.refreshToken(refresh, deviceId())?.also { (access, next) ->
                    account().put("credential", access).put("refreshToken", next); store.save()
                }
            })
    )
    fun repository(p: SharePlatform) = repositories.getValue(p)
    fun root(p: SharePlatform) = if (p == SharePlatform.BAIDU) "/" else "0"
    suspend fun refreshCredential(p: SharePlatform): String {
        val original = credential(p)
        val fresh = when(p) {
            SharePlatform.QUARK -> quark.refreshSession(original)
            SharePlatform.UC -> uc.refreshSession(original)
            else -> null
        }
        if (!fresh.isNullOrBlank()) { store.credentials().getJSONObject(p.name).put("credential",fresh); store.save() }
        return fresh ?: original
    }
    suspend fun accountStatus(p: SharePlatform): String {
        val c = credential(p)
        require(c.isNotBlank()) { "未登录" }
        val quota = when(p) {
            SharePlatform.QUARK -> quark.getQuota(c)
            SharePlatform.UC -> uc.getQuota(c)
            SharePlatform.BAIDU -> baidu.getQuota(c)
            SharePlatform.C139 -> mobile.getQuota(c)
            SharePlatform.PAN123 -> pan123.getQuota(c)
            SharePlatform.XUNLEI -> xunlei.getQuota(c,deviceId(),captcha())
        } ?: error("认证失效或网络不可用，请重新验证")
        return "已登录 · ${bytes(quota.used)} / ${bytes(quota.total)}"
    }
    suspend fun cloud(p: SharePlatform, dir: String): List<ShareFile> {
        val c = refreshCredential(p)
        require(c.isNotBlank()) { "请先在账号页导入认证" }
        return when(p) {
            SharePlatform.QUARK -> quark.listCloudFiles(dir,c) ?: error("获取文件失败")
            SharePlatform.UC -> uc.listCloudFiles(dir,c) ?: error("获取文件失败")
            SharePlatform.BAIDU -> baidu.listCloudFiles(dir,c)
            SharePlatform.C139 -> mobile.listCloudFiles(dir,c)
            SharePlatform.PAN123 -> pan123.listCloudFiles(dir,c)
            SharePlatform.XUNLEI -> xunlei.getFiles(dir,c,deviceId(),captcha()) ?: emptyList()
        }
    }
    suspend fun cloudLink(p: SharePlatform, file: ShareFile): DownloadLink {
        val c = refreshCredential(p)
        return when(p) {
            SharePlatform.QUARK -> quark.getDownloadLink(file.fid,c)
            SharePlatform.UC -> uc.cloudGetDownloadLink(file.fid,c)
            SharePlatform.BAIDU -> DownloadLink(file.fid,file.fname,baidu.fileMetasDlink(file.fid,c),file.fsize)
            SharePlatform.C139 -> mobile.getDownloadUrl(file.fid,c)
            SharePlatform.PAN123 -> pan123.getDownloadLink(file,c)
            SharePlatform.XUNLEI -> xunlei.getFileDetail(file.fid,c,deviceId(),captcha())
        } ?: error("获取下载地址失败")
    }
    fun headers(p: SharePlatform): Map<String,String> = when(p) {
        SharePlatform.QUARK -> mapOf("Cookie" to credential(p), "User-Agent" to QuarkConstants.API_USER_AGENT, "Referer" to QuarkConstants.DOWNLOAD_REFERER)
        SharePlatform.UC -> mapOf("Cookie" to credential(p), "User-Agent" to UCConstants.USER_AGENT, "Referer" to UCConstants.DOWNLOAD_REFERER, "Origin" to UCConstants.WEB_ORIGIN)
        SharePlatform.BAIDU -> mapOf("Cookie" to credential(p), "User-Agent" to BaiduConstants.UA_NETDISK)
        SharePlatform.C139 -> mapOf("User-Agent" to C139Constants.PC_UA)
        SharePlatform.PAN123 -> mapOf("User-Agent" to Pan123Constants.WEB_UA, "Referer" to Pan123Constants.DOWNLOAD_REFERER)
        SharePlatform.XUNLEI -> mapOf("User-Agent" to XunleiConstants.APP_UA)
    }
}
fun platformName(p: SharePlatform) = when(p) {
    SharePlatform.QUARK -> "夸克"; SharePlatform.UC -> "UC"; SharePlatform.XUNLEI -> "迅雷"
    SharePlatform.BAIDU -> "百度网盘"; SharePlatform.PAN123 -> "123云盘"; SharePlatform.C139 -> "移动云盘"
}
fun bytes(n: Long): String = when {
    n < 0 -> "未知"; n < 1024 -> "$n B"; n < 1048576 -> "%.1f KB".format(n/1024.0)
    n < 1073741824 -> "%.1f MB".format(n/1048576.0); else -> "%.2f GB".format(n/1073741824.0)
}
