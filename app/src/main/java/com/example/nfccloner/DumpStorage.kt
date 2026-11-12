package com.example.nfccloner

import android.content.Context
import java.io.File

object DumpStorage {
    private const val DIR_NAME = "card_dumps"

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun save(context: Context, name: String, dump: CardDump): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        val file = File(dir(context), "$safeName.json")
        file.writeText(dump.toJson())
        return file
    }

    fun load(context: Context, file: File): CardDump {
        return CardDump.fromJson(file.readText())
    }

    fun list(context: Context): List<File> {
        return dir(context).listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
