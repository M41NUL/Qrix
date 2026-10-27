package com.qrix.scanner

import android.net.Uri
import android.text.InputType
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Thrown by a type's builder when the form is not valid. [key] is the field to highlight. */
class FormError(val key: String?, message: String) : Exception(message)

enum class FieldKind { TEXT, MULTILINE, EMAIL, PHONE, URL, DECIMAL, PASSWORD, DATETIME, CHOICE, SWITCH, NUMBER, CAPS }

data class FieldSpec(
    val key: String,
    val hint: String,
    val kind: FieldKind = FieldKind.TEXT,
    val required: Boolean = false,
    /** For CHOICE: value to label pairs, the first one is selected by default. */
    val options: List<Pair<String, String>> = emptyList()
) {
    fun inputType(): Int = when (kind) {
        FieldKind.MULTILINE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        FieldKind.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        FieldKind.PHONE -> InputType.TYPE_CLASS_PHONE
        FieldKind.URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        FieldKind.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        FieldKind.NUMBER -> InputType.TYPE_CLASS_NUMBER
        FieldKind.CAPS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        FieldKind.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
}

/** One entry of the "Create QR Code" list: a form + a function that turns it into QR text. */
class QrType(
    val name: String,
    val icon: Int,
    val description: String,
    val fields: List<FieldSpec>,
    val build: (Map<String, String>) -> String
)

object QrTypes {

    private fun Map<String, String>.v(k: String) = this[k]?.trim().orEmpty()

    private fun need(m: Map<String, String>, key: String, msg: String): String {
        val x = m.v(key)
        if (x.isEmpty()) throw FormError(key, msg)
        return x
    }

    private fun withScheme(u: String): String =
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(u)) u else "https://$u"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;")
        .replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")

    private fun vEsc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;")
        .replace(",", "\\,").replace("\n", "\\n")

    private fun icalDate(millis: Long): String {
        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(millis))
    }

    class Coin(val id: String, val label: String, val regex: Regex)

    /** URI scheme = [Coin.id]. */
    val COINS: List<Coin> = listOf(
        Coin("bitcoin", "Bitcoin", Regex("(bc1|[13])[a-zA-HJ-NP-Z0-9]{25,62}")),
        Coin("ethereum", "Ethereum", Regex("0x[a-fA-F0-9]{40}")),
        Coin("litecoin", "Litecoin", Regex("(ltc1|[LM3])[a-zA-HJ-NP-Z0-9]{26,62}")),
        Coin("dogecoin", "Dogecoin", Regex("D[5-9A-HJ-NP-U][1-9A-HJ-NP-Za-km-z]{32}")),
        Coin("solana", "Solana", Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")),
        Coin("bitcoincash", "Bitcoin Cash", Regex("[qp][a-z0-9]{41}")),
        Coin("ripple", "XRP", Regex("r[1-9A-HJ-NP-Za-km-z]{24,34}")),
        Coin("tron", "Tron", Regex("T[1-9A-HJ-NP-Za-km-z]{33}")),
        Coin("dash", "Dash", Regex("X[1-9A-HJ-NP-Za-km-z]{33}")),
        Coin("monero", "Monero", Regex("[48][0-9AB][1-9A-HJ-NP-Za-km-z]{93}"))
    )

    class Social(val id: String, val label: String, val url: String)

    val SOCIALS: List<Social> = listOf(
        Social("instagram", "Instagram", "https://instagram.com/{u}"),
        Social("facebook", "Facebook", "https://facebook.com/{u}"),
        Social("x", "X", "https://x.com/{u}"),
        Social("tiktok", "TikTok", "https://www.tiktok.com/@{u}"),
        Social("youtube", "YouTube", "https://www.youtube.com/@{u}"),
        Social("linkedin", "LinkedIn", "https://www.linkedin.com/in/{u}"),
        Social("telegram", "Telegram", "https://t.me/{u}"),
        Social("messenger", "Messenger", "https://m.me/{u}"),
        Social("threads", "Threads", "https://www.threads.net/@{u}"),
        Social("snapchat", "Snapchat", "https://www.snapchat.com/add/{u}"),
        Social("pinterest", "Pinterest", "https://www.pinterest.com/{u}"),
        Social("reddit", "Reddit", "https://www.reddit.com/user/{u}"),
        Social("github", "GitHub", "https://github.com/{u}"),
        Social("discord", "Discord", "https://discord.gg/{u}"),
        Social("twitch", "Twitch", "https://www.twitch.tv/{u}"),
        Social("spotify", "Spotify", "https://open.spotify.com/user/{u}"),
        Social("medium", "Medium", "https://medium.com/@{u}"),
        Social("behance", "Behance", "https://www.behance.net/{u}")
    )

    val ALL: List<QrType> = listOf(
        QrType("Text", R.drawable.ic_text, "Any plain text or note.",
            listOf(FieldSpec("text", "Your text", FieldKind.MULTILINE, required = true))
        ) { m -> need(m, "text", "Enter some text") },

        QrType("Website", R.drawable.ic_link, "Opens a web page when scanned.",
            listOf(FieldSpec("url", "Website link  (example.com)", FieldKind.URL, required = true))
        ) { m -> withScheme(need(m, "url", "Enter a link")) },

        QrType("Wi-Fi", R.drawable.ic_wifi, "Lets people join your network by scanning.",
            listOf(
                FieldSpec("ssid", "Network name (SSID)", required = true),
                FieldSpec("pass", "Password", FieldKind.PASSWORD),
                FieldSpec("sec", "Security", FieldKind.CHOICE, options = listOf(
                    "WPA" to "WPA / WPA2 / WPA3", "WEP" to "WEP", "nopass" to "None"
                )),
                FieldSpec("hidden", "Hidden network", FieldKind.SWITCH)
            )
        ) { m ->
            val ssid = need(m, "ssid", "Enter the network name")
            val sec = m.v("sec").ifEmpty { "WPA" }
            val pass = m.v("pass")
            if (sec != "nopass" && pass.isEmpty()) throw FormError("pass", "Enter the password (or choose None)")
            buildString {
                append("WIFI:T:").append(sec).append(";S:").append(esc(ssid)).append(';')
                if (sec != "nopass") append("P:").append(esc(pass)).append(';')
                if (m.v("hidden") == "true") append("H:true;")
                append(';')
            }
        },

        QrType("Contact", R.drawable.ic_person, "Scan to save a contact (vCard).",
            listOf(
                FieldSpec("first", "First name"),
                FieldSpec("last", "Last name"),
                FieldSpec("phone", "Phone", FieldKind.PHONE),
                FieldSpec("email", "Email", FieldKind.EMAIL),
                FieldSpec("org", "Company"),
                FieldSpec("title", "Job title"),
                FieldSpec("addr", "Address", FieldKind.MULTILINE),
                FieldSpec("url", "Website", FieldKind.URL)
            )
        ) { m ->
            val first = m.v("first"); val last = m.v("last")
            if (first.isEmpty() && last.isEmpty()) throw FormError("first", "Enter a first or last name")
            buildString {
                append("BEGIN:VCARD\nVERSION:3.0\n")
                append("N:").append(vEsc(last)).append(';').append(vEsc(first)).append(";;;\n")
                append("FN:").append(vEsc("$first $last".trim())).append('\n')
                if (m.v("phone").isNotEmpty()) append("TEL;TYPE=CELL:").append(m.v("phone")).append('\n')
                if (m.v("email").isNotEmpty()) append("EMAIL:").append(m.v("email")).append('\n')
                if (m.v("org").isNotEmpty()) append("ORG:").append(vEsc(m.v("org"))).append('\n')
                if (m.v("title").isNotEmpty()) append("TITLE:").append(vEsc(m.v("title"))).append('\n')
                if (m.v("addr").isNotEmpty()) append("ADR:;;").append(vEsc(m.v("addr"))).append(";;;;\n")
                if (m.v("url").isNotEmpty()) append("URL:").append(m.v("url")).append('\n')
                append("END:VCARD")
            }
        },

        QrType("Email", R.drawable.ic_mail, "Opens a new email, ready to send.",
            listOf(
                FieldSpec("to", "Email address", FieldKind.EMAIL, required = true),
                FieldSpec("subject", "Subject"),
                FieldSpec("body", "Message", FieldKind.MULTILINE)
            )
        ) { m ->
            val to = need(m, "to", "Enter an email address")
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(to).matches())
                throw FormError("to", "This email address looks wrong")
            val q = listOfNotNull(
                m.v("subject").takeIf { it.isNotEmpty() }?.let { "subject=" + Uri.encode(it) },
                m.v("body").takeIf { it.isNotEmpty() }?.let { "body=" + Uri.encode(it) }
            ).joinToString("&")
            "mailto:$to" + if (q.isNotEmpty()) "?$q" else ""
        },

        QrType("Phone", R.drawable.ic_phone, "Dials a number when scanned.",
            listOf(FieldSpec("phone", "Phone number  (+8801…)", FieldKind.PHONE, required = true))
        ) { m -> "tel:" + need(m, "phone", "Enter a phone number").replace(" ", "") },

        QrType("SMS", R.drawable.ic_sms, "Starts a text message.",
            listOf(
                FieldSpec("phone", "Phone number", FieldKind.PHONE, required = true),
                FieldSpec("msg", "Message", FieldKind.MULTILINE)
            )
        ) { m -> "SMSTO:" + need(m, "phone", "Enter a phone number").replace(" ", "") + ":" + m.v("msg") },

        QrType("WhatsApp", R.drawable.ic_chat, "Opens a WhatsApp chat with a number.",
            listOf(
                FieldSpec("phone", "Number with country code  (8801…)", FieldKind.PHONE, required = true),
                FieldSpec("msg", "Message", FieldKind.MULTILINE)
            )
        ) { m ->
            val digits = need(m, "phone", "Enter a phone number").filter { it.isDigit() }
            if (digits.length < 8) throw FormError("phone", "Include the country code, e.g. 8801XXXXXXXXX")
            "https://wa.me/$digits" + m.v("msg").takeIf { it.isNotEmpty() }?.let { "?text=" + Uri.encode(it) }.orEmpty()
        },

        QrType("Location", R.drawable.ic_location, "Opens a place in Maps.",
            listOf(
                FieldSpec("lat", "Latitude  (23.8103)", FieldKind.DECIMAL, required = true),
                FieldSpec("lng", "Longitude  (90.4125)", FieldKind.DECIMAL, required = true)
            )
        ) { m ->
            val lat = need(m, "lat", "Enter the latitude").toDoubleOrNull()
            val lng = need(m, "lng", "Enter the longitude").toDoubleOrNull()
            if (lat == null || lat !in -90.0..90.0) throw FormError("lat", "Latitude must be between -90 and 90")
            if (lng == null || lng !in -180.0..180.0) throw FormError("lng", "Longitude must be between -180 and 180")
            "geo:$lat,$lng"
        },

        QrType("Event", R.drawable.ic_event, "Scan to add an event to the calendar.",
            listOf(
                FieldSpec("title", "Event title", required = true),
                FieldSpec("start", "Starts", FieldKind.DATETIME, required = true),
                FieldSpec("end", "Ends", FieldKind.DATETIME),
                FieldSpec("place", "Location"),
                FieldSpec("desc", "Description", FieldKind.MULTILINE)
            )
        ) { m ->
            val title = need(m, "title", "Enter the event title")
            val start = need(m, "start", "Pick a start time").toLong()
            val end = m.v("end").toLongOrNull()
            if (end != null && end < start) throw FormError("end", "End must be after the start")
            buildString {
                append("BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\n")
                append("SUMMARY:").append(vEsc(title)).append('\n')
                append("DTSTART:").append(icalDate(start)).append('\n')
                if (end != null) append("DTEND:").append(icalDate(end)).append('\n')
                if (m.v("place").isNotEmpty()) append("LOCATION:").append(vEsc(m.v("place"))).append('\n')
                if (m.v("desc").isNotEmpty()) append("DESCRIPTION:").append(vEsc(m.v("desc"))).append('\n')
                append("END:VEVENT\nEND:VCALENDAR")
            }
        },

        QrType("Crypto", R.drawable.ic_wallet, "Share a wallet address so people can pay you.",
            listOf(
                FieldSpec("coin", "Coin", FieldKind.CHOICE, options = COINS.map { it.id to it.label }),
                FieldSpec("address", "Wallet address", required = true),
                FieldSpec("amount", "Amount", FieldKind.DECIMAL)
            )
        ) { m ->
            val coin = COINS.firstOrNull { it.id == m.v("coin") } ?: COINS[0]
            var address = need(m, "address", "Enter the wallet address")
            if (coin.id == "bitcoincash") address = address.removePrefix("bitcoincash:")
            if (!coin.regex.matches(address)) {
                throw FormError("address", "This ${coin.label} address looks wrong")
            }
            val raw = m.v("amount").replace(',', '.')
            var query = ""
            if (raw.isNotEmpty()) {
                val amount = raw.toBigDecimalOrNull()
                if (amount == null || amount.signum() <= 0) throw FormError("amount", "Enter a valid amount")
                query = when (coin.id) {
                    // EIP-681 uses wei
                    "ethereum" -> "?value=" + amount.multiply(BigDecimal("1000000000000000000")).toBigInteger().toString()
                    "monero" -> "?tx_amount=" + amount.stripTrailingZeros().toPlainString()
                    else -> "?amount=" + amount.stripTrailingZeros().toPlainString()
                }
            }
            "${coin.id}:$address$query"
        },

        QrType("Social", R.drawable.ic_social, "Link straight to a social media profile.",
            listOf(
                FieldSpec("platform", "Platform", FieldKind.CHOICE,
                    options = SOCIALS.map { it.id to it.label }),
                FieldSpec("handle", "Username or profile link", required = true)
            )
        ) { m ->
            val raw = need(m, "handle", "Enter a username or profile link")
            if (raw.startsWith("http", true) || raw.startsWith("www.", true)) {
                withScheme(raw)
            } else {
                val u = raw.removePrefix("@")
                if (!Regex("[A-Za-z0-9._-]{1,100}").matches(u)) {
                    throw FormError("handle", "Use only letters, numbers, . _ and -")
                }
                val p = SOCIALS.firstOrNull { it.id == m.v("platform") } ?: SOCIALS[0]
                p.url.replace("{u}", u)
            }
        }
    )
}
