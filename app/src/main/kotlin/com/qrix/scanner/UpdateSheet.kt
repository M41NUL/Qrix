/*
 * File   : UpdateSheet.kt
 * Owner  : MAINUL (CODEX-M41NUL)
 * GitHub : M41NUL
 * About  : Bottom popup shown when a newer version is available (current -> new + What's fixed)
 */
package com.qrix.scanner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object UpdateSheet {

    fun show(a: Activity, info: UpdateChecker.Info) {
        val dialog = BottomSheetDialog(a)
        val inflater = LayoutInflater.from(a)
        val v = inflater.inflate(R.layout.dialog_update, null)

        v.findViewById<TextView>(R.id.updCurrent).text = UpdateChecker.currentVersion(a)
        v.findViewById<TextView>(R.id.updNew).text = info.latestVersion

        val fixes = UpdateChecker.fixes(info.message)
        val title = v.findViewById<View>(R.id.updFixTitle)
        val list = v.findViewById<LinearLayout>(R.id.updFixList)
        if (fixes.isEmpty()) {
            title.visibility = View.GONE
            list.visibility = View.GONE
        } else {
            fixes.forEach { line ->
                val row = inflater.inflate(R.layout.item_fix, list, false)
                row.findViewById<TextView>(R.id.fixText).text = line
                list.addView(row)
            }
        }

        v.findViewById<View>(R.id.updLater).setOnClickListener {
            dialog.dismiss()
        }
        v.findViewById<View>(R.id.updUpdate).setOnClickListener {
            openLink(a, info.downloadLink)
            dialog.dismiss()
        }

        dialog.setContentView(v)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.show()
    }

    private fun openLink(a: Activity, url: String) {
        if (url.isEmpty()) return
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(a, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
    }
}
