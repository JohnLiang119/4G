package com.tw.perapp4grouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.tw.perapp4grouter.localvpn.*
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat
import android.widget.TextView
import android.graphics.Color
import android.provider.Settings
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View

class CellularVpnService : VpnService() {

    companion object {
        private const val TAG = "CellularVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "VPN_ROUTER_CHANNEL"
        const val ACTION_START = "com.tw.perapp4grouter.START"
        const val ACTION_STOP = "com.tw.perapp4grouter.STOP"
        const val EXTRA_ALLOWED_APPS = "ALLOWED_APPS"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var networkMonitor: NetworkMonitor? = null
    private var isRunning = false

    private var deviceToNetworkUDPQueue: ConcurrentLinkedQueue<Packet>? = null
    private var deviceToNetworkTCPQueue: ConcurrentLinkedQueue<Packet>? = null
    private var networkToDeviceQueue: ConcurrentLinkedQueue<ByteBuffer>? = null
    private var executorService: ExecutorService? = null
    private var udpSelector: Selector? = null
    private var tcpSelector: Selector? = null

    private var statsRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkMonitor = NetworkMonitor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val allowedApps = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS) ?: arrayListOf()
                startVpn(allowedApps)
                startForeground(NOTIFICATION_ID, createNotification("VPN 路由服務執行中"))
            }
            ACTION_STOP -> {
                stopVpn()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpn(allowedApps: List<String>) {
        if (isRunning) return
        
        networkMonitor?.setListener(object : NetworkMonitor.NetworkStateListener {
            override fun onNetworkStateChanged(isWifiConnected: Boolean, isCellularConnected: Boolean) {
                AppLogger.d(TAG, "Network state: WiFi=$isWifiConnected, Cellular=$isCellularConnected")
            }

            override fun onCellularNetworkAvailable(network: Network) {
                AppLogger.d(TAG, "Cellular network ready for binding")
                LocalVpnNetworkHelper.currentNetwork = network
                setUnderlyingNetworks(arrayOf(network))
            }
        })
        networkMonitor?.start()

        val builder = Builder()
            .setSession("PerApp4GRouter")
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        for (appPackage in allowedApps) {
            try {
                builder.addAllowedApplication(appPackage)
                AppLogger.d(TAG, "Added allowed app: $appPackage")
            } catch (e: Exception) {
                AppLogger.e(TAG, "App not found: $appPackage", e)
            }
        }

        try {
            vpnInterface = builder.establish()
            AppLogger.d(TAG, "VPN Interface established: ${vpnInterface?.fd}")
            isRunning = true

            startLocalVpnEngine()
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to establish VPN", e)
            stopVpn()
        }
    }
    
    private fun startLocalVpnEngine() {
        try {
            val fd = vpnInterface?.fileDescriptor ?: return
            
            udpSelector = Selector.open()
            tcpSelector = Selector.open()
            deviceToNetworkUDPQueue = ConcurrentLinkedQueue()
            deviceToNetworkTCPQueue = ConcurrentLinkedQueue()
            networkToDeviceQueue = ConcurrentLinkedQueue()

            executorService = Executors.newFixedThreadPool(5)
            executorService?.submit(UDPInput(networkToDeviceQueue, udpSelector))
            executorService?.submit(UDPOutput(deviceToNetworkUDPQueue, udpSelector, this))
            executorService?.submit(TCPInput(networkToDeviceQueue, tcpSelector))
            executorService?.submit(TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, tcpSelector, this))
            executorService?.submit(VPNRunnable(fd, deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue))
            
            startStatsUpdater()
            AppLogger.i(TAG, "LocalVPN Engine Started")
        } catch (e: IOException) {
            AppLogger.e(TAG, "Error starting LocalVPN engine", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (!isRunning) return
        AppLogger.d(TAG, "Stopping VPN Service...")
        
        isRunning = false
        stopStatsUpdater()
        removeFloatingWindow()
        executorService?.shutdownNow()
        networkMonitor?.stop()
        
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            AppLogger.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        
        // TODO: Stop tun2socks engine here
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startStatsUpdater() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        statsRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                try {
                    val tcpCount = com.tw.perapp4grouter.localvpn.TCB.getActiveTCBCount()
                    val udpCount = com.tw.perapp4grouter.localvpn.UDPOutput.getActiveUDPCount()
                    val notifText = "TCP 連線: $tcpCount | UDP 連線: $udpCount"
                    val floatText = "$tcpCount/$udpCount"
                    
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (prefs.getBoolean(MainActivity.PREF_SHOW_NOTIF, true)) {
                        manager.notify(NOTIFICATION_ID, createNotification(notifText))
                    } else {
                        manager.notify(NOTIFICATION_ID, createNotification("VPN 路由服務執行中"))
                    }

                    updateFloatingWindow(floatText, prefs)
                } catch (e: Exception) {}
                
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(statsRunnable!!)
    }

    private fun stopStatsUpdater() {
        statsRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun updateFloatingWindow(text: String, prefs: SharedPreferences) {
        val showFloat = prefs.getBoolean(MainActivity.PREF_SHOW_FLOAT, false)
        if (showFloat && Settings.canDrawOverlays(this)) {
            if (floatingView == null) {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                floatingView = TextView(this).apply {
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#80000000"))
                    setPadding(32, 16, 32, 16)
                }
                
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    y = 150 // small offset from the very bottom
                    x = 0
                }
                
                floatingView?.setOnTouchListener(object : View.OnTouchListener {
                    private var initialX = 0
                    private var initialY = 0
                    private var initialTouchX = 0f
                    private var initialTouchY = 0f

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = params.x
                                initialY = params.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                params.x = initialX + (event.rawX - initialTouchX).toInt()
                                params.y = initialY + (event.rawY - initialTouchY).toInt()
                                try {
                                    windowManager?.updateViewLayout(floatingView, params)
                                } catch (e: Exception) {}
                                return true
                            }
                        }
                        return false
                    }
                })

                try {
                    windowManager?.addView(floatingView, params)
                } catch (e: Exception) {}
            }
            floatingView?.text = text
        } else {
            removeFloatingWindow()
        }
    }

    private fun removeFloatingWindow() {
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {}
            floatingView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Router Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("4G Router")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_secure) // Replace with app icon later
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }
}
