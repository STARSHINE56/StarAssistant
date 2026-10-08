package com.yunx.app.data.announcement

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AnnouncementCacheTest {
    @Test fun attachmentLoadsAndFailureFallsBackWithoutOverwritingCache() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/announcements.json").toString()
            val prefs = AnnouncementReadStoreTest().prefs()
            val client = OkHttpClient()
            val now = 1800000000000L
            val body = """{"schemaVersion":1,"enabled":true,"announcements":[{"id":"one","content":"正文","popup":true}]}"""
            server.enqueue(MockResponse().setHeader("Content-Disposition", "attachment").setBody(body))
            val first = AnnouncementApi.fetchStaticFeed(false, url, prefs, client, now)
            assertEquals("one", (first as AnnouncementApi.Result.Success).data.first.single().id)
            // Fresh persisted cache does not issue a second HTTP request.
            AnnouncementApi.fetchStaticFeed(false, url, prefs, client, now + 1000)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(500))
            val fallback = AnnouncementApi.fetchStaticFeed(true, url, prefs, client, now + 1000)
            assertNotNull((fallback as AnnouncementApi.Result.Success).data.second)
            assertEquals(body, prefs.getString(url, null))
            server.enqueue(MockResponse().setBody("not json"))
            assertTrue(AnnouncementApi.fetchStaticFeed(true, url, prefs, client, now + 2000) is AnnouncementApi.Result.Success)
            assertEquals(body, prefs.getString(url, null))
            server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(AnnouncementApi.fetchStaticFeed(false, url, prefs, client, now + 8 * 86400000L) is AnnouncementApi.Result.Failure)
        }
    }
}
