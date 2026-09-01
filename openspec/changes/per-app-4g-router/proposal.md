## Why

公司有一個 APP 必須走 4G 行動數據連線（無法改其原始碼），但使用者在工廠/辦公室通常連著 WiFi。Android 預設在 WiFi 連線時會讓 4G 休眠，導致公司 APP 無法正常存取公司伺服器。

需要一個獨立的 Android APP，透過 VPN 機制將指定 APP 的網路流量路由到 4G 行動網路，同時讓其他 APP 繼續使用 WiFi，實現真正的雙網路同時運作。

## What Changes

- 建立一個全新的 Android 原生 APP（Kotlin），作為 Per-App 4G 路由工具
- 使用 `VpnService` API 建立本地 VPN，僅攔截指定 APP 的流量
- 使用 `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR)` 喚醒並保持 4G 網路活躍
- 透過 LocalVPN (純 Java 實作的 TCP/IP 封包轉發引擎) 將攔截的流量轉發到 4G 網路
- 提供簡易 UI：大開關 + APP 選擇器 + 網路狀態與即時 Log 顯示看板
- 不需要遠端 VPN 伺服器，一切在本機完成
- 不需要 Root 權限

## Capabilities

### New Capabilities
- `vpn-routing`: 本地 VPN 服務核心 — 建立 TUN 介面、攔截指定 APP 流量、透過 tun2socks 引擎轉發到 4G 網路
- `network-monitor`: 網路狀態監控 — 偵測 4G/WiFi 連線狀態、透過 requestNetwork 喚醒 4G、狀態回報給 UI
- `app-selector-ui`: APP 選擇介面 — 列出已安裝 APP、讓使用者勾選要走 4G 的 APP、大開關控制 VPN 啟停

### Modified Capabilities
（無 — 這是全新專案）

## Impact

- **新專案**：在 `4G/` 目錄下建立全新 Android Native 專案（Kotlin）
- **依賴**：tun2socks .aar（Go 編譯的封包轉發引擎）
- **權限需求**：`BIND_VPN_SERVICE`、`ACCESS_NETWORK_STATE`、`CHANGE_NETWORK_STATE`、`INTERNET`、`FOREGROUND_SERVICE`、`POST_NOTIFICATIONS`
- **目標平台**：Android 7+ (API 24+)，主要在小米手機 (MIUI/HyperOS) 上使用
- **MIUI 特殊處理**：需引導使用者關閉電池優化、允許自啟動，避免服務被系統殺掉
