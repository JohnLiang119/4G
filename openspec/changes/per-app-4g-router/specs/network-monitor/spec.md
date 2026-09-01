## Purpose

監控裝置的 WiFi 與 4G 行動網路連線狀態，透過 ConnectivityManager 主動喚醒並保持 4G 網路活躍，將即時網路狀態回報給 UI 層。

## ADDED Requirements

### Requirement: 主動喚醒 4G 行動網路
系統 SHALL 使用 `ConnectivityManager.requestNetwork()` 搭配 `NetworkCapabilities.TRANSPORT_CELLULAR` 請求系統啟動 4G 行動網路，即使裝置目前已連接 WiFi 也 SHALL 讓 4G 保持活躍。

#### Scenario: WiFi 連線中喚醒 4G
- **WHEN** 裝置已連接 WiFi 且使用者啟動 VPN 服務
- **THEN** 系統透過 requestNetwork 喚醒 4G 行動網路
- **THEN** WiFi 與 4G 同時保持活躍

#### Scenario: 4G 不可用
- **WHEN** 裝置無 SIM 卡或行動數據已關閉
- **THEN** 系統通知使用者「4G 網路不可用，請確認行動數據已開啟」

### Requirement: 監控網路狀態變化
系統 SHALL 持續監控 WiFi 與 4G 的連線狀態變化，並即時通知 UI 層更新顯示。

#### Scenario: 4G 連線中斷
- **WHEN** 4G 網路連線中斷（如進入無訊號區域）
- **THEN** 系統通知 UI 顯示「4G 已斷線」狀態
- **THEN** 被路由的 APP 暫時無法連線

#### Scenario: 4G 連線恢復
- **WHEN** 4G 網路連線恢復
- **THEN** 系統自動重新綁定 VPN 流量到 4G
- **THEN** UI 顯示「4G 已連線」狀態

### Requirement: 提供 4G Network 物件給 VPN 服務
系統 SHALL 在 4G 網路可用時，將 `Network` 物件傳遞給 VPN 路由服務，供其綁定出口 socket。

#### Scenario: 4G Network 物件就緒
- **WHEN** `onAvailable(Network)` 回呼觸發
- **THEN** 系統將 cellular Network 物件傳遞給 VPN 服務的 tun2socks 引擎
- **THEN** VPN 服務使用此 Network 物件綁定所有出口 socket
