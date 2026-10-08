package com.yunx.app.data.announcement

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AnnouncementReadStoreTest {
    // An in-memory disk boundary; reconstructing the store tests persistence and state separation.
    internal fun prefs(): SharedPreferences {
        val values = mutableMapOf<String, Any>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString", "putLong" -> { values[args[0] as String] = args[1]; editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getLong" -> values[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
    @Test fun shownAndReadPersistIndependentlyAcrossRecreation() {
        val disk = prefs()
        val first = AnnouncementReadStore(disk)
        first.markShown("popup")
        first.markRead("detail")
        val next = AnnouncementReadStore(disk)
        assertEquals(setOf("popup"), next.shownIds())
        assertTrue(next.isRead("detail"))
        assertFalse(next.isRead("popup"))
        assertFalse("detail" in next.shownIds())
        next.markShown("popup")
        assertEquals(setOf("popup"), AnnouncementReadStore(disk).shownIds())
    }
    @Test fun batchReadDeduplicatesAndDoesNotErasePopupHistory() {
        val disk = prefs()
        val store = AnnouncementReadStore(disk)
        store.markShown("old")
        assertEquals(2, store.markAllRead(listOf("a", "a", " b ", "")))
        assertEquals(0, store.markAllRead(listOf("a", "b")))
        assertEquals(setOf("a", "b"), AnnouncementReadStore(disk).readIds.value)
        assertEquals(setOf("old"), AnnouncementReadStore(disk).shownIds())
    }
}
