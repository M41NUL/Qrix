package com.qrix.scanner

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Create screen.  Two modes:
 *  - QR Code: pick a type (Text, Website, Wi-Fi, Contact, …) and fill its own form
 *  - Barcode: pick a barcode type (Code 128, EAN-13, UPC-A, …) and enter the value
 */
class CreateFragment : Fragment(R.layout.fragment_create) {

    private var bitmap: Bitmap? = null
    private var qrMode = true
    private var qrType: QrType = QrTypes.ALL[0]
    private var barType: CodeUtils.BarcodeType = CodeUtils.BARCODE_TYPES[0]

    // QR style (colours + optional logo)
    private var fgColor = Color.BLACK
    private var bgColor = Color.WHITE
    private var logo: Bitmap? = null

    private val getters = LinkedHashMap<String, () -> String>()
    private val errorViews = HashMap<String, TextInputLayout>()

    private lateinit var root: View
    private lateinit var scroll: ScrollView
    private lateinit var grid: GridLayout
    private lateinit var form: LinearLayout
    private lateinit var txtDesc: TextView
    private lateinit var lblTypes: TextView
    private lateinit var styleCard: View

    private val storagePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val b = bitmap
            if (ok && b != null) save(b) else toast("Storage permission denied")
        }

    private val pickLogo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) setLogo(uri)
        }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        root = v
        val main = requireActivity() as MainActivity
        scroll = v.findViewById(R.id.scroll)
        grid = v.findViewById(R.id.typeGrid)
        form = v.findViewById(R.id.formContainer)
        txtDesc = v.findViewById(R.id.txtTypeDesc)
        lblTypes = v.findViewById(R.id.lblTypes)
        styleCard = v.findViewById(R.id.styleCard)

        val want = main.pendingCreateFormat
        main.pendingCreateFormat = null
        qrMode = want == null || want == "QR Code"
        CodeUtils.BARCODE_TYPES.firstOrNull { it.name == want }?.let { barType = it }

        val toggle = v.findViewById<MaterialButtonToggleGroup>(R.id.modeToggle)
        toggle.check(if (qrMode) R.id.modeQr else R.id.modeBarcode)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                qrMode = id == R.id.modeQr
                renderMode()
            }
        }

        v.findViewById<View>(R.id.btnGenerate).setOnClickListener { generate() }
        v.findViewById<View>(R.id.btnLogo).setOnClickListener {
            pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        v.findViewById<View>(R.id.btnLogo).apply {
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        v.findViewById<View>(R.id.btnLogoClear).setOnClickListener {
            logo = null
            renderLogo()
        }
        listOf(R.id.btnGenerate, R.id.btnSave, R.id.btnShare, R.id.btnLogo).forEach {
            Fx.press(v.findViewById(it))
        }
        renderSwatches()
        renderLogo()
        v.findViewById<View>(R.id.btnSave).setOnClickListener { bitmap?.let { save(it) } }
        v.findViewById<View>(R.id.btnShare).setOnClickListener {
            bitmap?.let { CodeUtils.shareBitmap(requireContext(), it) }
        }
        renderMode()
    }

    // ------------------------------------------------------------ type picker

    private fun renderMode() {
        hidePreview()
        lblTypes.text = if (qrMode) "QR code type" else "Barcode type"
        styleCard.isVisible = qrMode
        if (qrMode) renderQrGrid() else renderBarcodeGrid()
        val current = if (qrMode) QrTypes.ALL.indexOf(qrType) else CodeUtils.BARCODE_TYPES.indexOf(barType)
        selectType(current.coerceAtLeast(0))
    }

    private fun renderQrGrid() =
        renderTiles(QrTypes.ALL.map { it.name to it.icon })

    /** Barcode types use the same tile grid as QR types. */
    private fun renderBarcodeGrid() =
        renderTiles(CodeUtils.BARCODE_TYPES.map {
            it.name to (if (it.twoD) R.drawable.ic_qr_code else R.drawable.ic_barcode)
        })

    private fun renderTiles(items: List<Pair<String, Int>>) {
        grid.removeAllViews()
        val m = (4 * resources.displayMetrics.density).toInt()
        items.forEachIndexed { i, (name, icon) ->
            val tile = layoutInflater.inflate(R.layout.item_type_tile, grid, false)
            tile.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
            tile.findViewById<TextView>(R.id.tileName).text = name
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL),
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f)
            )
            lp.width = 0
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.setMargins(m, m, m, m)
            tile.layoutParams = lp
            tile.setOnClickListener { selectType(i) }
            Fx.press(tile)
            grid.addView(tile)
        }
    }

    private fun selectType(index: Int) {
        if (qrMode) qrType = QrTypes.ALL[index] else barType = CodeUtils.BARCODE_TYPES[index]
        for (i in 0 until grid.childCount) grid.getChildAt(i).isSelected = i == index
        txtDesc.text = if (qrMode) qrType.description
        else "${barType.description}\nExample: ${barType.example}"
        hidePreview()
        buildForm()
    }

    // ---------------------------------------------------------- QR style

    private val fgPalette = intArrayOf(
        0xFF000000.toInt(), 0xFF4F46E5.toInt(), 0xFF1E3A8A.toInt(), 0xFF0F766E.toInt(),
        0xFF15803D.toInt(), 0xFFB91C1C.toInt(), 0xFF7E22CE.toInt(), 0xFFBE185D.toInt()
    )
    private val bgPalette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFEF3C7.toInt(), 0xFFDCFCE7.toInt(), 0xFFDBEAFE.toInt(),
        0xFFFCE7F3.toInt(), 0xFFE0E7FF.toInt(), 0xFFF3E8FF.toInt()
    )

    private fun renderSwatches() {
        swatches(root.findViewById(R.id.swFg), fgPalette, fgColor) { fgColor = it; hidePreview() }
        swatches(root.findViewById(R.id.swBg), bgPalette, bgColor) { bgColor = it; hidePreview() }
    }

    /** Small round swatches in one row; the selected one gets a ring. */
    private fun swatches(box: LinearLayout, colors: IntArray, selected: Int, onPick: (Int) -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val accent = ContextCompat.getColor(ctx, R.color.qx_accent)
        val outline = ContextCompat.getColor(ctx, R.color.qx_outline)
        box.removeAllViews()
        colors.forEach { c ->
            val on = c == selected
            fun oval(fill: Int, stroke: Int, strokePx: Int) = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                if (strokePx > 0) setStroke(strokePx, stroke)
            }
            val dot = View(ctx)
            dot.background = if (on) {
                LayerDrawable(arrayOf(
                    oval(Color.TRANSPARENT, accent, (2 * dp).toInt()),
                    oval(c, outline, (1 * dp).toInt())
                )).also { it.setLayerInset(1, (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt()) }
            } else {
                oval(c, outline, (1 * dp).toInt())
            }
            dot.layoutParams = LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt())
                .apply { marginEnd = (4 * dp).toInt() }
            dot.setOnClickListener {
                onPick(c)
                swatches(box, colors, c, onPick)
            }
            Fx.press(dot, 0.9f)
            box.addView(dot)
        }
    }

    private fun setLogo(uri: Uri) {
        val bmp = try {
            val cr = requireContext().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Throwable) {
            null
        }
        if (bmp == null) {
            toast("Couldn't load this image")
            return
        }
        logo = bmp
        renderLogo()
    }

    private fun renderLogo() {
        val btn = root.findViewById<ImageView>(R.id.btnLogo)
        val clear = root.findViewById<View>(R.id.btnLogoClear)
        val has = logo != null
        clear.isVisible = has
        if (has) {
            btn.imageTintList = null
            btn.setPadding(0, 0, 0, 0)
            btn.setImageBitmap(logo)
        } else {
            val pad = (10 * resources.displayMetrics.density).toInt()
            btn.setPadding(pad, pad, pad, pad)
            btn.setImageResource(R.drawable.ic_image)
            btn.imageTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.qx_accent))
        }
        btn.contentDescription = if (has) "Change logo" else "Add logo"
        hidePreview()
    }

    // ------------------------------------------------------------------- form

    private fun buildForm() {
        form.removeAllViews()
        getters.clear()
        errorViews.clear()
        val fields = if (qrMode) qrType.fields else listOf(
            FieldSpec(
                "value", barType.hint,
                when {
                    barType.numeric -> FieldKind.NUMBER
                    barType.uppercase -> FieldKind.CAPS
                    else -> FieldKind.TEXT
                },
                required = true
            )
        )
        fields.forEach { addField(it) }
    }

    private fun addField(f: FieldSpec) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        when (f.kind) {
            FieldKind.CHOICE -> {
                form.addView(TextView(ctx).apply {
                    text = f.hint
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(ctx, R.color.qx_text2))
                    setPadding((4 * dp).toInt(), (16 * dp).toInt(), 0, (4 * dp).toInt())
                })
                val group = ChipGroup(ctx).apply {
                    isSingleSelection = true
                    isSelectionRequired = true
                    isSingleLine = true
                }
                var selected = f.options.first().first
                f.options.forEachIndexed { i, (value, label) ->
                    val chip = layoutInflater.inflate(R.layout.chip_filter, group, false) as Chip
                    chip.id = View.generateViewId()
                    chip.text = label
                    chip.setOnCheckedChangeListener { _, on -> if (on) selected = value }
                    group.addView(chip)
                    if (i == 0) chip.isChecked = true
                }
                form.addView(HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    clipToPadding = false
                    addView(group)
                })
                getters[f.key] = { selected }
            }

            FieldKind.SWITCH -> {
                val sw = MaterialSwitch(ctx).apply {
                    text = f.hint
                    setTextColor(ContextCompat.getColor(ctx, R.color.qx_text))
                    setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
                }
                form.addView(sw)
                getters[f.key] = { if (sw.isChecked) "true" else "" }
            }

            FieldKind.DATETIME -> {
                val (layout, input) = inflateField(f)
                var millis: Long? = null
                input.isFocusable = false
                input.isCursorVisible = false
                input.isClickable = true
                val fmt = SimpleDateFormat("EEE, d MMM yyyy  •  h:mm a", Locale.getDefault())
                val click = View.OnClickListener {
                    pickDateTime { picked ->
                        millis = picked
                        input.setText(fmt.format(Date(picked)))
                        layout.error = null
                    }
                }
                input.setOnClickListener(click)
                layout.endIconMode = TextInputLayout.END_ICON_CUSTOM
                layout.setEndIconDrawable(R.drawable.ic_event)
                layout.setEndIconOnClickListener(click)
                getters[f.key] = { millis?.toString().orEmpty() }
            }

            else -> {
                val (_, input) = inflateField(f)
                input.inputType = f.inputType()
                if (f.kind == FieldKind.MULTILINE) {
                    input.minLines = 2
                    input.gravity = android.view.Gravity.TOP
                }
                getters[f.key] = { input.text?.toString()?.trim().orEmpty() }
            }
        }
    }

    private fun inflateField(f: FieldSpec): Pair<TextInputLayout, TextInputEditText> {
        val layout = layoutInflater.inflate(R.layout.item_field, form, false) as TextInputLayout
        layout.hint = if (f.required) f.hint else "${f.hint} (optional)"
        val input = layout.findViewById<TextInputEditText>(R.id.fieldInput)
        form.addView(layout)
        errorViews[f.key] = layout
        return layout to input
    }

    private fun pickDateTime(onPicked: (Long) -> Unit) {
        val ctx = requireContext()
        val cal = Calendar.getInstance()
        DatePickerDialog(ctx, { _, y, mo, d ->
            TimePickerDialog(ctx, { _, h, mi ->
                cal.set(y, mo, d, h, mi, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked(cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(ctx)).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // --------------------------------------------------------------- generate

    private fun generate() {
        val ctx = requireContext()
        errorViews.values.forEach { it.error = null }
        val values = getters.mapValues { it.value() }
        val name = if (qrMode) "QR Code" else barType.name

        val text = try {
            if (qrMode) qrType.build(values) else checkBarcode(values["value"].orEmpty())
        } catch (e: FormError) {
            showError(e.key, e.message ?: "Please check this field")
            return
        }

        if (qrMode && ColorUtils.calculateContrast(fgColor, bgColor) < 3.0) {
            toast("Pick a darker QR color or a lighter background")
            return
        }

        val bmp = try {
            if (qrMode) CodeUtils.generate(text, name, 900, fgColor, bgColor, logo)
            else CodeUtils.generate(text, name)
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.length < 60 }
                ?: if (qrMode) "Too much data for a QR code" else "This value can't be encoded as $name"
            showError(if (qrMode) null else "value", msg)
            return
        }

        bitmap = bmp
        root.findViewById<ImageView>(R.id.preview).setImageBitmap(bmp)
        root.findViewById<TextView>(R.id.txtPreviewInfo).text =
            if (qrMode) "${qrType.name} • QR Code" else name
        val card = root.findViewById<View>(R.id.previewCard)
        card.isVisible = true
        Fx.enter(card, 0L, 18f)
        CodeUtils.vibrate(ctx)
        HistoryStore.add(ctx, text, name, Record.GENERATED)
        ctx.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(root.windowToken, 0)
        scroll.post { scroll.smoothScrollTo(0, card.top - 16) }
    }

    private fun checkBarcode(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) throw FormError("value", "Enter something first")
        if (barType.uppercase) t = t.uppercase()
        val p = barType.pattern
        if (p != null && !p.matches(t)) throw FormError("value", barType.patternError ?: "Invalid value")
        return t
    }

    private fun showError(key: String?, msg: String) {
        val target = key?.let { errorViews[it] } ?: errorViews.values.firstOrNull()
        if (target != null) {
            target.error = msg
            target.requestFocus()
            scroll.post { scroll.smoothScrollTo(0, (form.top + target.top - 120).coerceAtLeast(0)) }
        } else {
            toast(msg)
        }
    }

    private fun hidePreview() {
        bitmap = null
        view?.findViewById<View>(R.id.previewCard)?.isVisible = false
    }

    // ------------------------------------------------------------------- save

    private fun save(bmp: Bitmap) {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val ok = CodeUtils.saveToGallery(ctx, bmp)
        if (ok) CodeUtils.vibrate(ctx, strong = false)
        toast(if (ok) "Saved to Pictures/Qrix" else "Couldn't save image")
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
