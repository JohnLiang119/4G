package com.tw.perapp4grouter

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private var cellularNetwork: Network? = null
    var isWifiConnected: Boolean = false
        private set
    var isCellularConnected: Boolean = false
        private set

    interface NetworkStateListener {
        fun onNetworkStateChanged(isWifiConnected: Boolean, isCellularConnected: Boolean)
        fun onCellularNetworkAvailable(network: Network)
    }

    private var listener: NetworkStateListener? = null

    fun setListener(listener: NetworkStateListener) {
        this.listener = listener
        listener.onNetworkStateChanged(isWifiConnected, isCellularConnected)
        cellularNetwork?.let { listener.onCellularNetworkAvailable(it) }
    }

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppLogger.d("NetworkMonitor", "Cellular Network Available: $network")
            cellularNetwork = network
            isCellularConnected = true
            listener?.onCellularNetworkAvailable(network)
            listener?.onNetworkStateChanged(isWifiConnected, isCellularConnected)
        }

        override fun onLost(network: Network) {
            AppLogger.d("NetworkMonitor", "Cellular Network Lost: $network")
            if (cellularNetwork == network) {
                cellularNetwork = null
                isCellularConnected = false
                listener?.onNetworkStateChanged(isWifiConnected, isCellularConnected)
            }
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppLogger.d("NetworkMonitor", "WiFi Network Available")
            isWifiConnected = true
            listener?.onNetworkStateChanged(isWifiConnected, isCellularConnected)
        }

        override fun onLost(network: Network) {
            AppLogger.d("NetworkMonitor", "WiFi Network Lost")
            isWifiConnected = false
            listener?.onNetworkStateChanged(isWifiConnected, isCellularConnected)
        }
    }

    fun start() {
        // Request cellular network to wake it up and keep it active even if WiFi is connected
        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.requestNetwork(cellularRequest, cellularCallback)

        // Monitor WiFi network status
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
            
        connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback)
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(cellularCallback)
            connectivityManager.unregisterNetworkCallback(wifiCallback)
        } catch (e: Exception) {
            AppLogger.e("NetworkMonitor", "Error unregistering callbacks", e)
        }
        cellularNetwork = null
        isCellularConnected = false
        isWifiConnected = false
    }

    fun getCellularNetwork(): Network? {
        return cellularNetwork
    }
}
