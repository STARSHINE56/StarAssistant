package com.yunx.app.data.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChunkDownloaderHttpTest {
    private fun withDownload(test: suspend (MockWebServer, ChunkDownloader, File) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val file = File.createTempFile("download-http-", ".part")
            try { test(server, ChunkDownloader { OkHttpClient() }, file) }
            finally { file.delete() }
        }
    }
    @Test fun resumesFromExistingBytesWithoutDuplicatingPrefix() = withDownload { s, d, f ->
        f.writeText("abc")
        s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        assertEquals(ChunkResult.OK, d.downloadChunk(1, s.url("/file").toString(), 0, 5, f, emptyMap()) {})
        assertEquals("bytes=3-5", s.takeRequest().getHeader("Range"))
        assertEquals("abcdef", f.readText())
    }
    @Test fun rejectsOversizedExistingPartWithoutClaimingComplete() = withDownload { s, d, f ->
        f.writeText("toolong")
        assertEquals(ChunkResult.FAILED, d.downloadChunk(1, s.url("/file").toString(), 0, 2, f, emptyMap()) {})
    }
    @Test fun rangeIgnoredFallsBackWithoutWritingFullResponseToPart() = withDownload { s, d, f ->
        s.enqueue(MockResponse().setBody("abcdef"))
        assertEquals(ChunkResult.RANGE_IGNORED, d.downloadChunk(1, s.url("/file").toString(), 0, 2, f, emptyMap()) {})
        assertEquals(0L, f.length())
    }
    @Test fun rejectsWrongRangeAndIncompleteOpenRange() = withDownload { s, d, f ->
        s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-2/3").setBody("bc"))
        assertEquals(ChunkResult.FAILED, d.downloadChunk(1, s.url("/file").toString(), 0, 2, f, emptyMap()) {})
        s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/6").setBody("abc"))
        assertEquals(ChunkResult.FAILED, d.downloadChunk(1, s.url("/file").toString(), 0, Long.MAX_VALUE, f, emptyMap()) {})
    }
    @Test fun fullGetCannotSilentlyTruncateLargerResource() = withDownload { s, d, f ->
        s.enqueue(MockResponse().setBody("abcdef"))
        assertThrows(IllegalStateException::class.java) { runBlocking { d.downloadFull(1, s.url("/file").toString(), f, emptyMap(), 3) {} } }
    }
    @Test fun fullGetRejectsUnsolicitedPartialAndExpiredResponses() = withDownload { s, d, f ->
        s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/6").setBody("abc"))
        assertThrows(IllegalStateException::class.java) { runBlocking { d.downloadFull(1, s.url("/file").toString(), f, emptyMap()) {} } }
        s.enqueue(MockResponse().setResponseCode(403))
        assertThrows(IllegalStateException::class.java) { runBlocking { d.downloadFull(1, s.url("/file").toString(), f, emptyMap()) {} } }
        assertEquals(403, d.consumeSourceExpiryFailure(1))
    }
}
