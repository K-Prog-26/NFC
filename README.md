# NFC Cloner (Android)

A Kotlin Android app for reading, saving, and cloning NFC cards using only
your phone's built-in NFC radio. Covers:

- **NTAG213 / 215 / 216** (and other MIFARE Ultralight-compatible tags):
  full read/write of user data pages.
- **MIFARE Classic 1K/4K**: read/write of data blocks using a list of
  commonly known keys, plus optional UID cloning onto a **Gen1a "magic"**
  MIFARE Classic card.

## How to open and run it

1. Install [Android Studio](https://developer.android.com/studio) (Hedgehog or newer).
2. Open this folder (`NfcCloner/`) as an existing project.
3. Let Gradle sync (it will download the Android Gradle Plugin and Kotlin plugin on first run — needs internet).
4. Plug in your Android phone with USB debugging enabled, select it as the run target, and press Run.
5. Grant any permission prompts. No special permissions are needed beyond NFC, which is declared in the manifest.

## How to use it

1. Tap **"1. Read Source"**, then hold the card you want to copy against the back of your phone.
2. The app auto-detects whether it's an NTAG21x tag or a MIFARE Classic card and reads it.
3. Optionally tap **"Save last read as file"** to keep the dump as JSON for later.
4. Tap **"2. Write to Target"**, then hold the blank/destination card against your phone.
5. For MIFARE Classic, check **"Also clone UID"** first if you have a Gen1a magic card as your target and want a true 1:1 clone (same UID). Leave it unchecked to clone only the data.

## What will and won't work

| Card | Read | Write data | Write UID |
|---|---|---|---|
| NTAG213/215/216 | Yes | Yes | **No** — UID is factory-burned on standard tags. Only "magic" NTAG tags (a different product) support this, and this app does not currently include that path. |
| MIFARE Classic, known key | Yes | Yes | Only onto a **Gen1a magic** MIFARE Classic target |
| MIFARE Classic, custom/unknown key | Partial — sectors with keys not in the common list will read as blank | N/A | N/A |
| MIFARE DESFire, iCLASS, other encrypted cards | No | No | No |
| 125 kHz cards (EM4100, HID Prox, T5577, etc.) | No | No | No — your phone's NFC hardware physically cannot talk to 125 kHz cards. Use your PM3/iCopyKey handheld for these. |

### Why the magic-card UID write might not work on your phone
The Gen1a backdoor unlock command (`0x40` / `0x43`) is a non-standard,
7-bit-wide command that falls outside the normal ISO 14443-A command set.
Whether it gets through depends on your phone's specific NFC controller
chip and its firmware — there is no way to know without testing on your
device, and there's no software fix if your chipset blocks it. If
`isMagicGen1a()` returns false on a card you know is a genuine Gen1a
blank, that's almost certainly the reason.

### Common MIFARE Classic keys tried
`FFFFFFFFFFFF`, `A0A1A2A3A4A5`, `D3F7D3F7D3F7` (MAD key), `000000000000`,
plus a few other widely published defaults. Cards using a custom key not
in this list will only be partially readable — this is expected behavior
for any key-based security scheme, not a bug.

## Project structure

```
app/src/main/java/com/example/nfccloner/
  MainActivity.kt              UI + NFC foreground dispatch + read/write flow
  Ntag21xOperations.kt         NTAG213/215/216 page read/write
  MifareClassicOperations.kt   MIFARE Classic sector auth, block read/write, Gen1a magic card support
  CardDump.kt                  Shared data model + hex helpers
  DumpStorage.kt                Save/load dumps as JSON in app-private storage
```

## Legal note

Only read, copy, or write cards you own or have explicit permission to
duplicate. Cloning someone else's access card (building/office doors,
hotel keys, transit passes, etc.) without authorization is illegal in
most jurisdictions, regardless of how trivial it is technically.

## Ideas for next steps

- Export/import dumps as `.json` files via Android's file picker (Storage Access Framework) so dumps can be shared between phones or backed up.
- Add NDEF-specific read/write screen (separate from raw page editing) for writing URLs/text/Wi-Fi credentials to NTAG21x tags.
- Add a raw hex page/block editor so individual bytes can be tweaked before writing.
- If you later get a Proxmark3 or a 125 kHz USB writer with a documented serial protocol, a companion Windows app (C#/.NET using PCSC-Sharp, or Python with pyscard/pyserial) can round out the 125 kHz side using the same JSON dump format as this app.
