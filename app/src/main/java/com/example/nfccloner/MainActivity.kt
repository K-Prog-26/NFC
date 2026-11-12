package com.example.nfccloner

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Two modes:
 *  READ  - tap the source card, we read it into memory (and can save it to a file)
 *  WRITE - tap a target (blank/different) card, we write the in-memory dump onto it
 *
 * UID cloning for MIFARE Classic is attempted only if "clone UID" is checked
 * AND the target responds to the Gen1a magic-card unlock sequence.
 * NTAG21x UID can never be rewritten by this app (normal tags don't allow it).
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { IDLE, READ, WRITE }
    private var mode = Mode.IDLE
    private var currentDump: CardDump? = null

    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var modeText: TextView
    private lateinit var logText: TextView
    private lateinit var btnSaveDump: Button
    private lateinit var checkCloneUid: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            Toast.makeText(this, "This device has no NFC hardware", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        nfcAdapter = adapter

        modeText = findViewById(R.id.modeText)
        logText = findViewById(R.id.logText)
        btnSaveDump = findViewById(R.id.btnSaveDump)
        checkCloneUid = findViewById(R.id.checkCloneUid)

        findViewById<Button>(R.id.btnReadMode).setOnClickListener {
            mode = Mode.READ
            modeText.text = "Mode: READ - tap the SOURCE card now"
        }

        findViewById<Button>(R.id.btnWriteMode).setOnClickListener {
            if (currentDump == null) {
                Toast.makeText(this, "Read or load a dump first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mode = Mode.WRITE
            modeText.text = "Mode: WRITE - tap the TARGET card now"
        }

        btnSaveDump.setOnClickListener { saveCurrentDump() }

        findViewById<Button>(R.id.btnLoadDump).setOnClickListener { showLoadDialog() }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val techLists = arrayOf(
            arrayOf(android.nfc.tech.NfcA::class.java.name),
            arrayOf(MifareClassic::class.java.name),
            arrayOf(MifareUltralight::class.java.name)
        )
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return

        when (mode) {
            Mode.READ -> handleRead(tag)
            Mode.WRITE -> handleWrite(tag)
            Mode.IDLE -> log("Tag detected but no mode selected. Tap 'Read Source' or 'Write to Target' first.")
        }
    }

    // ---------- READ ----------

    private fun handleRead(tag: Tag) {
        val techList = tag.techList.toList()
        log("Tag UID: ${tag.id.toHex()}")
        log("Tech list: $techList")

        try {
            when {
                techList.contains(MifareClassic::class.java.name) -> {
                    log("Reading as MIFARE Classic...")
                    val result = MifareClassicOperations.readAll(tag)
                    val authedSectors = result.sectors.count { it.keyUsed != null }
                    log("Authenticated $authedSectors / ${result.sectors.size} sectors using common keys.")
                    currentDump = MifareClassicOperations.readResultToCardDump(result)
                    log("Read complete: MIFARE Classic ${result.cardType}, UID ${result.uidHex}")
                }
                techList.contains(MifareUltralight::class.java.name) -> {
                    log("Reading as NTAG21x / Ultralight...")
                    val pageCount = Ntag21xOperations.detectPageCount(MifareUltralight.get(tag))
                    val result = Ntag21xOperations.readAll(tag, pageCount)
                    currentDump = Ntag21xOperations.dumpToCardDump(result)
                    log("Read complete: ${result.chipName}, UID ${result.uidHex}, ${result.pages.size} pages")
                }
                else -> {
                    log("Unsupported tag type for this app. Supported techs: MifareClassic, MifareUltralight/NTAG21x.")
                    return
                }
            }
            btnSaveDump.isEnabled = true
            mode = Mode.IDLE
            modeText.text = "Mode: Read complete. Save it, or tap 'Write to Target' to clone it."
        } catch (e: Exception) {
            log("ERROR reading tag: ${e.message}")
        }
    }

    // ---------- WRITE ----------

    private fun handleWrite(tag: Tag) {
        val dump = currentDump
        if (dump == null) {
            log("No dump loaded.")
            return
        }
        val techList = tag.techList.toList()
        log("Target UID: ${tag.id.toHex()}")

        try {
            when {
                dump.type.startsWith("MIFARE_CLASSIC") -> {
                    if (!techList.contains(MifareClassic::class.java.name)) {
                        log("Target is not a MIFARE Classic card. Dump type is ${dump.type}.")
                        return
                    }
                    val wantsUidClone = checkCloneUid.isChecked
                    if (wantsUidClone) {
                        log("Checking whether target is a Gen1a magic card...")
                        val isMagic = MifareClassicOperations.isMagicGen1a(tag)
                        if (isMagic) {
                            val block0 = dump.blocks.getOrNull(0)?.hexToBytes()
                            if (block0 != null && block0.size == 16) {
                                val ok = MifareClassicOperations.writeMagicBlockZero(tag, block0)
                                log(if (ok) "UID/block 0 written to magic card." else "Failed to write block 0 on magic card.")
                            } else {
                                log("Source dump has no valid block 0 data to clone.")
                            }
                        } else {
                            log("Target did not respond as a Gen1a magic card. Skipping UID clone; writing data blocks only.")
                        }
                    }
                    val written = MifareClassicOperations.writeDataBlocks(tag, dump, writeTrailers = false)
                    log("Wrote $written data blocks to target (sector trailers and block 0 of sector 0 left untouched unless magic-UID step ran above).")
                }
                dump.type.startsWith("NTAG21x") -> {
                    if (!techList.contains(MifareUltralight::class.java.name)) {
                        log("Target is not an NTAG21x/Ultralight card. Dump type is ${dump.type}.")
                        return
                    }
                    if (checkCloneUid.isChecked) {
                        log("Note: standard NTAG21x UID cannot be rewritten by this app. Only user data pages will be cloned.")
                    }
                    val written = Ntag21xOperations.writeUserPages(tag, dump)
                    log("Wrote $written user data pages to target NTAG21x card.")
                }
                else -> log("Unknown dump type: ${dump.type}")
            }
            mode = Mode.IDLE
            modeText.text = "Mode: Write complete."
        } catch (e: Exception) {
            log("ERROR writing tag: ${e.message}")
        }
    }

    // ---------- Save / Load ----------

    private fun saveCurrentDump() {
        val dump = currentDump ?: return
        val name = "dump_${System.currentTimeMillis()}"
        val file = DumpStorage.save(this, name, dump)
        log("Saved to ${file.name}")
        Toast.makeText(this, "Saved ${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showLoadDialog() {
        val files = DumpStorage.list(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "No saved dumps yet", Toast.LENGTH_SHORT).show()
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Load a saved dump")
            .setItems(names) { _, which -> loadDump(files[which]) }
            .show()
    }

    private fun loadDump(file: File) {
        currentDump = DumpStorage.load(this, file)
        btnSaveDump.isEnabled = true
        log("Loaded ${file.name}: type=${currentDump?.type}, uid=${currentDump?.uid}")
        modeText.text = "Mode: Dump loaded. Tap 'Write to Target' to clone it."
    }

    // ---------- Logging ----------

    private fun log(msg: String) {
        runOnUiThread {
            logText.text = "${logText.text}\n$msg"
        }
    }
}
