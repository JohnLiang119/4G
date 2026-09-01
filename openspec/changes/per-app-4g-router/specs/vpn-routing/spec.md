## Purpose

本地 VPN 路由服務核心，負責建立 TUN 虛擬網路介面、攔截指定 APP 的網路流量，並透過 tun2socks 引擎將流量轉發到 4G 行動網路，實現 Per-App 雙網路分流。

## ADDED Requirements

### Requirement: 建立本地 VPN 隧道
系統 SHALL 使用 Android `VpnService` API 建立一個本地 VPN 隧道（TUN 介面），不連接任何遠端 VPN 伺服器。VPN 隧道建立前 SHALL 透過 `VpnService.prepare()` 取得使用者授權。

#### Scenario: 首次啟動 VPN
- **WHEN** 使用者按下啟動開關且系統尚未授權 VPN
- **THEN** 系統彈出 Android VPN 授權對話框，使用者同意後建立 TUN 介面

#### Scenario: 已授權後啟動 VPN
- **WHEN** 使用者按下啟動開關且系統已授權
- **THEN** 系統直接建立 TUN 介面並開始攔截流量

### Requirement: 僅攔截指定 APP 的流量
系統 SHALL 使用 `VpnService.Builder.addAllowedApplication()` 僅攔截使用者選定的 APP 流量，其他 APP 的流量 SHALL 完全不經過 VPN，繼續使用系統預設網路（WiFi）。

#### Scenario: 指定一個 APP 走 4G
- **WHEN** 使用者選擇公司 APP (package name) 並啟動 VPN
- **THEN** 僅該 APP 的網路流量被路由到 VPN 隧道
- **THEN** 其他 APP（如 Chrome、Line）繼續使用 WiFi，完全不受影響

#### Scenario: 未選擇任何 APP
- **WHEN** 使用者未選擇任何 APP 就嘗試啟動 VPN
- **THEN** 系統提示使用者至少選擇一個 APP

### Requirement: 透過 tun2socks 引擎轉發流量到 4G
系統 SHALL 使用 tun2socks 引擎讀取 TUN 介面的原始 IP 封包，並透過綁定到 4G 行動網路的 socket 將流量轉發到目的伺服器。所有出口 socket SHALL 呼叫 `VpnService.protect()` 防止流量迴圈。

#### Scenario: TCP 流量轉發
- **WHEN** 被攔截的 APP 發起 TCP 連線（如 HTTP/HTTPS 請求）
- **THEN** tun2socks 引擎透過 4G 網路建立 TCP 連線並轉發資料
- **THEN** 回應資料透過 TUN 介面回傳給 APP

#### Scenario: UDP 流量轉發（含 DNS）
- **WHEN** 被攔截的 APP 發起 UDP 封包（如 DNS 查詢）
- **THEN** tun2socks 引擎透過 4G 網路轉發 UDP 封包

### Requirement: 前景服務保持運作
系統 SHALL 以 Android 前景服務 (Foreground Service) 運行 VPN，在通知列顯示常駐通知，防止系統殺掉服務。

#### Scenario: VPN 運行中顯示通知
- **WHEN** VPN 服務啟動後
- **THEN** 通知列顯示常駐通知，包含目前狀態（已連線 / 轉發中）
- **THEN** 點擊通知可返回 APP 主畫面
