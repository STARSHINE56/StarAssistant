package com.xingchen.desktop

import com.yunx.app.data.network.model.DownloadLink
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DesktopTest {
    @Test fun credentialsAreEncryptedAndBackupRejectsWrongPassword() {
        val dir=Files.createTempDirectory("xingchen-store")
        val store=Store(dir)
        store.credentials().put("QUARK",org.json.JSONObject().put("credential","private-cookie"));store.save()
        assertFalse(String(Files.readAllBytes(dir.resolve("state.dat"))).contains("private-cookie"))
        assertEquals("private-cookie",Store(dir).credential("QUARK"))
        val backup=dir.resolve("backup.xca");store.exportAccounts(backup,"password123")
        val restored=Store(Files.createTempDirectory("xingchen-restore"))
        restored.importAccounts(backup,"password123");assertEquals("private-cookie",restored.credential("QUARK"))
        assertThrows(Exception::class.java) {restored.importAccounts(backup,"incorrect")}
    }
    @Test fun windowsReservedNamesAndTraversalAreHandled() {
        assertEquals("_CON.txt",Downloads.safeName("CON.txt"))
        assertEquals("a_b.mp4",Downloads.safeName("a:b.mp4"))
        assertThrows(IllegalArgumentException::class.java) { Downloads.safeName("../escape.txt") }
    }
    @Test fun realHttpBytesCompleteAndSurviveRestart() = runBlocking {
        val server=MockWebServer();server.start()
        val dir=Files.createTempDirectory("xingchen-download")
        val store=Store(dir.resolve("state"));store.settings().put("directory",dir.resolve("files").toString())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            server.enqueue(MockResponse().setBody("actual data").addHeader("ETag","\"v1\""))
            val downloads=Downloads(store,scope)
            val id=downloads.enqueue(DownloadLink("1","test.txt",server.url("/file").toString(),11),emptyMap(),"QUARK")
            withTimeout(15000) {while(downloads.get(id).status!="已完成") {if(downloads.get(id).status=="失败") fail("download failed");delay(20)}}
            assertEquals("actual data",Files.readString(java.nio.file.Path.of(downloads.get(id).path)))
            assertEquals(11L,downloads.get(id).done)
            assertTrue(Downloads(Store(dir.resolve("state")),scope).tasks.value.single().completed>0)
        } finally {scope.cancel();server.shutdown()}
    }
    @Test fun truncatedHttpResponseNeverEntersCompletedHistory() = runBlocking {
        val server=MockWebServer();server.start()
        val dir=Files.createTempDirectory("xingchen-truncated");val store=Store(dir.resolve("state"))
        store.settings().put("directory",dir.resolve("files").toString()).put("retries",0)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            server.enqueue(MockResponse().setBody("short"))
            val downloads=Downloads(store,scope)
            val id=downloads.enqueue(DownloadLink("1","bad.bin",server.url("/file").toString(),999),emptyMap(),"QUARK")
            withTimeout(15000) {while(downloads.get(id).status!="失败") delay(20)}
            assertFalse(Files.exists(java.nio.file.Path.of(downloads.get(id).path)))
            assertEquals(0L,downloads.get(id).completed)
        } finally {scope.cancel();server.shutdown()}
    }
}
