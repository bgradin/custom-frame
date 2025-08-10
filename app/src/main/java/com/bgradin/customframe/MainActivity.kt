package com.bgradin.customframe

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import com.bgradin.customframe.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                131 -> { // Power
                    // Typically handled by system; ignore
                    return true
                }
                132 -> { // Slideshow
                    findNavController(R.id.nav_host_fragment).navigate(R.id.slideshowFragment)
                    return true
                }
                134 -> { // Settings
                    findNavController(R.id.nav_host_fragment).navigate(R.id.settingsFragment)
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