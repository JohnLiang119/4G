## Why

目前系統只顯示全域 TCP/UDP 快取數量，無法辨識是哪個已選 APP 正在透過 4G 路由，也無法可靠表示真正仍在活動的連線。目標裝置均為 Android 10 以上，因此可使用官方連線擁有者 API，提供可對帳且不影響轉送的每 APP 作用中連線統計。

## What Changes

- 在每個新 TCP/UDP flow 首次出現時，以尚未反轉的原始五元組解析擁有者 UID，並映射成本次 VPN session 實際套用的 APP 名稱。
- 建立以 VPN session 為界的集中式連線統計，追蹤 TCP 生命週期與最近 60 秒有活動的 UDP flow。
- 在主畫面依 APP 顯示總數、TCP 與 UDP 數量；通知列與懸浮窗使用相同快照呈現精簡摘要。
- 對無法辨識的連線保留「未知 APP」分組；對 shared UID 使用單一「共用 UID」分組，避免猜測或重複計數。
- APP 選擇在 VPN 執行期間視為不可變的 applied snapshot；變更選擇後必須重建 VPN 並重設統計。
- 修正會使統計失真的 TCP 終止清理與 UDP 活動逾時行為，確保各分組加總永遠等於全域總數。
- 統計僅保留作用中 flow metadata，不記錄封包內容、目的網址、流量 bytes 或跨 session 歷史。
- **BREAKING**：最低支援版本由 Android 7（API 24）提升為 Android 10（API 29）。

## Capabilities

### New Capabilities
- `app-connection-stats`: 解析 VPN flow 的 APP 歸屬、維護每 APP 作用中 TCP/UDP 統計，並在主畫面、通知列與懸浮窗一致呈現。

### Modified Capabilities
（無 — 目前尚無已封存的基準 capabilities；相關 UI 與 VPN 行為納入新的 `app-connection-stats` capability。）

## Impact

- **Android 平台**：`minSdk` 提升至 29，使用 `ConnectivityManager.getConnectionOwnerUid()` 與 `PackageManager` UID/package 對應 API。
- **VPN 資料路徑**：調整 `Packet`、`TCPOutput`、`TCPInput`、`TCB`、`UDPOutput`、`UDPInput` 的 flow 身分與生命週期事件。
- **服務與 UI**：`CellularVpnService` 擁有統計 session，`MainActivity`、前景通知與懸浮窗改讀同一份不可變統計快照。
- **測試**：需涵蓋多 APP 同時連線、TCP 重送與終止、UDP 逾時、unknown/shared UID、VPN 重啟及 APP 選擇重建。
- **外部系統**：不新增遠端服務、Root 權限或流量上傳。
