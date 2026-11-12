package com.example.nfccloner

import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import java.io.IOException

/**
 * NTAG213/215/216 are read/written through Android's MifareUltralight tech class.
 * Page size is always 4 bytes. Page counts differ by chip:
 *   NTAG213 -> 45 pages (0..44)
 *   NTAG215 -> 135 pages (0..134)
 *   NTAG216 -> 231 pages (0..230)
 * Pages 0-3 hold UID/manufacturer data and a lock byte; page 2 byte 2/3 and
 * page 3 are the static lock bytes. We treat pages 0-1 as read-only (UID),
 * since that UID is factory-burned on a normal (non-magic) NTAG tag.
 */
object Ntag21xOperations {

    const val NTAG213_PAGES = 45
    const val NTAG215_PAGES = 135
    const val NTAG216_PAGES = 231

    // First user-writable data page is typically 4 on NTAG21x.
    const val FIRST_USER_PAGE = 4

    data class ReadResult(val uidHex: String, val pages: List<ByteArray>, val chipName: String)

    fun detectPageCount(ultralight: MifareUltralight): Int {
        // No reliable public API tells us 213 vs 215 vs 216 directly, so we
        // probe by reading forward until a read fails.
        var count = 0
        try {
            ultralight.connect()
            var page = 0
            while (true) {
                try {
                    ultralight.readPages(page)
                    page += 4
                    count = page
                } catch (e: IOException) {
                    break
                }
                if (page > NTAG216_PAGES + 8) break // safety cap
            }
        } finally {
            try { ultralight.close() } catch (_: Exception) {}
        }
        return if (count == 0) NTAG215_PAGES else count
    }

    fun chipNameForPageCount(pages: Int): String = when {
        pages <= NTAG213_PAGES + 4 -> "NTAG213"
        pages <= NTAG215_PAGES + 4 -> "NTAG215"
        else -> "NTAG216"
    }

    /** Reads every page from 0 up to pageCount-1, four pages at a time. */
    fun readAll(tag: Tag, pageCount: Int = NTAG215_PAGES): ReadResult {
        val ultralight = MifareUltralight.get(tag)
            ?: throw IOException("This tag does not support MifareUltralight (not an NTAG21x/Ultralight chip)")

        val pages = mutableListOf<ByteArray>()
        ultralight.connect()
        try {
            var page = 0
            while (page < pageCount) {
                // readPages returns 4 pages (16 bytes) starting at `page`
                val chunk = ultralight.readPages(page)
                for (i in 0 until 4) {
                    if (page + i < pageCount) {
                        pages.add(chunk.copyOfRange(i * 4, i * 4 + 4))
                    }
                }
                page += 4
            }
        } finally {
            try { ultralight.close() } catch (_: Exception) {}
        }

        val uid = tag.id.toHex()
        val chipName = chipNameForPageCount(pageCount)
        return ReadResult(uid, pages, chipName)
    }

    /**
     * Writes data pages (page 4 onward) from a previously saved dump onto a
     * DIFFERENT physical tag. This does NOT touch pages 0-3 because the UID
     * on a standard NTAG21x cannot be changed - only the user data is cloned.
     * Returns the number of pages successfully written.
     */
    fun writeUserPages(tag: Tag, dump: CardDump, startPage: Int = FIRST_USER_PAGE): Int {
        val ultralight = MifareUltralight.get(tag)
            ?: throw IOException("Target tag does not support MifareUltralight")

        var written = 0
        ultralight.connect()
        try {
            for (i in startPage until dump.blocks.size) {
                val pageBytes = dump.blocks[i].hexToBytes()
                if (pageBytes.size != 4) continue
                ultralight.writePage(i, pageBytes)
                written++
            }
        } finally {
            try { ultralight.close() } catch (_: Exception) {}
        }
        return written
    }

    fun dumpToCardDump(result: ReadResult): CardDump {
        return CardDump(
            type = "NTAG21x:${result.chipName}",
            uid = result.uidHex,
            blockSizeBytes = 4,
            blocks = result.pages.map { it.toHex() }
        )
    }
}
