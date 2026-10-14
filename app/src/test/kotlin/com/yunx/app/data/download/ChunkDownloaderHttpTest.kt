package com.yunx.app.data.download

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.atomic.AtomicBoolean
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
    @Test fun cancellationInterruptsBlockedResponseWithoutWaitingReadTimeout() = withDownload { s, d, f ->
        s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
            d.downloadChunk(1, s.url("/file").toString(), 0, 2, f, emptyMap()) {}
        }
        kotlinx.coroutines.withContext(Dispatchers.IO) { s.takeRequest() }
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
    @Test fun preemptionInterruptsStalledConnectionAndResumes() = withDownload { s, d, f ->
        s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/3").setBody("abc"))
        val preempt = AtomicBoolean(false)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
            d.downloadChunk(1, s.url("/file").toString(), 0, 2, f, emptyMap(), preempt = preempt) {}
        }
        kotlinx.coroutines.withContext(Dispatchers.IO) { s.takeRequest() }
        preempt.set(true)
        assertEquals(ChunkResult.OK, withTimeout(3000) { job.await() })
        assertEquals("abc", f.readText())
    }
    @Test fun rejectsChangedEtagBeforeWriting() = withDownload { s, d, f ->
        f.writeText("abc")
        s.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"new\"").setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
        assertThrows(SourceChangedException::class.java) { runBlocking {
            d.downloadChunk(1, s.url("/file").toString(), 0, 5, f, emptyMap(), expectedTotal = 6, expectedEtag = "\"old\"") {}
        } }
        assertEquals("abc", f.readText())
    }
    @Test fun rejectsChangedTotalBeforeWriting() = withDownload { s, d, f ->
        s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/9").setBody("abc"))
        assertThrows(SourceChangedException::class.java) { runBlocking {
            d.downloadChunk(1, s.url("/file").toString(), 0, 2, f, emptyMap(), expectedTotal = 6) {}
        } }
        assertEquals(0, f.length())
    }
    @Test fun conditionalRangeCannotSilentlyFallBackToMixedContent() = withDownload { s, d, f ->
        f.writeText("abc")
        s.enqueue(MockResponse().setBody("abcdef"))
        assertThrows(SourceChangedException::class.java) { runBlocking {
            d.downloadChunk(1, s.url("/file").toString(), 0, 5, f, mapOf("If-Range" to "\"old\"")) {}
        } }
        assertEquals("abc", f.readText())
    }
    @Test fun serverCongestionDoesNotWriteErrorBody() = withDownload { s, d, f ->
        var wait = 0L
        s.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("slow down"))
        assertEquals(ChunkResult.FAILED, d.downloadChunk(1, s.url("/file").toString(), 0, 2, f, emptyMap(), onCongestion = { wait = it }) {})
        assertEquals(2000L, wait)
        assertEquals(0L, f.length())
    }
    @Test fun capturesResourceMetadataAndProtectsRangeHeader() = withDownload { s, d, _ ->
        s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/6").setHeader("ETag", "\"version\"").setBody("a"))
        val metadata = d.probeResource(s.url("/file").toString(), mapOf("Range" to "bytes=9-10"))!!
        assertEquals(6L, metadata.total)
        assertEquals("\"version\"", metadata.etag)
        assertEquals("bytes=0-0", s.takeRequest().getHeader("Range"))
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
