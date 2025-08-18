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
import android.net.wifi.WifiNetworkSpecifier
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
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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

    private lateinit var wifiManager: WifiManager
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
            DpadKeyboardDialogFragment
                .newInstance("Wi‑Fi Password", binding.inputWifiPassword.text?.toString() ?: "") { newText ->
                    binding.inputWifiPassword.setText(newText)
                }
                .show(parentFragmentManager, "dpadkbd")
        }
        binding.btnChooseDifferent.setOnClickListener {
            showError(null)
            binding.rvNetworks.requestFocus()
        }

        // Optional: auto-scan on entry
        ensureLocationAndScan()

        wireDpadKeyboard(binding.inputWifiPassword, "Wi‑Fi Password")
        wireDpadKeyboard(binding.inputDropboxToken, "Dropbox Access Token")
        wireDpadKeyboard(binding.inputDropboxFolder, "Dropbox Folder")

        binding.btnSave.setOnClickListener { saveSettings() }

        binding.inputDropboxToken.setText(prefs.dropboxToken)
        binding.inputDropboxFolder.setText(prefs.dropboxFolder)

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

    private fun isDescendant(parent: View, child: View?): Boolean {
        var v = child
        while (v is View) {
            if (v === parent) return true
            v = (v.parent as? View)
        }
        return false
    }

    private fun saveSettings() {
        // persist wifi credentials
        prefs.wifiSsid = selected?.ssid ?: ""
        prefs.wifiPassword = if (selected?.security != Security.OPEN)
            binding.inputWifiPassword.text?.toString().orEmpty() else ""

        val oldToken = prefs.dropboxToken
        val oldFolder = prefs.dropboxFolder

        prefs.dropboxToken = binding.inputDropboxToken.text?.toString().orEmpty()
        prefs.dropboxFolder = binding.inputDropboxFolder.text?.toString().orEmpty()

        val credsChanged = (prefs.dropboxToken != oldToken) || (prefs.dropboxFolder != oldFolder)
        val tenMinMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(10)
        val stale = (System.currentTimeMillis() - prefs.lastSyncAt) > tenMinMs
        if (credsChanged || stale || true) {
            val nowReq = OneTimeWorkRequestBuilder<PhotoSyncWorker>()
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

        activity?.supportFragmentManager?.popBackStack()
    }

    private fun selectNetwork(item: WifiItem) {
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
            wireDpadKeyboard(binding.inputWifiPassword, "Wi-Fi Password")
        }
    }

    private fun ensureLocationAndScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC)
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
                binding.progressScan.visibility = View.GONE
                loadScanResults()
                // Unregister after one batch to keep things simple
                try { requireContext().unregisterReceiver(this) } catch (_: Exception) {}
                scanReceiver = null
            }
        }
        requireContext().registerReceiver(scanReceiver, filter)
        val ok = wifiManager.startScan()
        if (!ok) {
            // Even if startScan() returns false, you may still get cached results
            binding.progressScan.visibility = View.GONE
            loadScanResults()
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

    // === Connect ===

    private fun startConnect() {
        val item = selected
        if (item == null) {
            Toast.makeText(requireContext(), "Choose a network first", Toast.LENGTH_SHORT).show()
            return
        }
        val pw = binding.inputWifiPassword.text?.toString().orEmpty()
        if (item.security != Security.OPEN && pw.isEmpty()) {
            Toast.makeText(requireContext(), "Enter password", Toast.LENGTH_SHORT).show()
            DpadKeyboardDialogFragment
                .newInstance("Wi‑Fi Password", binding.inputWifiPassword.text?.toString() ?: "") { newText ->
                    binding.inputWifiPassword.setText(newText)
                }
                .show(parentFragmentManager, "dpadkbd")
            return
        }

        // UI: show spinner, hide prior status
        showConnecting(true)
        showConnected(false)
        showError(null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Specifier = temporary network for the app (good for our frame use)
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(item.ssid)
            when (item.security) {
                Security.OPEN -> { /* nothing */ }
                Security.WPA2, Security.WPA3, Security.WPA_MIXED -> {
                    // Use WPA2 by default; WPA3 requires platform support
                    specBuilder.setWpa2Passphrase(pw)
                }
                Security.WEP -> {
                    // WEP is deprecated and not supported by specifier
                    showError("WEP networks are not supported on this Android version.")
                    showConnecting(false); return
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // optional; set if AP lacks internet
                .setNetworkSpecifier(specBuilder.build())
                .build()

            pendingCallback?.let { cm.unregisterNetworkCallback(it) }
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Optionally bind process to verify connectivity / download test
                    cm.bindProcessToNetwork(network)
                    requireActivity().runOnUiThread {
                        showConnecting(false)
                        showConnected(true)
                    }
                }
                override fun onUnavailable() {
                    requireActivity().runOnUiThread {
                        showConnecting(false)
                        showError("Unable to connect.")
                    }
                }
                override fun onLost(network: Network) {
                    requireActivity().runOnUiThread {
                        showConnected(false)
                        showError("Connection lost.")
                    }
                }
            }
            pendingCallback = cb
            cm.requestNetwork(request, cb, 20_000) // 20s timeout
        } else {
            // Legacy: configure and enable
            legacyConnect(item, pw)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyConnect(item: WifiItem, password: String) {
        try {
            val config = WifiConfiguration().apply {
                SSID = "\"${item.ssid}\""
                when (item.security) {
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
                    showConnecting(false)
                    showConnected(true)
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
        scanReceiver?.let { requireContext().unregisterReceiver(it) }
        scanReceiver = null
        pendingCallback?.let { cm.unregisterNetworkCallback(it) }
        pendingCallback = null
        _binding = null
    }

    // === Utilities ===

    private fun parseSecurity(sr: ScanResult): Security {
        val caps = sr.capabilities ?: return Security.OPEN
        return when {
            caps.contains("WEP", true) -> Security.WEP
            caps.contains("WPA3", true) -> Security.WPA3
            caps.contains("WPA2", true) && caps.contains("WPA", true) -> Security.WPA_MIXED
            caps.contains("WPA2", true) -> Security.WPA2
            caps.contains("WPA", true) -> Security.WPA_MIXED
            else -> Security.OPEN
        }
    }

    data class WifiItem(val ssid: String, val security: Security, val rssi: Int)

    enum class Security { OPEN, WEP, WPA2, WPA3, WPA_MIXED }

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

            binding.inputWifiPassword.requestFocus()
        }

        override fun getItemCount() = items.size
    }

    // Use your existing helper from earlier responses
    private fun wireDpadKeyboard(target: android.widget.EditText, title: String) {
        target.showSoftInputOnFocus = false
        target.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                DpadKeyboardDialogFragment
                    .newInstance(title, target.text?.toString() ?: "") { newText ->
                        target.setText(newText)
                    }
                    .show(parentFragmentManager, "dpadkbd")
                true
            } else false
        }
    }

    companion object {
        private const val REQ_LOC = 2001
    }
}
