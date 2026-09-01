## Purpose

提供使用者介面讓使用者選擇哪些 APP 要走 4G 行動網路，以及控制 VPN 服務的啟停，同時顯示即時的網路連線狀態。

## ADDED Requirements

### Requirement: APP 選擇清單
系統 SHALL 顯示裝置上所有已安裝的使用者 APP（排除系統 APP），讓使用者勾選要路由到 4G 的 APP。選擇結果 SHALL 持久化儲存（SharedPreferences），下次開啟 APP 時自動載入。

#### Scenario: 瀏覽並選擇 APP
- **WHEN** 使用者開啟 APP 選擇畫面
- **THEN** 系統顯示所有已安裝的使用者 APP，包含 APP 名稱與圖示
- **THEN** 使用者可勾選一個或多個 APP

#### Scenario: 記住上次選擇
- **WHEN** 使用者關閉 APP 後重新開啟
- **THEN** 上次勾選的 APP 仍然保持勾選狀態

### Requirement: VPN 啟停大開關
系統 SHALL 提供一個明顯的開關控制 VPN 服務的啟動與停止。

#### Scenario: 啟動 VPN
- **WHEN** 使用者按下開關（OFF → ON）且已選擇至少一個 APP
- **THEN** 系統啟動 VPN 服務，開始將選定 APP 的流量路由到 4G
- **THEN** 開關顯示為 ON 狀態

#### Scenario: 停止 VPN
- **WHEN** 使用者按下開關（ON → OFF）
- **THEN** 系統停止 VPN 服務，所有 APP 回歸使用系統預設網路
- **THEN** 開關顯示為 OFF 狀態

### Requirement: 網路狀態顯示
系統 SHALL 在主畫面顯示目前的 WiFi 與 4G 連線狀態，讓使用者一眼確認雙網路是否正常運作。

#### Scenario: 雙網路正常運作
- **WHEN** VPN 已啟動且 WiFi 與 4G 皆已連線
- **THEN** 主畫面顯示「WiFi ✅」與「4G ✅」狀態指示

#### Scenario: 4G 未連線
- **WHEN** VPN 已啟動但 4G 未連線
- **THEN** 主畫面顯示「4G ❌」狀態指示並提示使用者檢查行動數據設定
