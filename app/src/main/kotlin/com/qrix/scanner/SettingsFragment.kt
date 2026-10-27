package com.qrix.scanner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val themeNames = arrayOf("System default", "Light", "Dark")

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()

        // Theme
        v.findViewById<View>(R.id.rowTheme).setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Theme")
                .setSingleChoiceItems(themeNames, Prefs.theme(ctx)) { dialog, which ->
                    dialog.dismiss()
                    Prefs.setTheme(ctx, which) // recreates the activity if the mode changed
                }
                .show()
        }

        // Switches (tap anywhere on the row)
        val swVibrate = v.findViewById<MaterialSwitch>(R.id.switchVibrate)
        val swSave = v.findViewById<MaterialSwitch>(R.id.switchSave)
        val swZoom = v.findViewById<MaterialSwitch>(R.id.switchAutoZoom)
        swZoom.isChecked = Prefs.autoZoom(ctx)
        swZoom.setOnCheckedChangeListener { _, on -> Prefs.setAutoZoom(ctx, on) }
        v.findViewById<View>(R.id.rowAutoZoom).setOnClickListener { swZoom.toggle() }
        val swFlash = v.findViewById<MaterialSwitch>(R.id.switchAutoFlash)
        swFlash.isChecked = Prefs.autoFlash(ctx)
        swFlash.setOnCheckedChangeListener { _, on -> Prefs.setAutoFlash(ctx, on) }
        v.findViewById<View>(R.id.rowAutoFlash).setOnClickListener { swFlash.toggle() }
        swVibrate.isChecked = Prefs.vibrate(ctx)
        swSave.isChecked = Prefs.saveHistory(ctx)
        swVibrate.setOnCheckedChangeListener { _, on -> Prefs.setVibrate(ctx, on) }
        swSave.setOnCheckedChangeListener { _, on -> Prefs.setSaveHistory(ctx, on) }
        v.findViewById<View>(R.id.rowVibrate).setOnClickListener { swVibrate.toggle() }
        v.findViewById<View>(R.id.rowSave).setOnClickListener { swSave.toggle() }

        // Clear data
        v.findViewById<View>(R.id.rowClearScans).setOnClickListener {
            confirm("Clear scan history?", Record.SCANNED)
        }
        v.findViewById<View>(R.id.rowClearGenerated).setOnClickListener {
            confirm("Clear generated codes?", Record.GENERATED)
        }

        // Version
        @Suppress("DEPRECATION")
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        val versionText = "Version ${version ?: "1.0.0"}"
        v.findViewById<TextView>(R.id.txtVersion).text = versionText
        v.findViewById<TextView>(R.id.txtVersionSub).text = versionText

        // Update check (Firebase Realtime Database)
        v.findViewById<View>(R.id.btnCheckUpdate).setOnClickListener { checkForUpdates() }

        // Developer links
        v.findViewById<View>(R.id.btnFacebook).setOnClickListener { openUrl(URL_FACEBOOK) }
        v.findViewById<View>(R.id.btnX).setOnClickListener { openUrl(URL_X) }
    }

    private fun checkForUpdates() {
        val btn = view?.findViewById<TextView>(R.id.btnCheckUpdate) ?: return
        val ctx = requireContext()
        btn.isEnabled = false
        btn.text = "\u2026"
        UpdateChecker.fetch { info, error ->
            if (!isAdded) return@fetch
            btn.isEnabled = true
            btn.text = "Check"
            when {
                info == null ->
                    Toast.makeText(ctx, error ?: "Couldn't check for updates", Toast.LENGTH_SHORT).show()
                UpdateChecker.isNewer(info.latestVersion, UpdateChecker.currentVersion(ctx)) -> UpdateSheet.show(requireActivity(), info)
                else ->
                    Toast.makeText(ctx, "No update available. You're on the latest version (${UpdateChecker.currentVersion(ctx)})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val URL_FACEBOOK = "https://www.facebook.com/MainulsSandbox"
        const val URL_X = "https://x.com/MainulsSandbox"
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<TextView>(R.id.txtTheme)?.text = themeNames[Prefs.theme(requireContext())]
    }

    private fun confirm(title: String, kind: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage("This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                HistoryStore.clear(requireContext(), kind)
                Toast.makeText(requireContext(), "Cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
