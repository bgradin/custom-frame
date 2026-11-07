package com.bgradin.customframe.ui

import com.bgradin.customframe.diagnostics.AppLog

import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import jp.wasabeef.glide.transformations.BlurTransformation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.bgradin.customframe.data.AppDb
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bgradin.customframe.R
import com.bgradin.customframe.data.Prefs
import com.bgradin.customframe.databinding.FragmentSlideshowBinding
import com.bgradin.customframe.work.PhotoSyncWorker
import java.io.File

class SlideshowFragment : Fragment() {
    private val TAG = "SlideshowFragment"

    private var _binding: FragmentSlideshowBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private val nextRunnable = Runnable { next() }
    private var isPlaying = true
    private var index = 0
    private var displayedFront = false
    private var loadGeneration = 0
    private var isLoading = false
    private lateinit var playlist: List<File>
    private var lastSeenSyncAt: Long = 0L
    private val prefs by lazy { Prefs(requireContext().applicationContext) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSlideshowBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The initial playlist already includes the last completed sync.
        lastSeenSyncAt = prefs.lastSyncAt
        val wm = WorkManager.getInstance(requireContext())
        wm.getWorkInfosByTagLiveData(PhotoSyncWorker.WORK_TAG)
            .observe(viewLifecycleOwner) { infos ->
                val syncing = infos.any { it.state == WorkInfo.State.RUNNING }
                binding.syncBadge.visibility = if (syncing) View.VISIBLE else View.GONE

                // When sync finishes AND the worker bumped lastSyncAt, reload & restart
                val now = prefs.lastSyncAt
                if (!syncing && now > lastSeenSyncAt) {
                    lastSeenSyncAt = now
                    playlist = loadPlaylist()
                    index = 0
                    showCurrent()
                }
            }

        playlist = loadPlaylist()
        showCurrent()

        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        binding.root.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    isPlaying = !isPlaying
                    if (isPlaying) scheduleNext() else handler.removeCallbacks(nextRunnable)
                    binding.status.text = getString(R.string.slideshow_paused)
                    binding.status.visibility = if (isPlaying) View.GONE else View.VISIBLE
                    true
                }
                21 -> { prev(); true }
                22 -> { next(); true }
                else -> false
            }
        }
    }

    private fun loadPlaylist(): List<File> {
        val dir = File(requireContext().filesDir, "photos")
        Log.i(TAG, "Loading slideshow playlist in ${dir.absolutePath}...")
        dir.mkdirs()
        val playlist = dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg","jpeg","png") }
            ?.sortedBy { it.name }
            ?: emptyList()
        Log.i(TAG, "${playlist.size} files detected")
        AppLog.event("playlist_loaded", "count=${playlist.size}")
        return playlist
    }

    private fun showCurrent() {
        handler.removeCallbacks(nextRunnable)
        if (playlist.isEmpty()) {
            ++loadGeneration
            isLoading = false
            binding.status.text = getString(R.string.no_photos)
            binding.status.visibility = View.VISIBLE;
            return
        }

        val generation = ++loadGeneration
        isLoading = true
        index = index.coerceIn(0, playlist.lastIndex)
        val file = playlist[index]
        val views = binding
        val outgoing = if (displayedFront) views.frontContainer else views.backContainer
        val incoming = if (displayedFront) views.backContainer else views.frontContainer
        val background = if (displayedFront) views.backBg else views.frontBg
        val foreground = if (displayedFront) views.backFg else views.frontFg

        outgoing.animate().cancel()
        incoming.animate().cancel()
        outgoing.alpha = 1f
        incoming.alpha = 0f
        var readyCount = 0
        var failed = false
        val listener = object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?, model: Any?, target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                if (generation == loadGeneration && _binding === views && !failed) {
                    failed = true
                    isLoading = false
                    AppLog.event("slide_load_failed", "index=$index type=${e?.javaClass?.simpleName}")
                    Log.w(TAG, "Unable to prepare slide", e)
                    scheduleNext()
                }
                return false
            }

            override fun onResourceReady(
                resource: Drawable, model: Any, target: Target<Drawable>?,
                dataSource: DataSource, isFirstResource: Boolean
            ): Boolean {
                // Glide invokes listeners before setting the ImageView drawable.
                // Post the transition so both targets have received their images.
                views.root.post {
                    if (generation != loadGeneration || _binding !== views || failed) {
                        return@post
                    }
                    readyCount++
                    if (readyCount == 2) {
                        isLoading = false
                        incoming.bringToFront()
                        views.status.bringToFront()
                        views.syncBadge.bringToFront()
                        displayedFront = incoming === views.frontContainer
                        incoming.animate().alpha(1f).setDuration(500).start()
                        val db = AppDb.get(requireContext())
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            db.photoDao().markShown(file.name, System.currentTimeMillis())
                        }
                        scheduleNext()
                    }
                }
                return false
            }
        }

        // Equivalent Glide requests reuse their original listeners. Clear the hidden
        // targets so a reload of the same file gets this generation's callbacks.
        Glide.with(this).clear(background)
        Glide.with(this).clear(foreground)

        val metrics = resources.displayMetrics
        // Prepare both images in the hidden container while the current slide stays visible.
        Glide.with(this).load(file)
            .override((metrics.widthPixels / 4).coerceAtLeast(1), (metrics.heightPixels / 4).coerceAtLeast(1))
            .transform(CenterCrop(), BlurTransformation(25, 1))
            .dontAnimate()
            .listener(listener)
            .into(background)
        Glide.with(this).load(file)
            .override(metrics.widthPixels, metrics.heightPixels)
            .fitCenter()
            .dontAnimate()
            .listener(listener)
            .into(foreground)

        binding.status.text = getString(R.string.slideshow_paused)
        binding.status.visibility = if (isPlaying) View.GONE else View.VISIBLE
    }

    private fun scheduleNext() {
        if (!isPlaying || !isResumed || isLoading || playlist.isEmpty()) return
        handler.removeCallbacks(nextRunnable)
        handler.postDelayed(nextRunnable, 10000)
    }

    private fun next() {
        index = (index + 1) % (playlist.size.coerceAtLeast(1))
        showCurrent()
    }

    private fun prev() {
        index = if (index - 1 < 0) (playlist.size - 1).coerceAtLeast(0) else index - 1
        showCurrent()
    }

    override fun onResume() {
        super.onResume()
        binding.root.keepScreenOn = true
        scheduleNext()
    }

    override fun onPause() {
        handler.removeCallbacks(nextRunnable)
        binding.root.keepScreenOn = false
        super.onPause()
    }

    override fun onDestroyView() {
        ++loadGeneration
        isLoading = false
        binding.frontContainer.animate().cancel()
        binding.backContainer.animate().cancel()
        displayedFront = false
        listOf(binding.frontBg, binding.frontFg, binding.backBg, binding.backFg).forEach { Glide.with(this).clear(it) }
        super.onDestroyView()
        _binding = null
        handler.removeCallbacksAndMessages(null)
    }
}
