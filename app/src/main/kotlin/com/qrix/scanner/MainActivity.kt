package com.qrix.scanner

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    /** Set by Home before jumping to another tab. */
    var pendingCreateFormat: String? = null
    var pendingHistoryKind: Int = -1 // -1 = all

    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = go(R.id.nav_home)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { screen ->
            screen.view.animate()
                .alpha(0f)
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(220)
                .withEndAction { screen.remove() }
                .start()
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Bars.apply(this)

        onBackPressedDispatcher.addCallback(this, backToHome)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener {
            backToHome.isEnabled = it.itemId != R.id.nav_home
            show(it.itemId)
            true
        }
        if (savedInstanceState == null) {
            show(R.id.nav_home)
            autoCheckUpdate()
        }
    }

    /** On app open: show the update popup if a newer version is out (every launch until updated). */
    private fun autoCheckUpdate() {
        UpdateChecker.fetch { info, _ ->
            if (info == null || isFinishing || isDestroyed) return@fetch
            val newer = UpdateChecker.isNewer(info.latestVersion, UpdateChecker.currentVersion(this))
            if (newer) UpdateSheet.show(this, info)
        }
    }

    override fun onResume() {
        super.onResume()
        Bars.apply(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Bars.apply(this)
    }

    private fun show(id: Int) {
        val f: Fragment = when (id) {
            R.id.nav_history -> HistoryFragment()
            R.id.nav_create -> CreateFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.container, f)
            .commit()
    }

    fun go(itemId: Int) {
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = itemId
    }
}
