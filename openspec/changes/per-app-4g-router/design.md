## Context

見 proposal.md — 需要在不修改公司 APP 原始碼的前提下，讓該 APP 的流量走 4G，其他 APP 走 WiFi。這是一個全新的 Android Native 專案，目標裝置為小米手機。

Android 本身支援 WiFi + 4G 雙網路同時啟用（硬體上是兩張獨立的網路卡），但系統預設在 WiFi 連線時會讓 4G 休眠。透過 `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR)` 可強制喚醒 4G，搭配 `VpnService` 的 per-app 路由能力即可實現分流。

## Goals / Non-Goals

**Goals:**
- 指定 APP 的流量透過 4G 行動網路傳輸
- 其他 APP 繼續使用 WiFi，完全不受影響
- 無需遠端 VPN 伺服器
- 無需 Root 權限
- 在小米 (MIUI/HyperOS) 上穩定運行

**Non-Goals:**
- 不做流量加密（非真正 VPN，只是路由）
- 不做流量統計或監控
- 不做 iOS 版本
- 不做自動偵測公司 APP 開關的功能（MVP 版手動開關）
- 不做多個 APP 走不同網路的場景（MVP 版所有選中 APP 統一走 4G）

## Decisions

### Decision 1: 使用 tun2socks (Go) 作為封包轉發引擎

**選擇**: [xjasonlyu/tun2socks](https://github.com/xjasonlyu/tun2socks) Go 引擎，透過 gomobile 編譯為 .aar

**理由**:
- 自己實作 TCP/IP stack（解析 IP 封包、處理 TCP 三次握手、序號管理）是極其複雜的工程
- tun2socks 使用 gVisor 的 TCP/IP stack，成熟穩定
- 支援 DIRECT 模式（不需要遠端 SOCKS 伺服器）
- 社群活躍，持續維護

**替代方案**:
- 自己寫封包轉發: ❌ TCP state machine 太複雜，MVP 不可行
- lwIP (C 語言 TCP/IP stack): ⚠️ 可行但 JNI 整合複雜
- NetGuard 方案: ⚠️ 是防火牆不是路由器，不符合需求

### Decision 2: 使用 addAllowedApplication 做 Per-App 分流

**選擇**: `VpnService.Builder.addAllowedApplication()`

**理由**:
- Android 原生 API，最乾淨的 per-app 路由方式
- 只有列入的 APP 流量進入 VPN 隧道，其他 APP 完全無感
- 不需要自己做封包過濾或 iptables 規則

**替代方案**:
- addDisallowedApplication (排除法): ⚠️ 邏輯反轉，需要排除所有不走 4G 的 APP，不實際
- iptables (需 Root): ❌ 需要 Root 權限

### Decision 3: Android Native Kotlin（非 Capacitor/Flutter）

**選擇**: 純 Android Native，Kotlin 語言

**理由**:
- `VpnService` 是 Android 平台 API，必須原生實作
- tun2socks .aar 整合需要原生 Android 專案
- Foreground Service 生命週期管理在原生最可靠
- MVP 版 UI 簡單，不需要跨平台框架

### Decision 4: SharedPreferences 儲存使用者設定

**選擇**: `SharedPreferences` 儲存選中的 APP package names

**理由**:
- MVP 版只需存一個 `Set<String>`（package names）
- 極簡、無依賴、啟動即讀取
- 未來可遷移到 DataStore 或 Room

## Risks / Trade-offs

- **[Android 只允許一個 VPN]** → 若使用者同時使用其他 VPN APP（如公司 VPN），本 APP 的 VPN 會被斷開。緩解：UI 提示使用者此限制。
- **[MIUI 電池優化殺進程]** → 小米系統可能殺掉後台 VPN 服務。緩解：引導使用者在設定中關閉電池優化、允許自啟動、鎖定最近任務。
- **[tun2socks .aar 編譯門檻]** → 需要 Go + gomobile 工具鏈。緩解：可提前編譯好 .aar 放入 libs/ 目錄。
- **[4G 額外耗電]** → 同時維持 WiFi + 4G 會增加耗電。緩解：MVP 可接受，使用者手動控制開關。
- **[tun2socks DIRECT 模式 + cellular binding]** → 需確認 tun2socks 的 DIRECT 模式能否搭配 `Network.bindSocket()` 使用。若不支援，備案是在 tun2socks 之上加一層 local SOCKS5 proxy 做 cellular binding。
