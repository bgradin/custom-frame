package com.bgradin.customframe

import com.bgradin.customframe.diagnostics.AppLog

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bgradin.customframe.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sleeping = false
    private var focusBeforeSleep: android.view.View? = null
    private val scheduleTick = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    private val clockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            com.bgradin.customframe.power.FramePower.reset(context)
            handler.removeCallbacks(scheduleTick)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                scheduleTick.run()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.event("activity_create", "restored=${savedInstanceState != null}")
        // Honor a remote wake before evaluating the startup schedule.
        com.bgradin.customframe.power.FramePower.onForeground(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        // Apply sleep visibility and brightness before attaching any slideshow views.
        updateDisplay()
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.content.ContextCompat.registerReceiver(this, clockReceiver,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_TIME_CHANGED)
                addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.foreground(true)
        AppLog.event("activity_resume")
        com.bgradin.customframe.power.FramePower.onForeground(this)
        handler.removeCallbacks(scheduleTick)
        scheduleTick.run()
    }

    override fun onPause() {
        handler.removeCallbacks(scheduleTick)
        AppLog.foreground(false)
        AppLog.event("activity_pause")
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(scheduleTick)
        unregisterReceiver(clockReceiver)
        super.onDestroy()
    }

    fun wakeSettingsChanged() {
        com.bgradin.customframe.power.FramePower.reset(this)
        updateDisplay()
    }

    private fun updateDisplay() {
        val wasSleeping = sleeping
        sleeping = com.bgradin.customframe.power.FramePower.apply(this)
        if (sleeping != wasSleeping) AppLog.event("display", "sleeping=$sleeping")
        if (sleeping && !wasSleeping) focusBeforeSleep = currentFocus
        binding.sleepOverlay.visibility = if (sleeping) android.view.View.VISIBLE else android.view.View.GONE
        binding.navHostFragment.visibility = if (sleeping) android.view.View.INVISIBLE else android.view.View.VISIBLE
        if (wasSleeping && !sleeping) {
            focusBeforeSleep?.requestFocus()
            focusBeforeSleep = null
        }
        window.attributes = window.attributes.apply {
            screenBrightness = if (sleeping) 0f else -1f
            buttonBrightness = if (sleeping) 0f else -1f
        }
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        androidx.core.view.WindowCompat.getInsetsController(window, binding.root).apply {
            if (sleeping) hide(bars) else show(bars)
        }
    }

    private fun togglePower() {
        val lastWake = com.bgradin.customframe.power.FramePower.lastRemoteWakeAt
        if (lastWake != Long.MIN_VALUE && android.os.SystemClock.elapsedRealtime() - lastWake < 1500L) return
        com.bgradin.customframe.power.FramePower.toggle(this)
        updateDisplay()
    }

    private fun navigateTo(destination: Int) {
        AppLog.event("navigation_request", "destination=$destination ${AppLog.memory()}")
        val host = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        if (supportFragmentManager.isStateSaved || host.navController.currentDestination?.id == destination) return
        host.navController.navigate(destination, null, navOptions {
            launchSingleTop = true
            popUpTo(R.id.slideshowFragment) { inclusive = destination == R.id.slideshowFragment }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == 131 || event.keyCode == KeyEvent.KEYCODE_POWER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) togglePower()
            return true
        }
        if (sleeping) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                132 -> { // Slideshow
                    navigateTo(R.id.slideshowFragment)
                    return true
                }
                134 -> { // Settings
                    navigateTo(R.id.settingsFragment)
                    return true
                }
                4 -> { // Back
                    onBackPressedDispatcher.onBackPressed()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
