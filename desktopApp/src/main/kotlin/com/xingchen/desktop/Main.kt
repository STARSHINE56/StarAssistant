package com.xingchen.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.*
import org.json.JSONObject
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JFileChooser

private val brand=Color(0xff7160e8)
private val navigation=listOf("解析" to Icons.Outlined.Link,"网盘" to Icons.Outlined.Cloud,"下载" to Icons.Outlined.Download,
    "历史" to Icons.Outlined.History,"账号" to Icons.Outlined.Person,"设置" to Icons.Outlined.Settings)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() = application {
    val scope=rememberCoroutineScope()
    val store=remember { Store() }
    val model=remember { DesktopModel(store,scope) }
    val state=rememberWindowState(width=1280.dp,height=800.dp)
    var systemDark by remember { mutableStateOf(WindowsNative.systemDark()) }
    LaunchedEffect(Unit) { while(true) { systemDark=WindowsNative.systemDark();delay(2000) } }
    val revision=model.settingRevision
    val mode=store.settings().optString("theme","system")
    val dark=mode=="dark" || mode=="system" && systemDark
    val mica=store.settings().optBoolean("mica",true)
    var micaActive by remember { mutableStateOf(false) }
    Window(onCloseRequest={ model.downloads.shutdown();WindowsNative.dispose();exitApplication() },state=state,title="星辰助手",icon=painterResource("xingchen.png"),undecorated=true,transparent=true) {
        LaunchedEffect(dark,mica,revision) {
            window.minimumSize=java.awt.Dimension(800,480)
            micaActive=WindowsNative.apply(window,dark,mica)
        }
        MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=brand) else lightColorScheme(primary=brand)) {
            val base=if(dark) Color(0xff202024) else Color(0xfff4f4f8)
            Column(Modifier.fillMaxSize().background(if(micaActive) base.copy(alpha=.82f) else base,RoundedCornerShape(12.dp)).border(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f),RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().height(46.dp),verticalAlignment=Alignment.CenterVertically) {
                    WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) {
                        var lastClick by remember { mutableStateOf(0L) }
                        Row(Modifier.fillMaxSize().padding(start=18.dp).onPointerEvent(PointerEventType.Press) {
                            val now=System.currentTimeMillis();if(now-lastClick<350) WindowsNative.maximize(window);lastClick=now
                        },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.AutoAwesome,"应用图标",tint=brand,modifier=Modifier.size(20.dp))
                            Text("星辰助手",style=MaterialTheme.typography.titleSmall)
                        }
                    }
                    IconButton(onClick={WindowsNative.minimize(window)}) { Icon(Icons.Outlined.Remove,"最小化",modifier=Modifier.size(18.dp)) }
                    IconButton(onClick={WindowsNative.maximize(window)}) { Icon(Icons.Outlined.CropSquare,"最大化或还原",modifier=Modifier.size(16.dp)) }
                    IconButton(onClick={model.downloads.shutdown();WindowsNative.dispose();exitApplication()}) { Icon(Icons.Outlined.Close,"关闭",modifier=Modifier.size(18.dp)) }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact=maxWidth<1100.dp
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(if(compact) 68.dp else 188.dp).fillMaxHeight().padding(horizontal=8.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            navigation.forEach { (name,icon) ->
                                val chosen=model.page==name
                                val highlight by animateColorAsState(if(chosen) brand.copy(alpha=.16f) else Color.Transparent,
                                    animationSpec=tween(if(store.settings().optBoolean("animations",true)) 160 else 0))
                                Row(Modifier.fillMaxWidth().height(48.dp).background(highlight,RoundedCornerShape(10.dp))
                                    .clickable { model.page=name;if(name=="网盘" && !model.cloudMode) model.openCloud() }
                                    .padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Icon(icon,name,tint=if(chosen) brand else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(22.dp))
                                    if(!compact) Text(name,fontWeight=if(chosen) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().padding(start=12.dp,end=24.dp,top=14.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(if(model.page=="解析") "分享链接解析" else model.page,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
                            if(model.busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=brand)
                            if(model.message.isNotBlank()) Surface(shape=RoundedCornerShape(8.dp),color=brand.copy(alpha=.12f)) {
                                Text(model.message,Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium)
                            }
                            when(model.page) {
                                "解析" -> ResolvePage(model)
                                "网盘" -> DrivePage(model)
                                "下载" -> DownloadPage(model,false)
                                "历史" -> DownloadPage(model,true)
                                "账号" -> AccountsPage(model)
                                "设置" -> SettingsPage(model,window,micaActive)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Panel(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier,shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.90f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable private fun Action(label: String,icon: ImageVector?=null,enabled: Boolean=true,onClick: ()->Unit) {
    TextButton(onClick=onClick,enabled=enabled,contentPadding=PaddingValues(horizontal=8.dp,vertical=4.dp)) {
        if(icon!=null) { Icon(icon,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp)) };Text(label)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ResolvePage(m: DesktopModel) {
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) { SharePlatform.entries.forEach { Text(platformName(it),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
            OutlinedTextField(m.input,{m.input=it},Modifier.fillMaxWidth(),placeholder={Text("粘贴分享链接或含提取码的分享文案")},minLines=2,maxLines=3,shape=RoundedCornerShape(10.dp))
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Action("粘贴",Icons.Outlined.ContentPaste) { runCatching { m.input=Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String } }
                Action("清空",Icons.Outlined.Clear) {m.input=""}
                Button(onClick={m.resolve()},enabled=m.input.isNotBlank()&&!m.busy,shape=RoundedCornerShape(8.dp)) {Text("开始解析")}
            }
        }
        if(m.session!=null && !m.cloudMode) FileBrowser(m,Modifier.weight(1f))
        else {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("最近解析",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Action("清空历史") {m.saveRecent(emptyList())} }
            if(m.recent.isEmpty()) Text("还没有解析记录。粘贴分享链接开始使用。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(m.recent) { r -> Panel(Modifier.fillMaxWidth()) {
                    Text(r.optString("title"),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                    Text("${platformName(SharePlatform.valueOf(r.getString("platform")))} · ${time(r.optLong("time"))} · ${r.optString("status")}",style=MaterialTheme.typography.bodySmall)
                    Text(r.optString("link"),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                    Row { Action("再次打开") {m.input=r.getString("link");m.resolve()};Action("重新解析") {m.resolve(r.getString("link"))};Action("删除") {m.saveRecent(m.recent.filterNot {it===r})} }
                } }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DrivePage(m: DesktopModel) {
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { SharePlatform.entries.forEach { p ->
            FilterChip(selected=m.platform==p && m.cloudMode,onClick={m.openCloud(p)},label={Text(platformName(p))},enabled=!m.busy)
        } }
        if(m.cloudMode) FileBrowser(m,Modifier.weight(1f))
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FileBrowser(m: DesktopModel,modifier: Modifier) {
    Panel(modifier.fillMaxWidth()) {
        FlowRow(verticalArrangement=Arrangement.spacedBy(4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(m.title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.widthIn(max=350.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
            Action("上级",Icons.Outlined.ArrowUpward,!m.busy) {m.up()}
            Action("刷新",Icons.Outlined.Refresh,!m.busy) {m.refreshFiles()}
            Action("全选") {m.selected=m.files.map {it.fid}.toSet()}
            Action("批量下载 (${m.selected.size})",enabled=m.selected.isNotEmpty()&&!m.busy) {m.download(m.files.filter {it.fid in m.selected})}
        }
        if(m.files.isEmpty()&&!m.busy) Text("此目录没有文件，或平台未返回可见文件。")
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(m.files,key={it.fid}) { f ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.30f),RoundedCornerShape(8.dp)).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(f.fid in m.selected,{checked -> m.selected=if(checked) m.selected+f.fid else m.selected-f.fid})
                    Icon(if(f.isdir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,null,tint=if(f.isdir) brand else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(24.dp))
                    Column(Modifier.weight(1f).padding(horizontal=10.dp).clickable(enabled=f.isdir&&!m.busy) {m.folder(f)}) {
                        Text(f.fname,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                        Text("${if(f.isdir) "文件夹" else bytes(f.fsize)} ${f.modifyTime}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    if(f.isdir) Action("打开",enabled=!m.busy) {m.folder(f)}
                    if(!m.cloudMode) IconButton(onClick={m.saveShare(f)},enabled=!m.busy) {Icon(Icons.Outlined.CloudUpload,"保存到网盘根目录")}
                    IconButton(onClick={m.download(listOf(f))},enabled=!m.busy) {Icon(Icons.Outlined.Download,"下载")}
                }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DownloadPage(m: DesktopModel,history: Boolean) {
    val all by m.downloads.tasks.collectAsState()
    val list=all.filter {if(history) it.status=="已完成" else it.status!="已完成"}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(history) Row {Text("${list.size} 个已完成任务",Modifier.weight(1f));Action("清空历史") {m.downloads.clearCompleted()} }
        if(list.isEmpty()) Text(if(history) "还没有完成的下载。" else "下载队列为空。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(list,key={it.id}) {t -> Panel(Modifier.fillMaxWidth()) {
                Text(t.name,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(t.path,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val percentage=if(t.total>0) (t.done.toDouble()/t.total).coerceIn(0.0,1.0) else 0.0
                if(!history) LinearProgressIndicator(progress={percentage.toFloat()},modifier=Modifier.fillMaxWidth(),color=brand)
                val eta=if(t.speed>0 && t.total>t.done) "约 ${(t.total-t.done)/t.speed} 秒" else "—"
                Text(if(history) "${bytes(t.total)} · ${time(t.completed)} · ${t.platform}" else "${t.status} · ${bytes(t.done)} / ${bytes(t.total)} · %.1f%% · ${bytes(t.speed)}/s · $eta".format(percentage*100),style=MaterialTheme.typography.bodySmall)
                if(t.error.isNotBlank()) Text(t.error,color=MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(history) {
                        Action("打开文件") {m.action {WindowsNative.open(t.path)}}
                        Action("打开文件夹") {m.action {WindowsNative.reveal(t.path)}}
                        Action("重新下载") { m.action {
                            val refresh=m.downloads.sourceRefresh
                            val (link,headers)=if(refresh!=null&&t.source.isNotBlank()) refresh(t) else com.yunx.app.data.network.model.DownloadLink("",t.name,t.url,t.total,isHls=t.hls) to t.headers
                            m.downloads.enqueue(link,headers,t.platform,t.source)
                        } }
                    } else {
                        if(t.status in listOf("下载中","等待中")) Action("暂停") {m.downloads.pause(t.id)}
                        if(t.status in listOf("已暂停","失败","已取消")) Action(if(t.status=="失败") "重试" else "继续") {m.downloads.resume(t.id)}
                        Action("取消") {m.downloads.cancel(t.id)}
                        Action("打开文件夹") {m.action {WindowsNative.reveal(t.path)}}
                    }
                    Action("删除记录") {m.downloads.remove(t.id)}
                }
            } }
        }
    }
}
@Composable private fun AccountsPage(m: DesktopModel) {
    var editing by remember {mutableStateOf<SharePlatform?>(null)}
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Text("在浏览器登录后导入 Cookie、Token 或账号 JSON。认证只保存在本机，登录状态由网盘 API 验证。",style=MaterialTheme.typography.bodyMedium)}
        items(SharePlatform.entries) { p -> Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.Cloud,null,tint=brand);Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {Text(platformName(p),style=MaterialTheme.typography.titleMedium);Text(m.accounts[p] ?: if(m.drives.credential(p).isBlank()) "未登录" else "已保存认证，尚未验证",style=MaterialTheme.typography.bodySmall)}
                Action("登录 / 导入",enabled=!m.busy) {editing=p}
                Action("刷新状态",enabled=!m.busy) {m.checkAccount(p)}
                Action("退出") {m.logout(p)}
            }
        } }
    }
    editing?.let { p ->
        var value by remember(p) {mutableStateOf("")}
        AlertDialog(onDismissRequest={editing=null},title={Text("${platformName(p)}认证")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(p in listOf(SharePlatform.XUNLEI,SharePlatform.PAN123)) "粘贴 accessToken。迅雷可导入含 credential、refreshToken、deviceId、captchaToken 的 JSON。" else "粘贴完整 Cookie；移动云盘需包含账号信息和 Authorization。")
            OutlinedTextField(value,{value=it},Modifier.fillMaxWidth(),label={Text("认证信息")},visualTransformation=PasswordVisualTransformation(),maxLines=5)
            Action("打开平台官网") {m.action {WindowsNative.browse(loginUrl(p))}}
        }},confirmButton={TextButton(onClick={m.accountSave(p,value);editing=null},enabled=value.isNotBlank()) {Text("保存并验证")}},dismissButton={TextButton(onClick={editing=null}) {Text("取消")}})
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SettingsPage(m: DesktopModel,parent: java.awt.Component,micaActive: Boolean) {
    val revision=m.settingRevision
    val settings=m.store.settings()
    var backup by remember {mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Text("下载",style=MaterialTheme.typography.titleMedium)
            Text(settings.optString("directory",java.nio.file.Path.of(System.getProperty("user.home"),"Downloads","星辰助手").toString()),maxLines=2,overflow=TextOverflow.Ellipsis)
            Action("更改目录",Icons.Outlined.FolderOpen) {m.action { val path=withContext(Dispatchers.IO) {WindowsNative.chooseDirectory(parent)};if(path!=null) m.setting("directory",path.toString()) }}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                NumberSetting("最大并发",settings.optInt("concurrency",3),1..16) {m.setting("concurrency",it)}
                NumberSetting("重试次数",settings.optInt("retries",3),0..20) {m.setting("retries",it)}
                var limit by remember(revision) {mutableStateOf(settings.optLong("limitKB",0).toString())}
                OutlinedTextField(limit,{limit=it.filter(Char::isDigit)},Modifier.width(180.dp),label={Text("限速 KB/s，0 不限制")},singleLine=true)
                Action("保存限速") {m.setting("limitKB",limit.toLongOrNull()?.coerceIn(0,1048576) ?: 0)}
            }
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(settings.optBoolean("overwrite",false),{m.setting("overwrite",it)});Text("覆盖已存在文件（关闭时自动重命名）")}
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("外观",style=MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach { (key,label) ->
                FilterChip(settings.optString("theme","system")==key,{m.setting("theme",key)},label={Text(label)})
            } }
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(settings.optBoolean("mica",true),{m.setting("mica",it)});Text("Mica 系统背景 · ${if(micaActive) "已启用" else "关闭或系统不支持"}")}
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(settings.optBoolean("animations",true),{m.setting("animations",it)});Text("界面动画")}
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("账号与数据",style=MaterialTheme.typography.titleMedium)
            FlowRow {Action("导出认证") {backup="export"};Action("导入认证") {backup="import"};Action("清除账号") {SharePlatform.entries.forEach(m::logout)};Action("清除解析历史") {m.saveRecent(emptyList())};Action("清除下载历史") {m.downloads.clearCompleted()} }
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("软件",style=MaterialTheme.typography.titleMedium)
            Text("星辰助手 ${System.getProperty("xingchen.version") ?: "1.0.0"}")
            FlowRow {
                Action("检查更新") {m.action {
                    val version=withContext(Dispatchers.IO) {
                        val request=okhttp3.Request.Builder().url("https://api.github.com/repos/STARSHINE56/StarAssistant/releases/latest").build()
                        com.yunx.app.data.network.HttpClients.apiClient().newCall(request).execute().use {
                            if(!it.isSuccessful) error("检查更新失败")
                            JSONObject(it.body?.string() ?: error("空响应")).getString("tag_name")
                        }
                    }
                    m.message="当前版本 ${System.getProperty("xingchen.version") ?: "1.0.0"} · 最新版本 $version"
                }}
                Action("下载新版") {m.action {WindowsNative.browse("https://github.com/STARSHINE56/StarAssistant/releases/latest")}}
                Action("软件官网") {m.action {WindowsNative.browse("https://link3.cc/starshine9")}}
                Action("关于星辰助手") {m.message="星辰助手 · 基于 YunX（CYQawa）· AGPL-3.0"}
                Action("支持开发") {m.action {WindowsNative.browse("https://link3.cc/starshine9")}}
            }
        }
    }
    backup?.let { mode ->
        var password by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={backup=null},title={Text(if(mode=="export") "导出加密认证" else "导入加密认证")},text={OutlinedTextField(password,{password=it},label={Text("备份密码（至少 8 位）")},visualTransformation=PasswordVisualTransformation())},confirmButton={TextButton(enabled=password.length>=8,onClick={
            backup=null;m.action {
                val chooser=JFileChooser().apply { selectedFile=java.io.File("xingchen-accounts.xca") }
                val result=if(mode=="export") chooser.showSaveDialog(parent) else chooser.showOpenDialog(parent)
                if(result==JFileChooser.APPROVE_OPTION) {withContext(Dispatchers.IO) {
                    if(mode=="export") m.store.exportAccounts(chooser.selectedFile.toPath(),password) else m.store.importAccounts(chooser.selectedFile.toPath(),password)
                };m.accounts=emptyMap();m.message="认证${if(mode=="export") "导出" else "导入"}成功"}
            }
        }) {Text("选择文件")}},dismissButton={TextButton(onClick={backup=null}) {Text("取消")}})
    }
}
@Composable private fun NumberSetting(label: String,value: Int,range: IntRange,save: (Int)->Unit) {
    Column {Text(label,style=MaterialTheme.typography.labelMedium);Row(verticalAlignment=Alignment.CenterVertically) {
        Action("−",enabled=value>range.first) {save(value-1)};Text(value.toString());Action("+",enabled=value<range.last) {save(value+1)}
    } }
}
private fun time(value: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(value))
private fun loginUrl(p: SharePlatform) = when(p) {
    SharePlatform.QUARK -> "https://pan.quark.cn";SharePlatform.UC -> "https://drive.uc.cn";SharePlatform.XUNLEI -> "https://pan.xunlei.com"
    SharePlatform.BAIDU -> "https://pan.baidu.com";SharePlatform.PAN123 -> "https://www.123pan.com";SharePlatform.C139 -> "https://yun.139.com"
}
