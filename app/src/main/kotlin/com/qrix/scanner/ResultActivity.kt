package com.qrix.scanner

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultActivity : AppCompatActivity() {

    companion object {
        fun intent(c: Context, text: String, format: String, id: Long, kind: Int, time: Long) =
            Intent(c, ResultActivity::class.java)
                .putExtra("text", text)
                .putExtra("format", format)
                .putExtra("id", id)
                .putExtra("kind", kind)
                .putExtra("time", time)
    }

    private lateinit var btnAction: MaterialButton
    private lateinit var btnCopy: MaterialButton
    private lateinit var extraList: LinearLayout
    private lateinit var btnFav: ImageButton
    private var recId = -1L
    private var isFav = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        Bars.apply(this)

        val text = intent.getStringExtra("text") ?: ""
        val format = intent.getStringExtra("format") ?: "Code"
        val id = intent.getLongExtra("id", -1L)
        val kind = intent.getIntExtra("kind", Record.SCANNED)
        val time = intent.getLongExtra("time", System.currentTimeMillis())
        val t = text.trim()
        val type = CodeUtils.typeOf(text, format)

        btnAction = findViewById(R.id.btnAction)
        btnCopy = findViewById(R.id.btnCopy)
        extraList = findViewById(R.id.extraList)
        btnFav = findViewById(R.id.btnFav)

        findViewById<TextView>(R.id.txtType).text = type.uppercase()
        findViewById<ImageView>(R.id.imgType).setImageResource(CodeUtils.iconFor(type))
        findViewById<TextView>(R.id.txtText).text = text
        val date = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(time))
        findViewById<TextView>(R.id.txtMeta).text =
            (if (kind == Record.GENERATED) "Generated" else "Scanned") + " • $format • $date"

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        recId = id
        isFav = recId != -1L && HistoryStore.isFav(this, recId)
        renderFav()
        if (recId != -1L) showDelete()
        btnFav.setOnClickListener {
            if (recId == -1L) {
                // History is switched off: the code is stored now, because it is being saved.
                recId = HistoryStore.add(this, text, format, kind).id
                isFav = true
                showDelete()
            } else {
                isFav = !isFav
            }
            HistoryStore.setFav(this, recId, isFav)
            renderFav()
            Fx.pop(btnFav)
            CodeUtils.vibrate(this, strong = false)
            Toast.makeText(
                this, if (isFav) "Saved to favorites" else "Removed from favorites", Toast.LENGTH_SHORT
            ).show()
        }

        // Default secondary buttons: Copy + Share
        btnCopy.setOnClickListener { copy(text) }
        findViewById<View>(R.id.btnShare).setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    "Share"
                )
            )
        }

        listOf(btnAction, btnCopy, findViewById<View>(R.id.btnShare), btnFav).forEach { Fx.press(it) }
        Fx.enter(findViewById(R.id.resultCard))
        Fx.enter(findViewById(R.id.actionBar), 80L, 18f)

        // The main button does whatever is most useful for this kind of code
        when (type) {
            "Link" -> action("Open link", R.drawable.ic_open) {
                view(Uri.parse(if (t.startsWith("www.", true)) "https://$t" else t))
            }

            "Social" -> action("Open profile", R.drawable.ic_social) {
                view(Uri.parse(if (t.startsWith("www.", true)) "https://$t" else t))
            }

            "Crypto" -> {
                val c = CodeUtils.parseCrypto(t)
                showExtra("Coin" to c["coin"].orEmpty(), "Amount" to c["amount"].orEmpty())
                findViewById<TextView>(R.id.txtText).text = c["address"] ?: text
                action("Open wallet", R.drawable.ic_wallet) { view(Uri.parse(t)) }
                secondary("Copy address", R.drawable.ic_copy) {
                    copy(c["address"].orEmpty(), "Address copied")
                }
            }

            "Wi-Fi" -> {
                val w = CodeUtils.parseWifi(t)
                val ssid = w["S"].orEmpty()
                val pass = w["P"].orEmpty()
                val sec = w["T"].orEmpty()
                showExtra(
                    "Network" to ssid,
                    "Security" to when (sec.uppercase()) {
                        "", "NOPASS" -> "Open (no password)"
                        "SAE" -> "WPA3"
                        else -> sec.uppercase()
                    },
                    "Password" to pass,
                    "Hidden" to if (w["H"].equals("true", true)) "Yes" else ""
                )
                action("Connect to Wi-Fi", R.drawable.ic_wifi) { connectWifi(w) }
                if (pass.isNotEmpty()) {
                    secondary("Copy password", R.drawable.ic_copy) { copy(pass, "Password copied") }
                }
            }

            "Contact" -> {
                val c = CodeUtils.parseContact(t)
                showExtra(
                    "Name" to c["name"].orEmpty(), "Phone" to c["phone"].orEmpty(),
                    "Email" to c["email"].orEmpty(), "Company" to c["org"].orEmpty(),
                    "Title" to c["title"].orEmpty(), "Address" to c["addr"].orEmpty(),
                    "Website" to c["url"].orEmpty(), "Note" to c["note"].orEmpty()
                )
                findViewById<TextView>(R.id.txtText).text = c["name"] ?: text
                action("Save contact", R.drawable.ic_person_add) { saveContact(c) }
            }

            "Email" -> action("Send email", R.drawable.ic_mail) {
                safeStart(Intent(Intent.ACTION_SENDTO, Uri.parse(if (t.startsWith("mailto:", true)) t else "mailto:$t")))
            }

            "Phone" -> {
                action("Call", R.drawable.ic_phone) { safeStart(Intent(Intent.ACTION_DIAL, Uri.parse(t))) }
                secondary("Save contact", R.drawable.ic_person_add) {
                    saveContact(mapOf("phone" to t.substringAfter(':')))
                }
            }

            "SMS" -> {
                val (number, body) = CodeUtils.parseSms(t)
                showExtra("To" to number, "Message" to body)
                action("Send SMS", R.drawable.ic_sms) {
                    safeStart(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body)
                    )
                }
            }

            "Location" -> action("Open in Maps", R.drawable.ic_location) { view(Uri.parse(t)) }

            "Event" -> {
                val e = CodeUtils.parseEvent(t)
                val fmt = SimpleDateFormat("EEE, d MMM yyyy, h:mm a", Locale.getDefault())
                showExtra(
                    "Title" to e["title"].orEmpty(),
                    "Starts" to (e["start"]?.toLongOrNull()?.let { fmt.format(Date(it)) } ?: ""),
                    "Ends" to (e["end"]?.toLongOrNull()?.let { fmt.format(Date(it)) } ?: ""),
                    "Where" to e["location"].orEmpty(), "Details" to e["desc"].orEmpty()
                )
                findViewById<TextView>(R.id.txtText).text = e["title"] ?: text
                action("Add to calendar", R.drawable.ic_event) { addEvent(e) }
            }

            "Product" -> {
                action("Search product", R.drawable.ic_search) {
                    view(Uri.parse("https://www.google.com/search?q=" + Uri.encode(t)))
                }
            }

            // Plain text → copy it
            else -> {
                action("Copy text", R.drawable.ic_copy) { copy(text) }
                secondary("Search", R.drawable.ic_search) {
                    view(Uri.parse("https://www.google.com/search?q=" + Uri.encode(t)))
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun action(label: String, icon: Int, onClick: () -> Unit) {
        btnAction.text = label
        btnAction.setIconResource(icon)
        btnAction.setOnClickListener { onClick() }
    }

    /** Replaces the left secondary button (default: Copy). */
    private fun secondary(label: String, icon: Int, onClick: () -> Unit) {
        btnCopy.text = label
        btnCopy.setIconResource(icon)
        btnCopy.setOnClickListener { onClick() }
    }

    private fun showExtra(vararg rows: Pair<String, String>) {
        val shown = rows.filter { it.second.isNotBlank() }
        if (shown.isEmpty()) return
        extraList.removeAllViews()
        extraList.visibility = View.VISIBLE
        shown.forEach { (label, value) ->
            val row = layoutInflater.inflate(R.layout.item_detail, extraList, false)
            row.findViewById<TextView>(R.id.detLabel).text = label.uppercase()
            row.findViewById<TextView>(R.id.detValue).text = value
            extraList.addView(row)
        }
    }

    private fun renderFav() {
        btnFav.setImageResource(if (isFav) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
        val color = ContextCompat.getColor(this, if (isFav) R.color.qx_gold else R.color.qx_text2)
        ImageViewCompat.setImageTintList(btnFav, ColorStateList.valueOf(color))
    }

    private fun showDelete() {
        findViewById<View>(R.id.btnDelete).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                HistoryStore.delete(this@ResultActivity, recId)
                finish()
            }
        }
    }

    private fun view(uri: Uri) = safeStart(Intent(Intent.ACTION_VIEW, uri))

    private fun safeStart(i: Intent) {
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(text: String, message: String = "Copied") {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Qrix", text))
        CodeUtils.vibrate(this, strong = false)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun saveContact(c: Map<String, String>) {
        val i = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            c["name"]?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            c["phone"]?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            c["email"]?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            c["org"]?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            c["title"]?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
            c["addr"]?.let { putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
            c["note"]?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
        }
        safeStart(i)
    }

    private fun addEvent(e: Map<String, String>) {
        val i = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            e["title"]?.let { putExtra(CalendarContract.Events.TITLE, it) }
            e["location"]?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            e["desc"]?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            e["start"]?.toLongOrNull()?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            e["end"]?.toLongOrNull()?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        }
        safeStart(i)
    }

    // ------------------------------------------------------------------ Wi-Fi

    /**
     * Android 11+: system "Save network?" dialog (no extra permission).
     * Android 10: network suggestion. Android 8–9: classic addNetwork().
     * If anything fails: copy the password and open Wi-Fi settings.
     */
    private fun connectWifi(w: Map<String, String>) {
        val ssid = w["S"].orEmpty()
        val pass = w["P"].orEmpty()
        val sec = w["T"].orEmpty().uppercase()
        val hidden = w["H"].equals("true", true)
        if (ssid.isEmpty()) {
            Toast.makeText(this, "This Wi-Fi code has no network name", Toast.LENGTH_SHORT).show()
            return
        }
        val open = sec == "NOPASS" || (sec.isEmpty() && pass.isEmpty())

        try {
            when {
                sec == "WEP" && Build.VERSION.SDK_INT >= 29 -> manualWifi(pass)
                Build.VERSION.SDK_INT >= 30 -> {
                    val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                        .putParcelableArrayListExtra(
                            Settings.EXTRA_WIFI_NETWORK_LIST,
                            arrayListOf(suggestion(ssid, pass, sec, hidden, open))
                        )
                    startActivity(intent)
                }
                Build.VERSION.SDK_INT == 29 -> {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val status = wm.addNetworkSuggestions(listOf(suggestion(ssid, pass, sec, hidden, open)))
                    if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
                        status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
                    ) {
                        Toast.makeText(
                            this, "Network added. Allow it in the notification to connect.",
                            Toast.LENGTH_LONG
                        ).show()
                        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    } else manualWifi(pass)
                }
                else -> legacyConnect(ssid, pass, sec, hidden, open)
            }
        } catch (e: Exception) {
            manualWifi(pass)
        }
    }

    @androidx.annotation.RequiresApi(29)
    private fun suggestion(
        ssid: String, pass: String, sec: String, hidden: Boolean, open: Boolean
    ): WifiNetworkSuggestion {
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid).setIsHiddenSsid(hidden)
        if (!open) {
            if (sec == "SAE" && Build.VERSION.SDK_INT >= 29) b.setWpa3Passphrase(pass)
            else b.setWpa2Passphrase(pass)
        }
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun legacyConnect(ssid: String, pass: String, sec: String, hidden: Boolean, open: Boolean) {
        val cfg = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            hiddenSSID = hidden
            when {
                open -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                sec == "WEP" -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    wepKeys[0] = "\"$pass\""
                    wepTxKeyIndex = 0
                }
                else -> preSharedKey = "\"$pass\""
            }
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val netId = wm.addNetwork(cfg)
        if (netId == -1) {
            manualWifi(pass)
            return
        }
        wm.enableNetwork(netId, true)
        wm.reconnect()
        Toast.makeText(this, "Connecting to $ssid…", Toast.LENGTH_LONG).show()
    }

    /** Last resort: give the user the password and the Wi-Fi settings screen. */
    private fun manualWifi(pass: String) {
        if (pass.isNotEmpty()) copy(pass, "Password copied. Pick the network in Wi-Fi settings.")
        safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Bars.apply(this)
    }
}
