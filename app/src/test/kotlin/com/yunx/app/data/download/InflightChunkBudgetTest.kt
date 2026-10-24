/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared Java buffer budget and conservative native thread/TLS ceiling. */
class InflightChunkBudgetTest {

    @Test
    fun clampsIntoSafeRange() {
        // 小堆仍保底 8 路
        assertTrue(DownloadManager.inflightChunksFor(16L * MB) >= 8)
        assertTrue(DownloadManager.inflightChunksFor(32L * MB) >= 8)
        assertTrue(DownloadManager.inflightChunksFor(64L * MB) >= 8)
        // 大堆也绝不放开到 512 路以上
        assertTrue(DownloadManager.inflightChunksFor(256L * MB) <= 512)
        assertTrue(DownloadManager.inflightChunksFor(512L * MB) <= 512)
        assertTrue(DownloadManager.inflightChunksFor(1024L * MB) <= 512)
        // Include a separate native thread/TLS ceiling rather than counting only read buffers.
        assertEquals(128, DownloadManager.inflightChunksFor(256L * MB))
        assertEquals(128, DownloadManager.inflightChunksFor(1024L * MB))
    }

    @Test
    fun totalBufferStaysWithinHeapBudget() {
        for (heapMb in longArrayOf(32, 64, 128, 256, 512)) {
            val heap = heapMb * MB
            val chunks = DownloadManager.inflightChunksFor(heap)
            val buffers = chunks.toLong() * BUFFER_SIZE
            assertTrue(
                "heap=${heapMb}MB chunks=$chunks buffers=$buffers",
                buffers <= heap / 8
            )
        }
    }

    @Test
    fun perStreamBufferIs64Kb() {
        assertEquals(64L * 1024, BUFFER_SIZE.toLong())
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
