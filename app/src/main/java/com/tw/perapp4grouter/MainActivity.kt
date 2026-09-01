package com.tw.perapp4grouter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import android.app.AlertDialog
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import android.widget.ProgressBar
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var switchVpn: Switch
    private lateinit var tvStatus: TextView
    private lateinit var appsContainer: LinearLayout
    private lateinit var tvLogger: TextView
    private lateinit var loggerScrollView: ScrollView
    private lateinit var tvConnectionStats: TextView
    private lateinit var switchFloatingWindow: Switch
    
    private val allowedApps = mutableSetOf<String>()
    
    companion object {
        private const val REQUEST_VPN = 1
        private const val REQUEST_OVERLAY = 2
        const val PREFS_NAME = "VpnPrefs"
        private const val KEY_ALLOWED_APPS = "allowed_apps"
        const val PREF_SHOW_NOTIF = "pref_show_notification"
        const val PREF_SHOW_FLOAT = "pref_show_floating_window"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple programmatic UI for MVP to avoid complex XML layouts
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        tvStatus = TextView(this).apply {
            text = "WiFi: 未知 | 4G: 未知"
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }
        
        val vpnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 32)
        }

        switchVpn = Switch(this).apply {
            text = "啟動 4G 路由 VPN"
            textSize = 18f
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    startVpnProcess()
                } else {
                    stopVpnProcess()
                }
            }
        }

        tvConnectionStats = TextView(this).apply {
            text = "TCP: 0/0 (0%) | UDP: 0/0 (0%)"
            textSize = 12f
            setTextColor(Color.parseColor("#BB86FC"))
            setPadding(32, 8, 0, 0)
        }

        vpnRow.addView(switchVpn)
        vpnRow.addView(tvConnectionStats)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val switchNotification = Switch(this).apply {
            text = "顯示下拉通知列統計"
            textSize = 14f
            isChecked = prefs.getBoolean(PREF_SHOW_NOTIF, true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_SHOW_NOTIF, isChecked).apply()
            }
        }

        switchFloatingWindow = Switch(this).apply {
            text = "顯示懸浮視窗統計"
            textSize = 14f
            setPadding(0, 16, 0, 32)
            isChecked = prefs.getBoolean(PREF_SHOW_FLOAT, false)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    this.isChecked = false
                    Toast.makeText(this@MainActivity, "請允許「顯示在其他應用程式上層」權限", Toast.LENGTH_LONG).show()
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                    startActivityForResult(intent, REQUEST_OVERLAY)
                } else {
                    prefs.edit().putBoolean(PREF_SHOW_FLOAT, isChecked).apply()
                }
            }
        }
        
        val titleApps = TextView(this).apply {
            text = "請選擇要走 4G 的 APP："
            textSize = 16f
            setPadding(0, 32, 0, 16)
        }
        
        val appsScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f // Weight 1 (takes half remaining space)
            )
        }
        appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        appsScrollView.addView(appsContainer)

        val titleLog = TextView(this).apply {
            text = "─── 底層狀態監控 (Debug) ───"
            textSize = 16f
            setPadding(0, 32, 0, 16)
        }

        loggerScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f // Weight 1 (takes half remaining space)
            )
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        tvLogger = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#3DDC84"))
            setPadding(16, 16, 16, 16)
        }
        loggerScrollView.addView(tvLogger)
        
        rootLayout.addView(tvStatus)
        rootLayout.addView(vpnRow)
        rootLayout.addView(switchNotification)
        rootLayout.addView(switchFloatingWindow)
        rootLayout.addView(titleApps)
        rootLayout.addView(appsScrollView)
        rootLayout.addView(titleLog)
        rootLayout.addView(loggerScrollView)
        
        setContentView(rootLayout)
        
        val savedApps = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet())
        if (savedApps != null) {
            allowedApps.addAll(savedApps)
        }
        
        loadInstalledApps()
        checkXiaomiMIUI()
        checkForUpdates()
    }

    private fun checkXiaomiMIUI() {
        if ("xiaomi".equals(android.os.Build.MANUFACTURER, ignoreCase = true)) {
            val hasShown = prefs.getBoolean("miui_prompt_shown", false)
            if (!hasShown) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("小米手機特別設定")
                    .setMessage("為了讓 VPN 穩定運作，請在設定中：\n1. 允許此 APP 「自啟動」\n2. 電池最佳化設定為「無限制」\n3. 在最近任務中將此 APP 上鎖")
                    .setPositiveButton("我知道了") { _, _ ->
                        prefs.edit().putBoolean("miui_prompt_shown", true).apply()
                    }
                    .show()
            }
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        
        for (packageInfo in packages) {
            // 排除系統 APP
            if (packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                val appName = packageInfo.applicationInfo.loadLabel(pm).toString()
                val pkgName = packageInfo.packageName
                
                val checkBox = CheckBox(this).apply {
                    text = "$appName ($pkgName)"
                    isChecked = allowedApps.contains(pkgName)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            allowedApps.add(pkgName)
                        } else {
                            allowedApps.remove(pkgName)
                        }
                        saveAllowedApps()
                    }
                }
                appsContainer.addView(checkBox)
            }
        }
    }

    private fun saveAllowedApps() {
        prefs.edit().putStringSet(KEY_ALLOWED_APPS, allowedApps).apply()
    }

    private fun startVpnProcess() {
        if (allowedApps.isEmpty()) {
            Toast.makeText(this, "請至少選擇一個 APP", Toast.LENGTH_SHORT).show()
            switchVpn.isChecked = false
            return
        }

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQUEST_VPN)
        } else {
            onActivityResult(REQUEST_VPN, Activity.RESULT_OK, null)
        }
    }

    private fun stopVpnProcess() {
        val stopIntent = Intent(this, CellularVpnService::class.java).apply {
            action = CellularVpnService.ACTION_STOP
        }
        startService(stopIntent)
        tvStatus.text = "VPN 已停止"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == Activity.RESULT_OK) {
                val startIntent = Intent(this, CellularVpnService::class.java).apply {
                    action = CellularVpnService.ACTION_START
                    putStringArrayListExtra(CellularVpnService.EXTRA_ALLOWED_APPS, ArrayList(allowedApps))
                }
                startService(startIntent)
                tvStatus.text = "VPN 執行中 (監控網路狀態...)"
            } else {
                Toast.makeText(this, "需要 VPN 權限才能運作", Toast.LENGTH_SHORT).show()
                switchVpn.isChecked = false
            }
        } else if (requestCode == REQUEST_OVERLAY) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                switchFloatingWindow.isChecked = true
                prefs.edit().putBoolean(PREF_SHOW_FLOAT, true).apply()
            }
        }
    }

    private val logListener: (String) -> Unit = { log ->
        tvLogger.append(log + "\n")
        loggerScrollView.post {
            loggerScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            try {
                val tcpCount = com.tw.perapp4grouter.localvpn.TCB.getActiveTCBCount()
                val tcpMax = com.tw.perapp4grouter.localvpn.TCB.getMaxTCBCount()
                val udpCount = com.tw.perapp4grouter.localvpn.UDPOutput.getActiveUDPCount()
                val udpMax = com.tw.perapp4grouter.localvpn.UDPOutput.getMaxUDPCount()
                
                val tcpRatio = if (tcpMax > 0) (tcpCount.toFloat() / tcpMax) * 100 else 0f
                val udpRatio = if (udpMax > 0) (udpCount.toFloat() / udpMax) * 100 else 0f
                
                tvConnectionStats.text = String.format("TCP: %d/%d (%.1f%%) | UDP: %d/%d (%.1f%%)", tcpCount, tcpMax, tcpRatio, udpCount, udpMax, udpRatio)
            } catch (e: Exception) {
            }
            tvConnectionStats.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.addListener(logListener)
        tvConnectionStats.post(statsRunnable)
    }

    override fun onPause() {
        super.onPause()
        AppLogger.removeListener(logListener)
        tvConnectionStats.removeCallbacks(statsRunnable)
    }

    private fun checkForUpdates() {
        Thread {
            try {
                val url = URL("https://api.github.com/repos/JohnLiang119/4G/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()
                    
                    val json = JSONObject(response)
                    val tagName = json.getString("tag_name")
                    val assets = json.getJSONArray("assets")
                    if (assets.length() > 0) {
                        val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
                        
                        val packageInfo = packageManager.getPackageInfo(packageName, 0)
                        val currentVersion = "v" + packageInfo.versionName
                        
                        // Compare versions naively: if tagName != currentVersion, prompt update.
                        if (tagName != currentVersion) {
                            runOnUiThread {
                                showUpdateDialog(tagName, downloadUrl)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("UpdateCheck", "Failed to check for updates", e)
            }
        }.start()
    }

    private fun showUpdateDialog(newVersion: String, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("發現新版本")
            .setMessage("最新版本為 $newVersion，是否要立即更新？")
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstallApk(downloadUrl, newVersion)
            }
            .setNegativeButton("稍後再說", null)
            .show()
    }

    private fun downloadAndInstallApk(apkUrl: String, version: String) {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(32, 32, 32, 0)
        }
        val tvProgress = TextView(this).apply {
            text = "0%"
            setPadding(32, 16, 32, 32)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progressBar)
            addView(tvProgress)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("下載更新中 ($version)")
            .setView(layout)
            .setCancelable(false)
            .show()

        Thread {
            var input: InputStream? = null
            var output: FileOutputStream? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL(apkUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode}")
                }

                val fileLength = connection.contentLength
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
                if (file.exists()) {
                    file.delete()
                }

                input = connection.inputStream
                output = FileOutputStream(file)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                
                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        runOnUiThread {
                            progressBar.progress = progress
                            tvProgress.text = "$progress%"
                        }
                    }
                    output.write(data, 0, count)
                }

                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "下載完成！", Toast.LENGTH_SHORT).show()
                    installApk()
                }
            } catch (e: Exception) {
                AppLogger.e("UpdateCheck", "Failed to download APK", e)
                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "下載失敗：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                try {
                    output?.close()
                    input?.close()
                } catch (ignored: Exception) {}
                connection?.disconnect()
            }
        }.start()
    }

    private fun installApk() {
        try {
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "安裝失敗，請手動開啟 APK", Toast.LENGTH_LONG).show()
            AppLogger.e("UpdateCheck", "Failed to install APK", e)
        }
    }
}
