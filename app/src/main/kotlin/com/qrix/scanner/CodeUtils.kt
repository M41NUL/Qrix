package com.qrix.scanner

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Patterns
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream

object CodeUtils {

    /** One entry of the "Create Barcode" list. */
    data class BarcodeType(
        val name: String,
        val format: BarcodeFormat,
        val hint: String,
        val description: String,
        val example: String,
        val numeric: Boolean,
        val twoD: Boolean = false,
        val pattern: Regex? = null,
        val patternError: String? = null,
        val uppercase: Boolean = false
    )

    /** Barcode types (no QR – QR has its own list in [QrTypes]). */
    val BARCODE_TYPES: List<BarcodeType> = listOf(
        BarcodeType("Code 128", BarcodeFormat.CODE_128, "Text or number",
            "Most common. Letters, numbers and symbols.", "ABC-12345", false),
        BarcodeType("EAN-13", BarcodeFormat.EAN_13, "12 or 13 digits",
            "Retail products worldwide. 12 digits (check digit is added) or 13.", "5901234123457", true,
            pattern = Regex("\\d{12,13}"), patternError = "EAN-13 needs 12 or 13 digits"),
        BarcodeType("UPC-A", BarcodeFormat.UPC_A, "11 or 12 digits",
            "Retail products in USA / Canada. 11 or 12 digits.", "042100005264", true,
            pattern = Regex("\\d{11,12}"), patternError = "UPC-A needs 11 or 12 digits"),
        BarcodeType("EAN-8", BarcodeFormat.EAN_8, "7 or 8 digits",
            "Short retail code for small packages. 7 or 8 digits.", "96385074", true,
            pattern = Regex("\\d{7,8}"), patternError = "EAN-8 needs 7 or 8 digits"),
        BarcodeType("UPC-E", BarcodeFormat.UPC_E, "7 or 8 digits",
            "Compact UPC. 7 or 8 digits, starts with 0 or 1.", "0123456", true,
            pattern = Regex("[01]\\d{6,7}"), patternError = "UPC-E needs 7 or 8 digits starting with 0 or 1"),
        BarcodeType("Code 39", BarcodeFormat.CODE_39, "A–Z, 0–9 and - . \$ / + %",
            "Industrial & ID labels. Capital letters, digits and a few symbols.", "CODE-39", false,
            pattern = Regex("[A-Z0-9\\-. \$/+%]+"),
            patternError = "Code 39 allows A–Z, 0–9, space and - . \$ / + %", uppercase = true),
        BarcodeType("Code 93", BarcodeFormat.CODE_93, "A–Z, 0–9 and - . \$ / + %",
            "Denser than Code 39. Capital letters, digits and a few symbols.", "CODE93", false,
            pattern = Regex("[A-Z0-9\\-. \$/+%]+"),
            patternError = "Code 93 allows A–Z, 0–9, space and - . \$ / + %", uppercase = true),
        BarcodeType("ITF", BarcodeFormat.ITF, "Even number of digits",
            "Interleaved 2 of 5. Cartons & logistics. Even number of digits.", "12345678", true,
            pattern = Regex("(\\d\\d)+"), patternError = "ITF needs an even number of digits"),
        BarcodeType("Codabar", BarcodeFormat.CODABAR, "e.g. A123456B",
            "Libraries, blood banks. Start and end with A, B, C or D.", "A123456B", false,
            pattern = Regex("[A-D][0-9\\-\$:/.+]+[A-D]"),
            patternError = "Codabar must start and end with A–D (e.g. A123456B)", uppercase = true),
        BarcodeType("PDF417", BarcodeFormat.PDF_417, "Text or number",
            "2D stacked barcode. IDs, boarding passes, tickets.", "TICKET-2026-001", false, twoD = true),
        BarcodeType("Data Matrix", BarcodeFormat.DATA_MATRIX, "Text or number",
            "2D square barcode for tiny parts and labels.", "PART-4821", false, twoD = true),
        BarcodeType("Aztec", BarcodeFormat.AZTEC, "Text or number",
            "2D barcode used in transport tickets.", "BOARDING-77", false, twoD = true)
    )

    /** name -> ZXing format, for every generator (QR + barcodes). */
    val GEN_FORMATS: Map<String, BarcodeFormat> =
        linkedMapOf("QR Code" to BarcodeFormat.QR_CODE).also { m ->
            BARCODE_TYPES.forEach { m[it.name] = it.format }
        }

