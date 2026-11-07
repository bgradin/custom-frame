package com.bgradin.customframe.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bgradin.customframe.R
import com.bgradin.customframe.data.Prefs
import com.bgradin.customframe.databinding.FragmentSettingsBinding
import com.bgradin.customframe.work.PhotoSyncWorker

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var wakeStartMinutes = 0
    private var wakeEndMinutes = 0

    private var inlineKeyboard: InlineKeyboard? = null

    private lateinit var wifiManager: WifiManager
    private val scanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var scanReceiver: BroadcastReceiver? = null
    private val networks = mutableListOf<WifiItem>()
    private lateinit var adapter: NetworksAdapter

    private var selected: WifiItem? = null

    private var pendingCallback: ConnectivityManager.NetworkCallback? = null
    private val cm by lazy { requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private var pendingSelectSsid: String? = null

    private val prefs by lazy { Prefs(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        inlineKeyboard = InlineKeyboard(binding.root, binding.settingsContent)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inlineKeyboard?.close() != true) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        wakeStartMinutes = savedInstanceState?.getInt("wakeStart") ?: prefs.wakeStartMinutes
        wakeEndMinutes = savedInstanceState?.getInt("wakeEnd") ?: prefs.wakeEndMinutes
        binding.wakeWindowEnabled.isChecked = savedInstanceState?.getBoolean("wakeEnabled") ?: prefs.wakeWindowEnabled
        updateWakeButtons()
        binding.wakeWindowEnabled.setOnCheckedChangeListener { _, _ -> updateWakeButtons() }
        binding.btnWakeStart.setOnClickListener { chooseWakeTime(true) }
        binding.btnWakeEnd.setOnClickListener { chooseWakeTime(false) }
        binding.btnSystemTime.setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.system_time_unavailable, Toast.LENGTH_LONG).show()
            } catch (_: SecurityException) {
                Toast.makeText(requireContext(), R.string.system_time_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        applyFocusMotion(view)
        // Prefill saved password if any
        val savedPw = prefs.wifiPassword
        if (savedPw.isNotBlank()) {
            binding.inputWifiPassword.setText(savedPw)
        }

        // Remember saved SSID; we'll try to select it when results arrive
        val savedSsid = prefs.wifiSsid
        if (savedSsid.isNotBlank()) {
            pendingSelectSsid = savedSsid
        }

        // Build network list
        adapter = NetworksAdapter(networks) { item ->
            selectNetwork(item)
        }
        binding.rvNetworks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNetworks.adapter = adapter

        binding.btnScanWifi.setOnClickListener { ensureLocationAndScan() }

        // Pressing Play/Accept on the list triggers "Scan" if empty, else opens selection
        binding.rvNetworks.setOnKeyListener { _, keyCode, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER && networks.isEmpty()) {
                ensureLocationAndScan(); true
            } else false
        }

        // Connect flow
        binding.btnConnectWifi.setOnClickListener { startConnect() }

        // Error panel actions
        binding.btnRetry.setOnClickListener { startConnect() }
        binding.btnReenter.setOnClickListener { // open keyboard
            inlineKeyboard?.open(binding.inputWifiPassword, getString(R.string.wi_fi_password))
        }
        binding.btnChooseDifferent.setOnClickListener {
            showError(null)
            binding.rvNetworks.requestFocus()
        }

        // Optional: auto-scan on entry
        ensureLocationAndScan()

        wireDpadKeyboard(binding.inputWifiPassword, getString(R.string.wi_fi_password))
        wireDpadKeyboard(binding.inputDropboxToken, getString(R.string.dropbox_access_token))
        wireDpadKeyboard(binding.inputDropboxFolder, getString(R.string.dropbox_folder))
        wireDpadKeyboard(binding.inputPhotoSyncLimit, getString(R.string.photo_sync_limit))

        binding.btnSave.setOnClickListener { saveSettings() }

        binding.inputDropboxToken.setText(prefs.dropboxToken)
        binding.inputDropboxFolder.setText(prefs.dropboxFolder)
        binding.inputPhotoSyncLimit.setText(prefs.photoSyncLimit.takeIf { it > 0 }?.toString().orEmpty())

        val rv = binding.rvNetworks
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (isDescendant(rv, newFocus)) {
                rv.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_recycler_focus)
            } else {
                rv.background = null
            }
        }
        rv.viewTreeObserver.addOnGlobalFocusChangeListener(listener)

        viewLifecycleOwner.lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    rv.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        binding.currentTime.text = java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm z", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            outState.putInt("wakeStart", wakeStartMinutes)
            outState.putInt("wakeEnd", wakeEndMinutes)
            outState.putBoolean("wakeEnabled", binding.wakeWindowEnabled.isChecked)
        }
    }

    private fun updateWakeButtons() {
        fun time(minutes: Int) = String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)
        binding.btnWakeStart.text = getString(R.string.wake_start, time(wakeStartMinutes))
        binding.btnWakeEnd.text = getString(R.string.wake_end, time(wakeEndMinutes))
        binding.btnWakeStart.isEnabled = binding.wakeWindowEnabled.isChecked
        binding.btnWakeEnd.isEnabled = binding.wakeWindowEnabled.isChecked
    }

    private fun chooseWakeTime(start: Boolean) {
        val minutes = if (start) wakeStartMinutes else wakeEndMinutes
        android.app.TimePickerDialog(requireContext(), { _, hour, minute ->
            if (_binding != null) {
                if (start) wakeStartMinutes = hour * 60 + minute else wakeEndMinutes = hour * 60 + minute
                updateWakeButtons()
            }
        }, minutes / 60, minutes % 60, true).show()
    }

    private fun applyFocusMotion(view: View) {
        // Scrolling rows must stay inside the network selector's viewport.
        if (view is RecyclerView) {
            view.clipChildren = true
            view.clipToPadding = true
            return
        }
        if (view is ViewGroup) {
            view.clipChildren = false
            view.clipToPadding = false
            for (index in 0 until view.childCount) applyFocusMotion(view.getChildAt(index))
        }
        if (view is android.widget.Button || view is android.widget.EditText) {
            view.setOnFocusChangeListener { control, focused ->
                control.animate().scaleX(if (focused) 1.025f else 1f)
                    .scaleY(if (focused) 1.025f else 1f).setDuration(140).start()
            }
        }
    }

    private fun isDescendant(parent: View, child: View?): Boolean {
        var v = child
        while (v is View) {
            if (v === parent) return true
            v = (v.parent as? View)
        }
        return false
    }

    private fun saveSettings() {
        val limitText = binding.inputPhotoSyncLimit.text?.toString().orEmpty().trim()
        val limit = if (limitText.isEmpty()) 0 else limitText.toIntOrNull()
        if (limit == null || (limitText.isNotEmpty() && limit !in 1..200)) {
            binding.inputPhotoSyncLimit.error = getString(R.string.photo_sync_limit_error)
            binding.inputPhotoSyncLimit.requestFocus()
            return
        }
        val limitChanged = prefs.photoSyncLimit != limit
        prefs.photoSyncLimit = limit
        prefs.wakeStartMinutes = wakeStartMinutes
        prefs.wakeEndMinutes = wakeEndMinutes
        prefs.wakeWindowEnabled = binding.wakeWindowEnabled.isChecked
        (requireActivity() as com.bgradin.customframe.MainActivity).wakeSettingsChanged()
        // persist wifi credentials
        prefs.wifiSsid = selected?.ssid ?: prefs.wifiSsid
        if (selected != null) {
            prefs.wifiPassword = if (selected?.security != Security.OPEN)
                binding.inputWifiPassword.text?.toString().orEmpty() else ""
        }

        val oldToken = prefs.dropboxToken
        val oldFolder = prefs.dropboxFolder

        prefs.dropboxToken = binding.inputDropboxToken.text?.toString().orEmpty().trim()
        val folder = binding.inputDropboxFolder.text?.toString().orEmpty().trim().trimEnd('/')
        prefs.dropboxFolder = if (folder.isBlank()) "/" else "/" + folder.trimStart('/')

        val credsChanged = (prefs.dropboxToken != oldToken) || (prefs.dropboxFolder != oldFolder)
        val tenMinMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(10)
        val stale = (System.currentTimeMillis() - prefs.lastSyncAt) > tenMinMs
        if (prefs.dropboxToken.isNotBlank() && (credsChanged || limitChanged || stale)) {
            val nowReq = OneTimeWorkRequestBuilder<PhotoSyncWorker>()
                .setConstraints(androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .addTag(PhotoSyncWorker.WORK_TAG)
                .build()
            WorkManager.getInstance(requireContext().applicationContext)
                .enqueueUniqueWork(
                    PhotoSyncWorker.NOW_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,  // replace any pending “now” job
                    nowReq
                )
            Toast.makeText(requireContext(), "Starting sync…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        }

        findNavController().popBackStack()
    }

    private fun selectNetwork(item: WifiItem) {
        if (selected?.ssid != item.ssid) {
            binding.inputWifiPassword.setText(if (prefs.wifiSsid == item.ssid) prefs.wifiPassword else "")
        }
        selected = item
        adapter.setSelected(item.ssid)

        val isOpen = item.security == Security.OPEN
        binding.inputWifiPassword.isEnabled = !isOpen

        if (isOpen) {
            binding.inputWifiPassword.setText("")
        } else {
            // If nothing typed yet, prefill with saved password
            if (binding.inputWifiPassword.text.isNullOrBlank()) {
                val savedForSsid = prefs.wifiPassword
                if (savedForSsid.isNotBlank() && prefs.wifiSsid == item.ssid) {
                    binding.inputWifiPassword.setText(savedForSsid)
                }
            }
            // Keep your D-pad keyboard
            wireDpadKeyboard(binding.inputWifiPassword, getString(R.string.wi_fi_password))
        }
    }

    private fun ensureLocationAndScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            return
        }
        startWifiScan()
    }

    @SuppressLint("MissingPermission")
    private fun startWifiScan() {
        binding.progressScan.visibility = View.VISIBLE
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        scanReceiver?.let { requireContext().unregisterReceiver(it) }
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scanHandler.removeCallbacksAndMessages(null)
                binding.progressScan.visibility = View.GONE
                loadScanResults()
                // Unregister after one batch to keep things simple
                try { requireContext().unregisterReceiver(this) } catch (_: Exception) {}
                scanReceiver = null
            }
        }
        ContextCompat.registerReceiver(requireContext(), scanReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        scanHandler.removeCallbacksAndMessages(null)
        scanHandler.postDelayed({
            if (_binding != null) {
                binding.progressScan.visibility = View.GONE
                loadScanResults()
                scanReceiver?.let { requireContext().unregisterReceiver(it) }
                scanReceiver = null
            }
        }, 15000)
        val ok = wifiManager.startScan()
        if (!ok) {
            // Even if startScan() returns false, you may still get cached results
            binding.progressScan.visibility = View.GONE
            loadScanResults()
            scanReceiver?.let { requireContext().unregisterReceiver(it) }
            scanReceiver = null
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE])
    private fun loadScanResults() {
        val results = wifiManager.scanResults.orEmpty()
        networks.clear()

        val map = mutableMapOf<String, ScanResult>()
        for (sr in results) {
            val ssid = sr.SSID ?: continue
            if (ssid.isBlank()) continue
            val prev = map[ssid]
            if (prev == null || WifiManager.calculateSignalLevel(sr.level, 100) >
                WifiManager.calculateSignalLevel(prev.level, 100)) {
                map[ssid] = sr
            }
        }

        map.values
            .sortedByDescending { it.level }
            .forEach { sr ->
                networks.add(WifiItem(sr.SSID, parseSecurity(sr), sr.level))
            }

        // 🔧 Tell RecyclerView to refresh
        adapter.notifyDataSetChanged()

        // Keep selection highlight if applicable
        adapter.setSelected(selected?.ssid)

        val wanted = pendingSelectSsid ?: prefs.wifiSsid
        if (wanted.isNotBlank()) {
            val match = networks.firstOrNull { it.ssid == wanted }
            if (match != null) {
                selectNetwork(match)
                pendingSelectSsid = null
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWifiScan()
    }

    // === Connect ===

    private fun startConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            return
        }
        val item = selected
        if (item == null) {
            Toast.makeText(requireContext(), "Choose a network first", Toast.LENGTH_SHORT).show()
            return
        }
        if (item.security == Security.ENTERPRISE || item.security == Security.WPA3) {
            showError("This network requires system Wi-Fi settings.")
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            return
        }
        val pw = binding.inputWifiPassword.text?.toString().orEmpty()
        if (item.security != Security.OPEN && pw.isEmpty()) {
            Toast.makeText(requireContext(), "Enter password", Toast.LENGTH_SHORT).show()
            inlineKeyboard?.open(binding.inputWifiPassword, getString(R.string.wi_fi_password))
            return
        }

        // UI: show spinner, hide prior status
        showConnecting(true)
        showConnected(false)
        showError(null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // System Wi-Fi settings establish a persistent internet connection.
            showConnecting(false)
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        } else {
            legacyConnect(item, pw)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyConnect(item: WifiItem, password: String) {
        try {
            val config = WifiConfiguration().apply {
                SSID = "\"${item.ssid}\""
                when (item.security) {
                    Security.ENTERPRISE -> error("Enterprise networks require system settings")
                    Security.OPEN -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                    Security.WEP -> {
                        wepKeys[0] = "\"$password\""
                        wepTxKeyIndex = 0
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    }
                    Security.WPA2, Security.WPA3, Security.WPA_MIXED -> {
                        preSharedKey = "\"$password\""
                    }
                }
            }
            val netId = wifiManager.addNetwork(config)
            if (netId == -1) {
                showConnecting(false); showError("Failed to add network."); return
            }
            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            val ok = wifiManager.reconnect()
            if (ok) {
                // Give it a moment and declare success
                binding.root.postDelayed({
                    if (_binding == null) return@postDelayed
                    showConnecting(false)
                    val connected = wifiManager.connectionInfo?.ssid?.trim('"') == item.ssid
                    showConnected(connected)
                    if (!connected) showError("Connection not confirmed. Check the password and try again.")
                }, 2000)
            } else {
                showConnecting(false); showError("Unable to connect.")
            }
        } catch (t: Throwable) {
            showConnecting(false); showError(t.message ?: "Error connecting.")
        }
    }

    // === UI helpers ===

    private fun showConnecting(show: Boolean) {
        binding.panelConnecting.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnConnectWifi.isEnabled = !show
        binding.btnScanWifi.isEnabled = !show
        binding.rvNetworks.isEnabled = !show
        binding.inputWifiPassword.isEnabled = !show && (selected?.security != Security.OPEN)
    }

    private fun showConnected(show: Boolean) {
        binding.tvConnected.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.panelConnError.visibility = View.GONE
        }
    }

    private fun showError(msg: String?) {
        if (msg == null) {
            binding.panelConnError.visibility = View.GONE
            return
        }
        binding.panelConnError.visibility = View.VISIBLE
        binding.tvConnError.text = msg
        binding.btnRetry.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanHandler.removeCallbacksAndMessages(null)
        scanReceiver?.let { requireContext().unregisterReceiver(it) }
        scanReceiver = null
        pendingCallback?.let { cm.unregisterNetworkCallback(it) }
        pendingCallback = null
        inlineKeyboard?.dispose()
        inlineKeyboard = null
        _binding = null
    }

    // === Utilities ===

    private fun parseSecurity(sr: ScanResult): Security {
        val caps = sr.capabilities ?: return Security.OPEN
        return when {
            caps.contains("EAP", true) -> Security.ENTERPRISE
            caps.contains("WEP", true) -> Security.WEP
            caps.contains("WPA3", true) -> Security.WPA3
            caps.contains("WPA2", true) && caps.contains("WPA", true) -> Security.WPA_MIXED
            caps.contains("WPA2", true) -> Security.WPA2
            caps.contains("WPA", true) -> Security.WPA_MIXED
            else -> Security.OPEN
        }
    }

    data class WifiItem(val ssid: String, val security: Security, val rssi: Int)

    enum class Security { OPEN, WEP, WPA2, WPA3, WPA_MIXED, ENTERPRISE }

    private inner class NetworksAdapter(
        private val items: List<WifiItem>,
        private val onClickExternal: (WifiItem) -> Unit
    ) : RecyclerView.Adapter<NetworksAdapter.VH>() {

        var selectedSsid: String? = null
            private set

        fun setSelected(ssid: String?) {
            val old = selectedSsid
            selectedSsid = ssid
            if (old != ssid) notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val root: View = v.findViewById(R.id.root)
            val ssidText: TextView = v.findViewById(R.id.ssidText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_wifi, parent, false)
            v.setOnFocusChangeListener { row, focused ->
                row.animate().scaleX(if (focused) 1.015f else 1f)
                    .scaleY(if (focused) 1.015f else 1f).setDuration(140).start()
            }
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val lock = if (item.security == Security.OPEN) "" else " \uD83D\uDD12"
            holder.ssidText.text = "${item.ssid}$lock"

            val selected = item.ssid == selectedSsid
            holder.itemView.isSelected = selected

            holder.root.setOnClickListener {
                setSelected(item.ssid)
                onClickExternal(item)
            }
            holder.root.setOnKeyListener { _, keyCode, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    setSelected(item.ssid)
                    onClickExternal(item)
                    true
                } else false
            }

            // Ensure rows are focusable for DPAD navigation
            holder.root.isFocusable = true
            holder.root.isFocusableInTouchMode = true


        }

        override fun getItemCount() = items.size
    }

    private fun wireDpadKeyboard(target: android.widget.EditText, title: String) {
        target.showSoftInputOnFocus = false
        target.setOnClickListener { inlineKeyboard?.open(target, title) }
        target.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                inlineKeyboard?.open(target, title)
                true
            } else false
        }
    }

    companion object {
        private const val REQ_LOC = 2001
    }
}
