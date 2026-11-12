package com.example.nfccloner

import org.json.JSONArray
import org.json.JSONObject

/**
 * A generic in-memory representation of whatever was read off a card.
 * type is "NTAG21x" or "MIFARE_CLASSIC".
 * blocks is a list of pages/blocks, each stored as a hex string, in order starting at block 0.
 */
data class CardDump(
    val type: String,
    val uid: String,
    val blockSizeBytes: Int,
    val blocks: List<String>
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("uid", uid)
        obj.put("blockSizeBytes", blockSizeBytes)
        val arr = JSONArray()
        blocks.forEach { arr.put(it) }
        obj.put("blocks", arr)
        return obj.toString(2)
    }

    companion object {
        fun fromJson(json: String): CardDump {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("blocks")
            val blocks = mutableListOf<String>()
            for (i in 0 until arr.length()) blocks.add(arr.getString(i))
            return CardDump(
                type = obj.getString("type"),
                uid = obj.getString("uid"),
                blockSizeBytes = obj.getInt("blockSizeBytes"),
                blocks = blocks
            )
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = this.trim().replace(" ", "")
    val result = ByteArray(clean.length / 2)
    for (i in result.indices) {
        val index = i * 2
        result[i] = clean.substring(index, index + 2).toInt(16).toByte()
    }
    return result
}
