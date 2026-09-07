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
import com.tw.perapp4grouter.stats.*
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
import android.widget.LinearLayout

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

    private var statsSession: VpnStatsSession? = null

    private var statsRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingLayout: LinearLayout? = null
    private var tvFloatingSummary: TextView? = null
    private var tvFloatingDetails: TextView? = null
    private var isFloatingExpanded = false

    private var lastNotificationTimeMillis = 0L
    private var lastNotificationFlowCount = -1

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
        if (isRunning) {
            // 執行中的 session 綁定的是當時已套用的清單；變更選擇必須重新套用（停止後再啟動）
            AppLogger.i(TAG, "VPN 已在執行中，變更 APP 選擇需要重新啟動 VPN 才會生效")
            return
        }

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

        // 在 establish() 之前逐一驗證並加入允許清單，只保留真正被 builder 接受的 package
        val appliedApps = AppliedAppSelector.apply(
            selectedPackages = allowedApps,
            resolver = AndroidAppliedPackageResolver(packageManager),
            sink = object : AllowedApplicationSink {
                override fun addAllowedApplication(packageName: String): Boolean = try {
                    builder.addAllowedApplication(packageName)
                    AppLogger.d(TAG, "Added allowed app: $packageName")
                    true
                } catch (e: Exception) {
                    AppLogger.e(TAG, "App not found: $packageName", e)
                    false
                }
            }
        )

        if (appliedApps.isEmpty()) {
            // 空的 allowed list 會讓 Android 把所有 APP 導入 VPN，因此必須在 establish() 之前中止
            AppLogger.e(TAG, "沒有任何可套用的 APP，已中止建立 VPN（不會退化成全部 APP）", null)
            showError("沒有任何可套用的 APP，請重新選擇後再啟動")
            networkMonitor?.stop()
            stopSelf()
            return
        }

        try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                throw IOException("VpnService.establish() 回傳 null")
            }
            AppLogger.d(TAG, "VPN Interface established: ${vpnInterface?.fd}")
            isRunning = true

            // 只有在建立成功之後才開新的統計 generation，確保每個成功 session 都從零開始
            startStatsSession(appliedApps)
            startLocalVpnEngine()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to establish VPN", e)
            stopVpn()
        }
    }

    /** 以本次實際套用的 APP 清單建立新的統計 session。 */
    private fun startStatsSession(appliedApps: List<AppliedApp>) {
        val index = AppliedAppIndex.build(appliedApps)
        val session = VpnStatsSession(
            generation = VpnStatsSession.nextGeneration(),
            appliedAppIndex = index,
            ownerLookup = AndroidConnectionOwnerLookup(this)
        )
        statsSession = session
        FlowObservers.set(session)
        session.publishEmpty()
        AppLogger.i(TAG, "統計 session 已建立（generation=${session.generation}，已套用 ${index.appliedPackages.size} 個 APP）")
    }

    /** 清除統計 session 的所有狀態，包含追蹤器、解析器與已發布的快照。 */
    private fun stopStatsSession() {
        FlowObservers.set(null)
        statsSession?.shutdown()
        statsSession = null
        StatsSnapshotRepository.clear()
    }

    private fun showError(message: String) {
        handler.post {
            try {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
            }
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
        if (!isRunning) {
            // establish() 失敗等路徑仍必須清除已建立的統計狀態
            stopStatsSession()
            networkMonitor?.stop()
            return
        }
        AppLogger.d(TAG, "Stopping VPN Service...")
        
        isRunning = false
        stopStatsUpdater()
        stopStatsSession()
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
                    // 每秒由追蹤器推導一份新的不可變快照供三個介面共用
                    statsSession?.publish()
                    val snapshot = com.tw.perapp4grouter.stats.StatsSnapshotRepository.latest()
                    
                    val now = System.currentTimeMillis()
                    val flowsChangedToZeroOrFirst = (lastNotificationFlowCount == 0 && snapshot.totalFlows > 0) || 
                                                    (lastNotificationFlowCount > 0 && snapshot.totalFlows == 0)
                    
                    if (now - lastNotificationTimeMillis >= 5000 || flowsChangedToZeroOrFirst) {
                        lastNotificationTimeMillis = now
                        lastNotificationFlowCount = snapshot.totalFlows

                        val notifText: String
                        if (snapshot.isEmpty) {
                            notifText = "無作用中連線"
                        } else {
                            val sorted = snapshot.rows.sortedByDescending { it.total }
                            val appCount = snapshot.activeAppCount
                            val appWord = if (appCount == 1) "APP" else "APPs"
                            val prefix = "$appCount $appWord · ${snapshot.totalFlows} 連線"
                            
                            val details = mutableListOf<String>()
                            for (i in 0 until Math.min(2, sorted.size)) {
                                details.add("${sorted[i].label}: ${sorted[i].total}")
                            }
                            if (sorted.size > 2) {
                                details.add("另 ${sorted.size - 2} 個")
                            }
                            notifText = "$prefix (${details.joinToString(", ")})"
                        }

                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (prefs.getBoolean(MainActivity.PREF_SHOW_NOTIF, true)) {
                            manager.notify(NOTIFICATION_ID, createNotification(notifText))
                        } else {
                            manager.notify(NOTIFICATION_ID, createNotification("VPN 路由服務執行中"))
                        }
                    }

                    updateFloatingWindow(snapshot, prefs)
                } catch (e: Exception) {}
                
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(statsRunnable!!)
    }

    private fun stopStatsUpdater() {
        statsRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun updateFloatingWindow(snapshot: com.tw.perapp4grouter.stats.StatsSnapshot, prefs: SharedPreferences) {
        val showFloat = prefs.getBoolean(MainActivity.PREF_SHOW_FLOAT, false)
        if (showFloat && Settings.canDrawOverlays(this)) {
            if (floatingLayout == null) {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                
                floatingLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#99000000"))
                    setPadding(32, 16, 32, 16)
                }
                
                tvFloatingSummary = TextView(this).apply {
                    textSize = 12f
                    setTextColor(Color.WHITE)
                }
                
                tvFloatingDetails = TextView(this).apply {
                    textSize = 10f
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 8, 0, 0)
                    visibility = View.GONE
                }
                
                floatingLayout?.addView(tvFloatingSummary)
                floatingLayout?.addView(tvFloatingDetails)
                
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
                
                floatingLayout?.setOnTouchListener(object : View.OnTouchListener {
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
                                val dx = Math.abs(event.rawX - initialTouchX)
                                val dy = Math.abs(event.rawY - initialTouchY)
                                if (dx > 10 || dy > 10) {
                                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                                    try {
                                        windowManager?.updateViewLayout(floatingLayout, params)
                                    } catch (e: Exception) {}
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                val dx = Math.abs(event.rawX - initialTouchX)
                                val dy = Math.abs(event.rawY - initialTouchY)
                                if (dx < 10 && dy < 10) {
                                    isFloatingExpanded = !isFloatingExpanded
                                    updateFloatingWindowData(com.tw.perapp4grouter.stats.StatsSnapshotRepository.latest())
                                }
                                return true
                            }
                        }
                        return false
                    }
                })

                try {
                    windowManager?.addView(floatingLayout, params)
                } catch (e: Exception) {}
            }
            updateFloatingWindowData(snapshot)
        } else {
            removeFloatingWindow()
        }
    }

    private fun updateFloatingWindowData(snapshot: com.tw.perapp4grouter.stats.StatsSnapshot) {
        if (floatingLayout == null) return
        
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val isLocked = keyguardManager.isKeyguardLocked
        
        if (isLocked) {
            isFloatingExpanded = false
            tvFloatingSummary?.text = "${snapshot.activeAppCount} APP · ${snapshot.totalFlows} 連線"
            tvFloatingDetails?.visibility = View.GONE
            return
        }

        tvFloatingSummary?.text = "${snapshot.activeAppCount} APP · ${snapshot.totalFlows} 連線"
        
        if (isFloatingExpanded && !snapshot.isEmpty) {
            val sorted = snapshot.rows.sortedByDescending { it.total }
            val details = mutableListOf<String>()
            for (i in 0 until Math.min(3, sorted.size)) {
                details.add("${sorted[i].label}: ${sorted[i].total}")
            }
            tvFloatingDetails?.text = details.joinToString("\n")
            tvFloatingDetails?.visibility = View.VISIBLE
        } else {
            tvFloatingDetails?.visibility = View.GONE
        }
    }

    private fun removeFloatingWindow() {
        if (floatingLayout != null) {
            try {
                windowManager?.removeView(floatingLayout)
            } catch (e: Exception) {}
            floatingLayout = null
            tvFloatingSummary = null
            tvFloatingDetails = null
            isFloatingExpanded = false
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

        val publicVersion = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("4G Router")
            .setContentText("VPN 路由服務執行中")
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("4G Router")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_secure) // Replace with app icon later
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }
}
