package com.xingchen.desktop

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import java.awt.*
import java.nio.file.Path
import javax.swing.JFileChooser

object WindowsNative {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: Pointer, size: Int): Int
        fun DwmExtendFrameIntoClientArea(hwnd: HWND, margins: Pointer): Int
    }
    private val dwm by lazy { Native.load("dwmapi", Dwm::class.java) }
    private fun hwnd(window: Window) = HWND(Native.getComponentPointer(window))
    fun apply(window: Window, dark: Boolean, mica: Boolean): Boolean {
        if (!Platform.isWindows() || !window.isDisplayable) return false
        return runCatching {
            val handle=hwnd(window)
            val style = User32.INSTANCE.GetWindowLong(handle, -16)
            User32.INSTANCE.SetWindowLong(handle, -16, style or 0x00040000 or 0x00020000 or 0x00010000 or 0x00080000)
            User32.INSTANCE.SetWindowPos(handle,null,0,0,0,0,0x0020 or 0x0001 or 0x0002 or 0x0004)
            fun attribute(id: Int, value: Int): Int = Memory(4).use { memory ->
                memory.setInt(0,value);dwm.DwmSetWindowAttribute(handle,id,memory,4)
            }
            attribute(20,if(dark) 1 else 0)
            attribute(33,2) // DWMWCP_ROUND
            val hr=attribute(38,if(mica) 2 else 1) // DWMSBT_MAINWINDOW, Windows 11 22621+
            Memory(16).use { memory ->
                for(i in 0..3) memory.setInt(i*4L,if(mica && hr>=0) -1 else 0)
                dwm.DwmExtendFrameIntoClientArea(handle,memory)
            }
            mica && hr>=0
        }.getOrDefault(false)
    }
    fun minimize(window: Window) {
        if(Platform.isWindows()) User32.INSTANCE.ShowWindow(hwnd(window),6)
        else (window as Frame).extendedState=Frame.ICONIFIED
    }
    fun maximize(window: Window) {
        if(Platform.isWindows()) {
            val handle=hwnd(window);val placement=WinUser.WINDOWPLACEMENT()
            User32.INSTANCE.GetWindowPlacement(handle,placement)
            User32.INSTANCE.ShowWindow(handle,if(placement.showCmd==3) 9 else 3)
        } else (window as Frame).extendedState = if((window as Frame).extendedState==Frame.MAXIMIZED_BOTH) Frame.NORMAL else Frame.MAXIMIZED_BOTH
    }
    fun systemDark(): Boolean = if(Platform.isWindows()) runCatching {
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize","AppsUseLightTheme")==0
    }.getOrDefault(false) else false
    fun chooseDirectory(parent: Component): Path? {
        if(Platform.isWindows()) {
            val script="""
                Add-Type -AssemblyName System.Windows.Forms
                [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
                ${'$'}dialog = New-Object System.Windows.Forms.FolderBrowserDialog
                ${'$'}dialog.Description = '选择星辰助手下载目录'
                if (${'$'}dialog.ShowDialog() -eq 'OK') { [Console]::Write(${'$'}dialog.SelectedPath) }
            """.trimIndent()
            val encoded=java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            val process=ProcessBuilder("powershell.exe","-NoProfile","-STA","-EncodedCommand",encoded).start()
            val path=process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            if(process.waitFor()==0 && path.isNotBlank()) return Path.of(path)
            return null
        }
        val chooser=JFileChooser().apply { fileSelectionMode=JFileChooser.DIRECTORIES_ONLY }
        return if(chooser.showOpenDialog(parent)==JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }
    fun open(path: String) { Desktop.getDesktop().open(Path.of(path).toFile()) }
    fun reveal(path: String) {
        if(Platform.isWindows()) ProcessBuilder("explorer.exe","/select,",Path.of(path).toAbsolutePath().toString()).start()
        else Desktop.getDesktop().open(Path.of(path).parent.toFile())
    }
    fun browse(url: String) { Desktop.getDesktop().browse(java.net.URI(url)) }
    private var tray: TrayIcon? = null
    fun notifyCompleted(name: String) {
        if(!SystemTray.isSupported()) return
        EventQueue.invokeLater {
            runCatching {
                if(tray==null) {
                    val icon=java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    icon.createGraphics().apply { color=Color(100,85,235);fillRoundRect(0,0,32,32,10,10);color=Color.WHITE;drawString("星",7,22);dispose() }
                    tray=TrayIcon(icon,"星辰助手").apply { isImageAutoSize=true };SystemTray.getSystemTray().add(tray)
                }
                tray?.displayMessage("下载完成",name,TrayIcon.MessageType.INFO)
            }
        }
    }
    fun dispose() { tray?.let { SystemTray.getSystemTray().remove(it) };tray=null }
}
