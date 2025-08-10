package com.bgradin.customframe.ui

import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bgradin.customframe.data.Prefs
import com.bgradin.customframe.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = Prefs(requireContext())
        binding.inputSsid.setText(prefs.wifiSsid)
        binding.inputPassword.setText(prefs.wifiPassword)
        binding.inputDropboxToken.setText(prefs.dropboxToken)
        binding.inputDropboxFolder.setText(prefs.dropboxFolder)

        binding.btnSave.setOnClickListener {
            prefs.wifiSsid = binding.inputSsid.text.toString()
            prefs.wifiPassword = binding.inputPassword.text.toString()
            prefs.dropboxToken = binding.inputDropboxToken.text.toString()
            prefs.dropboxFolder = binding.inputDropboxFolder.text.toString()
        }

        binding.btnConnectWifi.setOnClickListener { connectWifi() }
    }

    private fun connectWifi() {
        val prefs = Prefs(requireContext())
        val ssid = prefs.wifiSsid
        val password = prefs.wifiPassword

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec)
                .build()

            val cm = requireContext().getSystemService(ConnectivityManager::class.java)
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {})
        } else {
            val wm = requireContext().applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val config = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            @Suppress("DEPRECATION") val netId = wm.addNetwork(config)
            @Suppress("DEPRECATION") wm.enableNetwork(netId, true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}