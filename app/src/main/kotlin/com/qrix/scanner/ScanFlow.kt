package com.qrix.scanner

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** Shared logic for camera scans and gallery scans. */
object ScanFlow {

    val client: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }

    /** Saves to history (if enabled), buzzes, and opens the result screen. */
    fun open(ctx: Context, text: String, format: String) {
        CodeUtils.vibrate(ctx)
        val rec = if (Prefs.saveHistory(ctx)) HistoryStore.add(ctx, text, format, Record.SCANNED) else null
        ctx.startActivity(
            ResultActivity.intent(
                ctx, text, format,
                rec?.id ?: -1L, Record.SCANNED, rec?.time ?: System.currentTimeMillis()
            )
        )
    }

    /**
     * Decodes QR codes AND barcodes from an image picked in the gallery.
     * Tries the original image first, then a down-scaled copy (big photos).
     * If the image has several different codes the user picks one.
     */
    fun scanImage(activity: Activity, uri: Uri, onDone: (Boolean) -> Unit = {}) {
        fun fail() {
            Toast.makeText(activity, "No QR code or barcode found in this image", Toast.LENGTH_LONG).show()
            onDone(false)
        }

        fun fallback() {
            val bmp = decodeScaled(activity, uri)
            if (bmp == null) {
                fail()
                return
            }
            client.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { if (!deliver(activity, it, onDone)) fail() }
                .addOnFailureListener { fail() }
        }

        val first = try {
            InputImage.fromFilePath(activity, uri)
        } catch (e: Exception) {
            null
        }
        if (first == null) {
            fallback()
            return
        }
        client.process(first)
            .addOnSuccessListener { if (!deliver(activity, it, onDone)) fallback() }
            .addOnFailureListener { fallback() }
    }

    /** Opens the single code found, or lets the user choose when there are several. */
    private fun deliver(activity: Activity, list: List<Barcode>?, onDone: (Boolean) -> Unit): Boolean {
        val codes = list.orEmpty()
            .filter { !it.rawValue.isNullOrEmpty() }
            .distinctBy { it.rawValue }
        when {
            codes.isEmpty() -> return false
            codes.size == 1 -> {
                open(activity, codes[0].rawValue!!, CodeUtils.formatName(codes[0].format))
                onDone(true)
            }
            else -> {
                val labels = codes.map {
                    val v = it.rawValue!!.replace("\n", " ")
                    CodeUtils.formatName(it.format) + " • " + (if (v.length > 48) v.take(48) + "…" else v)
                }.toTypedArray()
                MaterialAlertDialogBuilder(activity)
                    .setTitle("${codes.size} codes found")
                    .setItems(labels) { _, i ->
                        open(activity, codes[i].rawValue!!, CodeUtils.formatName(codes[i].format))
                        onDone(true)
                    }
                    .setOnCancelListener { onDone(false) }
                    .show()
            }
        }
        return true
    }

    private fun decodeScaled(ctx: Context, uri: Uri): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Throwable) {
            null
        }
    }
}
