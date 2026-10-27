package com.qrix.scanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment

class HomeFragment : Fragment(R.layout.fragment_home) {

    // Photo picker (no storage permission needed). Falls back to the system
    // file picker automatically on devices without the photo picker.
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) ScanFlow.scanImage(requireActivity(), uri)
        }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val main = requireActivity() as MainActivity

        // Gentle staggered entrance + press feedback
        v.findViewById<LinearLayout>(R.id.homeContent).let { content ->
            for (i in 0 until content.childCount) Fx.enter(content.getChildAt(i), i * 35L)
        }
        listOf(R.id.btnScan, R.id.btnCreateQr, R.id.btnCreateBarcode, R.id.btnGallery).forEach {
            Fx.press(v.findViewById(it))
        }

        v.findViewById<View>(R.id.btnScan).setOnClickListener {
            startActivity(Intent(requireContext(), ScanActivity::class.java))
        }
        v.findViewById<View>(R.id.btnCreateQr).setOnClickListener {
            main.pendingCreateFormat = "QR Code"
            main.go(R.id.nav_create)
        }
        v.findViewById<View>(R.id.btnCreateBarcode).setOnClickListener {
            main.pendingCreateFormat = "Code 128"
            main.go(R.id.nav_create)
        }
        v.findViewById<View>(R.id.btnGallery).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        v.findViewById<View>(R.id.seeAllScans).setOnClickListener {
            main.pendingHistoryKind = Record.SCANNED
            main.go(R.id.nav_history)
        }
        v.findViewById<View>(R.id.seeAllGenerated).setOnClickListener {
            main.pendingHistoryKind = Record.GENERATED
            main.go(R.id.nav_history)
        }
    }

    override fun onResume() {
        super.onResume()
        val v = view ?: return
        val ctx = requireContext()
        fill(
            v.findViewById(R.id.recentList), v.findViewById(R.id.txtNoScans),
            HistoryStore.list(ctx, Record.SCANNED).take(3)
        )
        fill(
            v.findViewById(R.id.generatedList), v.findViewById(R.id.txtNoGenerated),
            HistoryStore.list(ctx, Record.GENERATED).take(3)
        )
    }

    private fun fill(container: LinearLayout, empty: View, items: List<Record>) {
        container.removeAllViews()
        empty.isVisible = items.isEmpty()
        items.forEach { r ->
            val row = layoutInflater.inflate(R.layout.item_record, container, false)
            RecordBinder.bind(row, r) { RecordBinder.open(requireContext(), r) }
            container.addView(row)
        }
    }
}