    fun formatName(f: Int): String = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR Code"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        else -> "Code"
    }

    private val urlRegex = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)

    fun isUrl(t: String) = urlRegex.matches(t.trim())

    private val socialHosts = listOf(
        "instagram.com", "facebook.com", "fb.com", "x.com", "twitter.com", "tiktok.com",
        "linkedin.com", "youtube.com", "t.me", "github.com", "snapchat.com", "threads.net",
        "pinterest.com", "reddit.com", "discord.gg", "twitch.tv", "medium.com", "behance.net",
        "m.me", "spotify.com"
    )

    private val cryptoSchemes = QrTypes.COINS.map { it.id }

    private fun isSocial(t: String): Boolean {
        val url = if (t.startsWith("www.", true)) "https://$t" else t
        val host: String = try {
            Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.")
        } catch (e: Exception) {
            ""
        }
        if (host.isEmpty()) return false
        return socialHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun isCrypto(l: String): Boolean =
        l.contains(':') && l.substringBefore(':') in cryptoSchemes

    fun typeOf(text: String, format: String): String {
        val t = text.trim()
        val l = t.lowercase()
        return when {
            isUrl(t) -> if (isSocial(t)) "Social" else "Link"
            isCrypto(l) -> "Crypto"
            l.startsWith("wifi:") -> "Wi-Fi"
            l.startsWith("mailto:") || Patterns.EMAIL_ADDRESS.matcher(t).matches() -> "Email"
            l.startsWith("tel:") -> "Phone"
            l.startsWith("smsto:") || l.startsWith("sms:") -> "SMS"
            l.startsWith("geo:") -> "Location"
            l.startsWith("begin:vcard") || l.startsWith("mecard:") -> "Contact"
            l.contains("begin:vevent") -> "Event"
            t.isNotEmpty() && t.all { it.isDigit() } && format != "QR Code" -> "Product"
            else -> "Text"
        }
    }

    /** Parses "WIFI:T:WPA;S:name;P:pass;H:true;;" into a map (T, S, P, H). */
    fun parseWifi(text: String): Map<String, String> = parseKeyValues(text.trim().substring(5))

    /** Short readable title for lists (history, recent scans). */
    fun titleOf(text: String, type: String): String = when (type) {
        "Wi-Fi" -> parseWifi(text)["S"]?.let { "Wi-Fi: $it" } ?: text
        "Contact" -> parseContact(text)["name"] ?: text
        "Event" -> parseEvent(text)["title"] ?: text
        "SMS" -> parseSms(text).first.ifEmpty { text }
        "Crypto" -> parseCrypto(text).let { c -> "${c["coin"]}: ${c["address"]}" }
        else -> text
    }.replace("\n", " ")

    fun iconFor(type: String): Int = when (type) {
        "Link" -> R.drawable.ic_link
        "Wi-Fi" -> R.drawable.ic_wifi
        "Email" -> R.drawable.ic_mail
        "Phone" -> R.drawable.ic_phone
        "SMS" -> R.drawable.ic_sms
        "Location" -> R.drawable.ic_location
        "Contact" -> R.drawable.ic_person
        "Event" -> R.drawable.ic_event
        "Crypto" -> R.drawable.ic_wallet
        "Social" -> R.drawable.ic_social
        "Product" -> R.drawable.ic_barcode
        else -> R.drawable.ic_text
    }

    /** Splits "K:v;K2:v2;;" (with \\ escapes) into a map. Used for Wi-Fi and MECARD. */
    private fun parseKeyValues(body: String): Map<String, String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var esc = false
        for (ch in body) {
            when {
                esc -> { sb.append(ch); esc = false }
                ch == '\\' -> esc = true
                ch == ';' -> { parts.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        val out = mutableMapOf<String, String>()
        parts.forEach {
            val i = it.indexOf(':')
            if (i > 0) out[it.substring(0, i).uppercase()] = it.substring(i + 1)
        }
        return out
    }

    /** Contact from a vCard or MECARD → keys: name, phone, email, org, title, addr, url, note. */
    fun parseContact(text: String): Map<String, String> {
        val t = text.trim()
        val out = linkedMapOf<String, String>()
        if (t.startsWith("mecard:", true)) {
            val m = parseKeyValues(t.substring(7))
            m["N"]?.let { n ->
                val p = n.split(',')
                out["name"] = if (p.size >= 2) "${p[1].trim()} ${p[0].trim()}".trim() else n
            }
            m["TEL"]?.let { out["phone"] = it }
            m["EMAIL"]?.let { out["email"] = it }
            m["ORG"]?.let { out["org"] = it }
            m["ADR"]?.let { out["addr"] = it.split(',').filter { s -> s.isNotBlank() }.joinToString(", ") }
            m["URL"]?.let { out["url"] = it }
            m["NOTE"]?.let { out["note"] = it }
            return out
        }
        // vCard: unfold wrapped lines first
        val lines = t.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        for (line in lines) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).substringBefore(';').substringAfterLast('.').uppercase()
            val v = line.substring(i + 1).replace("\\n", "\n").replace("\\,", ",").trim()
            if (v.isEmpty()) continue
            when (key) {
                "FN" -> out["name"] = v
                "N" -> if ("name" !in out) {
                    val p = v.split(';')
                    out["name"] = "${p.getOrElse(1) { "" }} ${p.getOrElse(0) { "" }}".trim()
                }
                "TEL" -> if ("phone" !in out) out["phone"] = v
                "EMAIL" -> if ("email" !in out) out["email"] = v
                "ORG" -> out["org"] = v.split(';').filter { it.isNotBlank() }.joinToString(" ")
                "TITLE" -> out["title"] = v
                "ADR" -> out["addr"] = v.split(';').filter { it.isNotBlank() }.joinToString(", ")
                "URL" -> out["url"] = v
                "NOTE" -> out["note"] = v
            }
        }
        return out
    }

    /** "bitcoin:ADDRESS?amount=0.1" and friends → keys: coin, address, amount. */
    fun parseCrypto(text: String): Map<String, String> {
        val t = text.trim()
        val scheme = t.substringBefore(':').lowercase()
        val rest = t.substringAfter(':').removePrefix("//")
        val address = rest.substringBefore('?').substringBefore('@')
        val params = rest.substringAfter('?', "").split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val out = linkedMapOf(
            "coin" to (QrTypes.COINS.firstOrNull { it.id == scheme }?.label
                ?: scheme.replaceFirstChar { it.uppercase() }),
            "address" to address
        )
        val amount = if (scheme == "ethereum") {
            // EIP-681 stores the value in wei
            params["value"]?.let {
                try {
                    java.math.BigDecimal(it)
                        .divide(java.math.BigDecimal("1000000000000000000"))
                        .stripTrailingZeros().toPlainString()
                } catch (e: Exception) {
                    null
                }
            }
        } else params["amount"] ?: params["tx_amount"]
        if (!amount.isNullOrBlank()) out["amount"] = amount
        return out
    }

    /** "SMSTO:number:body" or "sms:number?body=..." → (number, body). */
    fun parseSms(text: String): Pair<String, String> {
        val t = text.trim()
        val rest = t.substringAfter(':')
        return if (t.startsWith("smsto:", true)) {
            rest.substringBefore(':') to (if (rest.contains(':')) rest.substringAfter(':') else "")
        } else {
            val number = rest.substringBefore('?')
            val body = rest.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("body=", true) }
                ?.substringAfter('=')
                ?.let { try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } }
                .orEmpty()
            number to body
        }
    }

    /** Calendar event from VEVENT → keys: title, start, end (epoch millis), location, desc. */
    fun parseEvent(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val lines = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        for (line in lines) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).substringBefore(';').uppercase()
            val v = line.substring(i + 1).replace("\\n", "\n").replace("\\,", ",").trim()
            when (key) {
                "SUMMARY" -> out["title"] = v
                "LOCATION" -> out["location"] = v
                "DESCRIPTION" -> out["desc"] = v
                "DTSTART" -> parseIcalDate(v)?.let { out["start"] = it.toString() }
                "DTEND" -> parseIcalDate(v)?.let { out["end"] = it.toString() }
            }
        }
        return out
    }

    private fun parseIcalDate(v: String): Long? {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val tries = listOf(
            Triple("yyyyMMdd'T'HHmmss'Z'", utc, true),
            Triple("yyyyMMdd'T'HHmmss", java.util.TimeZone.getDefault(), true),
            Triple("yyyyMMdd", java.util.TimeZone.getDefault(), true)
        )
        for ((pattern, tz, _) in tries) {
            try {
                val f = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                f.timeZone = tz
                f.isLenient = false
                return f.parse(v)?.time ?: continue
            } catch (e: Exception) {
            }
        }
        return null
    }

    // ---------------------------------------------------------------- generate

    /**
     * Builds the code image. [fg], [bg] and [logo] only apply to QR codes; when a logo is
     * placed in the middle the highest error correction level is used so it still scans.
     */
    fun generate(
        text: String, name: String, size: Int = 900,
        fg: Int = Color.BLACK, bg: Int = Color.WHITE, logo: Bitmap? = null
    ): Bitmap {
        val fmt = GEN_FORMATS[name] ?: BarcodeFormat.QR_CODE
        val isQr = fmt == BarcodeFormat.QR_CODE
        val square = isQr || fmt == BarcodeFormat.AZTEC || fmt == BarcodeFormat.DATA_MATRIX
        val oneD = !square && fmt != BarcodeFormat.PDF_417
        val h = if (square) size else size / 3
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to (if (isQr) 2 else if (square) 1 else 10),
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        if (isQr && logo != null) hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H
        val on = if (isQr) fg else Color.BLACK
        val off = if (isQr) bg else Color.WHITE

        val m = MultiFormatWriter().encode(text, fmt, size, h, hints)
        val px = IntArray(m.width * m.height) {
            if (m.get(it % m.width, it / m.width)) on else off
        }
        val base = Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
        if (isQr && logo != null) return overlayLogo(base, logo, bg)
        if (!oneD) return base

        // 1D barcodes: print the value under the bars, like a retail label.
        val label = 64
        val out = Bitmap.createBitmap(base.width, base.height + label, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(base, 0f, 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            textSize = 38f
        }
        while (paint.measureText(text) > base.width - 40 && paint.textSize > 14f) paint.textSize -= 2f
        c.drawText(text, base.width / 2f, base.height + 44f, paint)
        return out
    }

    /** Puts the logo in the centre on a small, softly rounded plate in the background colour. */
    private fun overlayLogo(base: Bitmap, logo: Bitmap, bg: Int): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val cx = out.width / 2f
        val cy = out.height / 2f
        val box = out.width * 0.2f
        val pad = box * 0.12f

        val scale = minOf(box / logo.width, box / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        val img = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        val plate = RectF(img).apply { inset(-pad, -pad) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        c.drawRoundRect(plate, box * 0.22f, box * 0.22f, fill)

        val clip = android.graphics.Path().apply {
            addRoundRect(img, box * 0.14f, box * 0.14f, android.graphics.Path.Direction.CW)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.save()
        c.clipPath(clip)
        c.drawBitmap(logo, null, img, paint)
        c.restore()
        return out
    }

    fun saveToGallery(c: Context, bmp: Bitmap): Boolean {
        return try {
            val name = "Qrix_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Qrix")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = c.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return false
                resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Qrix"
                )
                dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaScannerConnection.scanFile(c, arrayOf(f.absolutePath), arrayOf("image/png"), null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun shareBitmap(c: Context, bmp: Bitmap) {
        val dir = File(c.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "qrix_code.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        c.startActivity(Intent.createChooser(i, "Share code"))
    }

    // ----------------------------------------------------------------- haptics

    /**
     * Buzz for a finished action. The old version sent a 60 ms pulse with no usage
     * information, which many phones silently drop or make too weak to feel.
     * Now: a clearly felt pulse, sent as an alarm-type vibration so system touch-feedback
     * and silent-mode settings don't swallow it. The in-app switch still turns it off.
     */
    fun vibrate(c: Context, strong: Boolean = true) {
        if (!Prefs.vibrate(c)) return
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                c.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!v.hasVibrator()) return
            val ms = if (strong) 130L else 40L
            val effect = if (v.hasAmplitudeControl())
                VibrationEffect.createOneShot(ms, 255)
            else
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            v.cancel()
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                legacyVibrate(v, effect)
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyVibrate(v: Vibrator, effect: VibrationEffect) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        v.vibrate(effect, attrs)
    }
}
