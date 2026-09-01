## 1. 環境搭建與專案初始化

- [x] 1.1 在 `4G/` 目錄下建立 Android Native 專案（Kotlin, minSdk 24, targetSdk 34）
- [x] 1.2 設定 `AndroidManifest.xml` — 宣告 VpnService、BIND_VPN_SERVICE、ACCESS_NETWORK_STATE、CHANGE_NETWORK_STATE、INTERNET、FOREGROUND_SERVICE、POST_NOTIFICATIONS 權限
- [x] 1.3 安裝 Go + gomobile 工具鏈，編譯 tun2socks 為 .aar 並放入 `app/libs/`
- [x] 1.4 在 `build.gradle.kts` 中加入 tun2socks .aar 依賴

## 2. 網路監控模組 (NetworkMonitor)

- [x] 2.1 實作 `NetworkMonitor` 類別 — 使用 `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR)` 喚醒 4G 網路
- [x] 2.2 實作 `NetworkCallback` — 在 `onAvailable` / `onLost` 中追蹤 4G Network 物件與連線狀態
- [x] 2.3 實作 WiFi 連線狀態監控 — 偵測 WiFi 是否可用
- [x] 2.4 提供 `getCellularNetwork(): Network?` 方法供 VPN 服務取用

## 3. VPN 路由核心 (CellularVpnService)

- [x] 3.1 建立 `CellularVpnService` 繼承 `VpnService` — 實作 `onCreate` / `onStartCommand` / `onDestroy` 生命週期
- [x] 3.2 實作 VPN Builder 設定 — `addAddress`、`addRoute`、`addDnsServer`、`addAllowedApplication`、`setUnderlyingNetworks`
- [x] 3.3 整合 tun2socks 引擎 — 將 TUN FileDescriptor 傳給 tun2socks，啟動封包轉發
- [x] 3.4 實作 socket protect 邏輯 — 確保 tun2socks 的出口 socket 呼叫 `protect()` 不被 VPN 攔截
- [x] 3.5 實作 4G Network binding — 將 tun2socks 出口 socket 綁定到 cellular Network 物件
- [x] 3.6 實作 Foreground Service — 建立常駐通知，顯示 VPN 狀態，點擊返回主畫面

## 4. APP 選擇與 UI (MainActivity)

- [x] 4.1 建立 `MainActivity` 主畫面 — 大開關 (Switch) + 網路狀態指示 (WiFi ✅ / 4G ✅)
- [x] 4.2 實作已安裝 APP 列表 — 使用 `PackageManager` 取得使用者 APP 清單（排除系統 APP），顯示名稱與圖示
- [x] 4.3 實作 APP 勾選邏輯 — CheckBox 勾選，結果存入 SharedPreferences
- [x] 4.4 實作 VPN 啟動流程 — 按下開關 → `VpnService.prepare()` 取得授權 → 啟動 CellularVpnService
- [x] 4.5 實作 VPN 停止流程 — 按下開關 → 停止 CellularVpnService → 清除狀態

## 5. 小米適配與測試

- [x] 5.1 新增 MIUI 適配引導 — 偵測小米裝置，提示使用者關閉電池優化、允許自啟動
- [ ] 5.2 小米真機測試 — 驗證 VPN 啟停、4G 喚醒、Per-App 路由、WiFi 不受影響
- [ ] 5.3 邊界情境測試 — 4G 斷線/恢復、WiFi 斷線/恢復、APP 選擇變更後重啟 VPN
