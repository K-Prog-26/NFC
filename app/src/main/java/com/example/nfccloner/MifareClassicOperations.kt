package com.example.nfccloner

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import java.io.IOException

/**
 * MIFARE Classic 1K/4K support.
 *
 * Reading: authenticates each sector with a list of commonly used keys and
 * reads every block it can. Sectors it cannot authenticate are left blank
 * in the dump (this is normal for cards using custom/secret keys).
 *
 * Writing a full clone (including UID) only works onto "magic" Gen1a
 * MIFARE Classic cards, which accept an unauthenticated backdoor command
 * to unlock block 0. A normal MIFARE Classic card's block 0 (UID +
 * manufacturer data) is factory-locked and cannot be rewritten - on a
 * normal target card, only the data blocks (not block 0) can be cloned.
 *
 * Gen1a backdoor command support varies by phone NFC chipset. If it
 * doesn't work on your device, there is no software workaround.
 */
object MifareClassicOperations {

    // Common default/well-known MIFARE Classic keys, tried in order.
    val commonKeys: List<ByteArray> = listOf(
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), // factory default
        byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
        byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte()), // MAD key
        byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte()),
        byteArrayOf(0x4D, 0x3A, 0x99.toByte(), 0xC3.toByte(), 0x51, 0xDD.toByte()), byteArrayOf(0x1A, 0x98.toByte(), 0x2C, 0x7E, 0x45, 0x9A.toByte()) (0x99 became 0x99.toByte(), and 0x98 became 0x98.toByte())
    )

    data class SectorResult(
        val sectorIndex: Int,
        val keyUsed: ByteArray?,
        val blocks: List<ByteArray?> // null for a block we couldn't read
    )

    data class ReadResult(
        val uidHex: String,
        val cardType: String, // "1K" or "4K"
        val sectors: List<SectorResult>
    )

    fun readAll(tag: Tag): ReadResult {
        val mifare = MifareClassic.get(tag)
            ?: throw IOException("This tag is not a MIFARE Classic card")

        val cardType = if (mifare.size == MifareClassic.SIZE_4K) "4K" else "1K"
        val sectors = mutableListOf<SectorResult>()

        mifare.connect()
        try {
            for (sectorIndex in 0 until mifare.sectorCount) {
                var authedKey: ByteArray? = null
                for (key in commonKeys) {
                    val ok = try {
                        mifare.authenticateSectorWithKeyA(sectorIndex, key)
                    } catch (e: Exception) {
                        false
                    }
                    if (ok) { authedKey = key; break }
                }

                val blockCount = mifare.getBlockCountInSector(sectorIndex)
                val firstBlock = mifare.sectorToBlock(sectorIndex)
                val blocks = mutableListOf<ByteArray?>()

                if (authedKey != null) {
                    for (b in 0 until blockCount) {
                        val block = try {
                            mifare.readBlock(firstBlock + b)
                        } catch (e: Exception) {
                            null
                        }
                        blocks.add(block)
                    }
                } else {
                    // Could not authenticate; record as unreadable.
                    for (b in 0 until blockCount) blocks.add(null)
                }

                sectors.add(SectorResult(sectorIndex, authedKey, blocks))
            }
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }

        return ReadResult(tag.id.toHex(), cardType, sectors)
    }

    fun readResultToCardDump(result: ReadResult): CardDump {
        val blocks = mutableListOf<String>()
        result.sectors.forEach { sector ->
            sector.blocks.forEach { block ->
                blocks.add(block?.toHex() ?: "")
            }
        }
        return CardDump(
            type = "MIFARE_CLASSIC_${result.cardType}",
            uid = result.uidHex,
            blockSizeBytes = 16,
            blocks = blocks
        )
    }

    /**
     * Writes data blocks (NOT block 0 of sector 0, and not the sector
     * trailer blocks by default) onto a target card using the same common
     * key list to authenticate each sector first. Works on normal cards
     * that still use a known key, and is the right choice when you are
     * NOT using a magic card.
     *
     * @param writeTrailers if true, also overwrites sector trailers (access
     *   bits + keys). Dangerous: a bad trailer can permanently lock a
     *   sector. Defaults to false.
     */
    fun writeDataBlocks(tag: Tag, dump: CardDump, writeTrailers: Boolean = false): Int {
        val mifare = MifareClassic.get(tag) ?: throw IOException("Target is not a MIFARE Classic card")
        var written = 0
        mifare.connect()
        try {
            for (sectorIndex in 0 until mifare.sectorCount) {
                var authedKey: ByteArray? = null
                for (key in commonKeys) {
                    val ok = try { mifare.authenticateSectorWithKeyA(sectorIndex, key) } catch (e: Exception) { false }
                    if (ok) { authedKey = key; break }
                }
                if (authedKey == null) continue

                val blockCount = mifare.getBlockCountInSector(sectorIndex)
                val firstBlock = mifare.sectorToBlock(sectorIndex)
                for (b in 0 until blockCount) {
                    val absoluteBlock = firstBlock + b
                    val isTrailer = (b == blockCount - 1)
                    val isBlockZero = (absoluteBlock == 0)
                    if (isBlockZero) continue // never rewrite UID block on a normal card
                    if (isTrailer && !writeTrailers) continue
                    if (absoluteBlock >= dump.blocks.size) continue

                    val hex = dump.blocks[absoluteBlock]
                    if (hex.isEmpty()) continue
                    val data = hex.hexToBytes()
                    if (data.size != 16) continue

                    try {
                        mifare.writeBlock(absoluteBlock, data)
                        written++
                    } catch (e: Exception) {
                        // keep going even if one block fails
                    }
                }
            }
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
        return written
    }

    /**
     * Attempts to detect whether the presented tag is a Gen1a "magic" MIFARE
     * Classic card by sending the unauthenticated backdoor unlock command
     * and seeing if the card responds. This talks to the tag over raw NfcA,
     * bypassing normal authentication.
     */
    fun isMagicGen1a(tag: Tag): Boolean {
        val nfcA = NfcA.get(tag) ?: return false
        return try {
            nfcA.connect()
            val unlocked = sendUnlockSequence(nfcA)
            unlocked
        } catch (e: Exception) {
            false
        } finally {
            try { nfcA.close() } catch (_: Exception) {}
        }
    }

    private fun sendUnlockSequence(nfcA: NfcA): Boolean {
        return try {
            // Backdoor commands used by Gen1a magic cards (0x40 then 0x43).
            // These are 7-bit commands; many Android NFC chipsets accept
            // them transmitted as a single raw byte via transceive.
            val cmd40 = byteArrayOf(0x40)
            val resp40 = nfcA.transceive(cmd40)
            if (resp40.isEmpty()) return false
            val cmd43 = byteArrayOf(0x43)
            val resp43 = nfcA.transceive(cmd43)
            resp43.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Writes block 0 (UID + manufacturer bytes + BCC) onto a Gen1a magic
     * card. This is the step that makes the clone's UID match the source
     * card. Must be called on a connected NfcA session where the unlock
     * sequence has just succeeded, so this method manages its own
     * connect/close and re-sends the unlock right before writing.
     *
     * @param block0Data exactly 16 bytes: 4 bytes UID + 1 byte BCC +
     *   remaining manufacturer bytes, as read from the source card's block 0.
     */
    fun writeMagicBlockZero(tag: Tag, block0Data: ByteArray): Boolean {
        if (block0Data.size != 16) throw IllegalArgumentException("Block 0 must be exactly 16 bytes")
        val nfcA = NfcA.get(tag) ?: throw IOException("Tag does not support NfcA")
        return try {
            nfcA.connect()
            if (!sendUnlockSequence(nfcA)) {
                throw IOException("Could not unlock card - it may not be a Gen1a magic card, or your phone's NFC chipset doesn't support the backdoor command")
            }
            // WRITE command (0xA0) + block number, then the 16 data bytes + CRC
            // handled automatically by transceive for the data phase.
            val writeCmd = byteArrayOf(0xA0.toByte(), 0x00)
            val writeAck = nfcA.transceive(writeCmd)
            if (writeAck.isEmpty()) return false
            val dataResp = nfcA.transceive(block0Data)
            dataResp.isNotEmpty()
        } finally {
            try { nfcA.close() } catch (_: Exception) {}
        }
    }
}
