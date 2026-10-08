package com.yunx.app.data.announcement

import org.junit.Assert.*
import org.junit.Test

class AnnouncementCosTest {
    private fun item(id: String, extra: String = "") = """{"id":"$id","title":"标题","content":"**正文**","publishedAt":"2026-10-08T00:00:00+08:00"$extra}"""
    private fun feed(vararg items: String) = """{"schemaVersion":1,"enabled":true,"announcements":[${items.joinToString(",") }]}"""
    private val now = parseIsoMillis("2026-10-09T00:00:00+08:00")!!

    @Test fun adaptsRealCosFieldsAndPreservesMarkdown() {
        val list = AnnouncementApi.parseStaticFeed(feed(item("welcome", ",\"popup\":false,\"pinned\":true")), now)
        assertEquals("**正文**", list.single().content)
        assertFalse(list.single().popup)
        assertTrue(list.single().isPinned)
        assertEquals(1791388800000L, list.single().effectiveMillis)
    }
    @Test fun filtersDisabledExpiredFutureAndDuplicateIds() {
        val list = AnnouncementApi.parseStaticFeed(feed(item("a"), item("a"), item("b", ",\"enabled\":false"), item("c", ",\"expiresAt\":\"2026-10-08T01:00:00+08:00\""), """{"id":"future","publishedAt":"2099-01-01T00:00:00Z"}"""), now)
        assertEquals(listOf("a"), list.map { it.id })
        assertTrue(AnnouncementApi.parseStaticFeed("""{"schemaVersion":1,"enabled":false,"announcements":[]}""", now).isEmpty())
    }
    @Test fun popupUsesShownIdsIndependentlyOfReadAndPrefersPinned() {
        val list = AnnouncementApi.parseStaticFeed(feed(item("plain", ",\"popup\":true"), item("pin", ",\"popup\":true,\"pinned\":true"), item("silent", ",\"popup\":false")), now)
        assertEquals("pin", AnnouncementApi.pickPopupCandidate(list, emptySet())?.id)
        assertEquals("plain", AnnouncementApi.pickPopupCandidate(list, setOf("pin"))?.id)
        assertNull(AnnouncementApi.pickPopupCandidate(list, setOf("pin", "plain")))
    }
    @Test fun badFeedFailsRatherThanReplacingCacheWithEmptyList() {
        assertThrows(IllegalArgumentException::class.java) { AnnouncementApi.parseStaticFeed("""{"schemaVersion":99,"announcements":[]}""", now) }
        assertThrows(IllegalArgumentException::class.java) { AnnouncementApi.parseStaticFeed("""{"schemaVersion":1}""", now) }
        assertFalse(AnnouncementApi.cacheUsable(now - 8 * 86400000L, now, true))
        assertTrue(AnnouncementApi.cacheUsable(now - 86400000L, now, true))
        assertFalse(AnnouncementApi.cacheUsable(now - 86400000L, now, false))
        assertFalse(AnnouncementApi.cacheUsable(now + 1, now, true))
    }
    @Test fun isoOffsetsAreStrictAndCorrect() {
        assertEquals(parseIsoMillis("2026-10-07T16:00:00Z"), parseIsoMillis("2026-10-08T00:00:00+08:00"))
        assertNull(parseIsoMillis("2026-10-08T00:00:00Zgarbage"))
        assertNull(parseIsoMillis("2026-99-08T00:00:00Z"))
    }
}
