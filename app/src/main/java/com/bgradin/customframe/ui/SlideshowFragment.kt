package com.bgradin.customframe.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
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
    private var isPlaying = true
    private var index = 0
    private lateinit var playlist: List<File>
    private val photoFiles = mutableListOf<File>()
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

        val wm = WorkManager.getInstance(requireContext())
        wm.getWorkInfosByTagLiveData(PhotoSyncWorker.WORK_TAG)
            .observe(viewLifecycleOwner) { infos ->
                val syncing = infos.any { it.state == WorkInfo.State.RUNNING }
                binding.syncBadge.visibility = if (syncing) View.VISIBLE else View.GONE

                // When sync finishes AND the worker bumped lastSyncAt, reload & restart
                val now = prefs.lastSyncAt
                if (!syncing && now > lastSeenSyncAt) {
                    lastSeenSyncAt = now
                    // tiny post to let file writes settle
                    view.post {
                        playlist = loadPlaylist()
                        showCurrent()
                        scheduleNext()
                    }
                }
            }

        playlist = loadPlaylist()
        showCurrent()
        scheduleNext()

        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        binding.root.setOnKeyListener { _, keyCode, _ ->
            when (keyCode) {
                23 -> { // Play/accept
                    isPlaying = !isPlaying
                    if (isPlaying) scheduleNext() else handler.removeCallbacksAndMessages(null)
                    true
                }
                21 -> { prev(); true }
                22 -> { next(true); true }
                24 -> { adjustInterval(-1000); true }
                25 -> { adjustInterval(+1000); true }
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
        return playlist
    }

    private fun showCurrent() {
        if (playlist.isEmpty()) {
            binding.status.text = getString(R.string.no_photos)
            return
        }
        val file = playlist[index % playlist.size]
        Glide.with(this)
            .load(file)
            .transition(DrawableTransitionOptions.withCrossFade(500))
            .into(binding.image)
        binding.status.text = "${index+1}/${playlist.size}"
    }

    private fun scheduleNext() {
        if (!isPlaying) return
        handler.postDelayed({ next(false) }, Prefs(requireContext()).slideIntervalMs)
    }

    private fun next(manual: Boolean) {
        index = (index + 1) % (playlist.size.coerceAtLeast(1))
        showCurrent()
        if (isPlaying && !manual) scheduleNext()
    }

    private fun prev() {
        index = if (index - 1 < 0) (playlist.size - 1).coerceAtLeast(0) else index - 1
        showCurrent()
    }

    private fun adjustInterval(delta: Long) {
        val p = Prefs(requireContext())
        p.slideIntervalMs = (p.slideIntervalMs + delta).coerceIn(2000, 30000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        handler.removeCallbacksAndMessages(null)
    }
}
