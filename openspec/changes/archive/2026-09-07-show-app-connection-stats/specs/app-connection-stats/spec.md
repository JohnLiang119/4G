## Purpose

提供 Android 10 以上多 APP VPN session 的即時連線可觀測性，讓使用者能辨識哪些已套用的 APP 正透過路由服務活動，以及各 APP 的 TCP、UDP 與合計作用中 flow 數量，同時維持統計一致性、隱私與封包轉送可靠性。

## ADDED Requirements

### Requirement: Android 10 以上平台基準
系統 MUST 將 Android 10（API 29）設為最低支援版本，所有受支援裝置 SHALL 使用相同的每 flow APP 歸屬行為。

#### Scenario: 在 Android 10 以上執行
- **WHEN** 使用者在 Android 10（API 29）或更新版本啟動 VPN
- **THEN** 系統 SHALL 啟用每 APP 作用中連線統計

#### Scenario: 在舊版 Android 安裝
- **WHEN** 使用者嘗試在 Android 9（API 28）或更舊版本安裝此版本
- **THEN** 系統 MUST 將該裝置視為不相容，而非提供不可靠的 APP 歸屬 fallback

### Requirement: VPN flow 的 APP 歸屬
系統 SHALL 將每個由本次 VPN session 攔截的新 TCP 或 UDP flow 歸屬至其來源 UID，並以該 session 實際套用的 APP 身分顯示。歸屬 SHALL 使用 APP 原始來源端點與目的端點，不得將 LocalVPN 自身的出口連線誤認為來源 APP。

#### Scenario: 兩個 APP 同時建立連線
- **WHEN** 已套用且 UID 不同的 APP A 與 APP B 分別建立兩條及一條不同 TCP flow
- **THEN** 系統 SHALL 顯示 APP A 為 2、APP B 為 1，且全域總數為 3

#### Scenario: 同一個 APP 建立 TCP 與 UDP flow
- **WHEN** 同一個已套用 APP 同時具有兩條 TCP flow 與三條作用中 UDP flow
- **THEN** 系統 SHALL 將它們聚合在同一 APP 下並顯示 TCP 2、UDP 3、合計 5

#### Scenario: 不得歸屬至路由服務本身
- **WHEN** LocalVPN 為來源 APP 建立受保護的出口 socket
- **THEN** 系統 SHALL 保留原始 flow 的 APP 歸屬，且 MUST NOT 將該 flow 顯示為 4G Router 本身

#### Scenario: 非 TCP 或 UDP 流量
- **WHEN** VPN 收到不屬於 TCP 或 UDP 的封包
- **THEN** 系統 SHALL 排除該封包於連線統計之外，且不得因此改變任何 APP 的數量

### Requirement: 無法唯一辨識的 APP 歸屬
系統 SHALL 保留所有無法唯一映射至單一 APP 的作用中 flow，使全域總數仍可完整對帳。系統 MUST NOT 猜測、拆分或重複計入這些 flow。

#### Scenario: 找不到連線擁有者
- **WHEN** 系統無法取得某個新 flow 的有效來源 UID
- **THEN** 系統 SHALL 將該 flow 計入「未知 APP」分組
- **THEN** 統計失敗 MUST NOT 中斷或延遲該 flow 的正常封包轉送

#### Scenario: UID 沒有可見 package
- **WHEN** 系統取得有效 UID，但無法取得對應 package 或顯示名稱
- **THEN** 系統 SHALL 將該 flow 計入「未知 APP」分組

#### Scenario: 多個 package 共用 UID
- **WHEN** 一個 UID 對應 APP A 與 APP B，且該 UID 有三條作用中 flow
- **THEN** 系統 SHALL 顯示單一「共用 UID：APP A / APP B」分組且數量為 3
- **THEN** 系統 MUST NOT 將三條 flow 同時複製到 APP A 與 APP B，或任意分配給其中之一

### Requirement: TCP 作用中 flow 計數
系統 SHALL 將從首次 outbound SYN 建立至終止事件完成的每個唯一 TCP flow 計為一條作用中連線。重送或後續封包 MUST NOT 重複增加數量，且同一 flow 的終止事件 MUST NOT 重複扣除數量。

#### Scenario: TCP flow 開始建立
- **WHEN** 已套用 APP 發出某個新五元組的首次 TCP SYN
- **THEN** 系統 SHALL 將該 flow 計為一條作用中 TCP 連線，包括仍在建立中的狀態

#### Scenario: TCP SYN 重送與資料封包
- **WHEN** 已計數的 TCP flow 收到相同五元組的 SYN 重送、ACK 或資料封包
- **THEN** 系統 SHALL 維持該 flow 的計數為 1

#### Scenario: TCP flow 正常終止
- **WHEN** TCP flow 完成雙向關閉
- **THEN** 系統 SHALL 將該 flow 從作用中統計移除一次

#### Scenario: TCP flow 異常終止
- **WHEN** TCP flow 發生連線失敗、RST、channel 錯誤或其他終止性錯誤
- **THEN** 系統 SHALL 將該 flow 從作用中統計移除一次

#### Scenario: TCP 單邊關閉
- **WHEN** TCP flow 僅觀察到單邊 FIN 且仍未進入終止狀態
- **THEN** 系統 SHALL 保留該 flow 於作用中統計，直到完整關閉或發生終止性錯誤

### Requirement: UDP 作用中 flow 計數
系統 SHALL 將相同 UID、協定、來源端點與目的端點的 UDP datagram 聚合為一條 UDP flow。UDP flow SHALL 在任一方向最後活動後保留 60 秒，逾時、channel 錯誤或 VPN 停止時 SHALL 從作用中統計移除。

#### Scenario: 建立 UDP flow
- **WHEN** 已套用 APP 對一個新目的端點送出首個 UDP datagram
- **THEN** 系統 SHALL 將該五元組計為一條作用中 UDP flow

#### Scenario: 相同 UDP flow 持續活動
- **WHEN** 已計數 UDP flow 在 60 秒內傳送或接收更多 datagram
- **THEN** 系統 SHALL 維持計數為 1 並刷新最後活動時間

#### Scenario: 不同 UDP 目的端點
- **WHEN** 同一 APP 與來源端點對兩個不同目的端點傳送 UDP datagram
- **THEN** 系統 SHALL 將其計為兩條不同 UDP flow

#### Scenario: UDP flow 閒置逾時
- **WHEN** UDP flow 自最後一次任一方向活動起滿 60 秒
- **THEN** 系統 SHALL 在下一次統計更新時將該 flow 從作用中統計移除

### Requirement: 統計一致性與 VPN session 隔離
系統 SHALL 以每次成功建立的 VPN session 隔離作用中 flow，並發布單一一致的統計快照。每個分組的合計 SHALL 等於其 TCP 與 UDP 數量相加，全域總數 SHALL 等於所有 APP、共用 UID 與未知 APP 分組的加總，且所有數值 MUST NOT 小於零。

#### Scenario: VPN 停止與重新啟動
- **WHEN** 使用者停止具有作用中 flow 的 VPN，之後重新建立 VPN
- **THEN** 舊 session 的所有統計 SHALL 被清除，新 session SHALL 從零開始

#### Scenario: 舊 session 的延遲事件
- **WHEN** 新 VPN session 建立後才收到舊 session worker 的延遲關閉或解析結果
- **THEN** 系統 SHALL 忽略該事件，且新 session 的統計 MUST NOT 被扣減或重新歸屬

#### Scenario: Activity 重新建立
- **WHEN** Activity 因旋轉、離開後返回或系統重建而重新連接仍在執行的 VPN service
- **THEN** 畫面 SHALL 顯示目前 session 的既有統計，而非將數量清零

#### Scenario: 併發建立與關閉 flow
- **WHEN** 多個 TCP 與 UDP worker 同時建立、更新及關閉 flow
- **THEN** 每個發布的快照 SHALL 維持分組與全域加總一致，且所有數量 MUST NOT 小於零

### Requirement: 已套用 APP 清單的一致性
系統 SHALL 以成功建立 VPN 時實際套用的 APP 清單作為該 session 的不可變身分來源。執行期間的選擇變更 MUST NOT 重新標示既有 flow；套用新選擇 SHALL 重建 VPN 並建立新的空白統計 session。

#### Scenario: 執行期間修改 APP 選擇
- **WHEN** VPN 已套用 APP A 與 APP B，而使用者將草稿選擇改成 APP A 與 APP C 但尚未重新套用
- **THEN** 路由與統計 SHALL 繼續使用 APP A 與 APP B

#### Scenario: 套用新的 APP 選擇
- **WHEN** 使用者成功套用 APP A 與 APP C 的新選擇
- **THEN** 系統 SHALL 重建 VPN、將統計歸零，並只使用 APP A 與 APP C 作為新 session 的已套用清單

#### Scenario: 沒有選擇任何 APP
- **WHEN** 使用者嘗試以空的 APP 清單啟動或重新套用 VPN
- **THEN** 系統 MUST 阻止建立 VPN 並提示至少選擇一個 APP
- **THEN** 系統 MUST NOT 退化成將所有 APP 加入 VPN

#### Scenario: 選擇的 APP 在套用前已移除
- **WHEN** 唯一選擇的 APP 在 VPN 建立前已從裝置解除安裝
- **THEN** 系統 MUST 阻止建立空 allowed list 的 VPN 並顯示無可套用 APP 的錯誤

### Requirement: 每 APP 統計呈現
系統 SHALL 在主畫面、前景服務通知與懸浮窗使用同一統計快照。主畫面 SHALL 提供所有作用中分組的 APP 名稱、TCP、UDP 與合計；空狀態 SHALL 明確表示目前沒有作用中連線。

#### Scenario: 主畫面顯示多 APP 統計
- **WHEN** 三個 APP 分別具有 9、4 與 2 條作用中 flow
- **THEN** 主畫面 SHALL 顯示「3 個 APP、15 條作用中連線」及三個 APP 各自的 TCP、UDP與合計

#### Scenario: 沒有作用中連線
- **WHEN** VPN 正在執行但目前沒有任何作用中 flow
- **THEN** 主畫面、通知與懸浮窗 SHALL 顯示「尚無作用中連線」，且不得將僅被選取但閒置的 APP 算為作用中 APP

#### Scenario: 通知列摘要
- **WHEN** 超過兩個 APP 具有作用中 flow
- **THEN** 通知 SHALL 顯示 APP 數、全域總數、前兩個 APP 名稱與數量，以及其餘 APP 的數量摘要

#### Scenario: 懸浮窗摘要與展開
- **WHEN** 多個 APP 具有作用中 flow
- **THEN** 收合的懸浮窗 SHALL 顯示 APP 數與全域總數
- **THEN** 使用者展開懸浮窗時 SHALL 看見最多三個作用中 APP 的名稱與數量，以及其餘 APP 數量摘要

#### Scenario: 統計更新頻率
- **WHEN** 作用中 flow 數量發生變化
- **THEN** 主畫面與懸浮窗 SHALL 在 1 秒內取得更新
- **THEN** 通知 SHALL 在 5 秒內取得更新，且更新 MUST NOT 重複發聲或震動

### Requirement: 統計隱私與資料最小化
系統 SHALL 僅在記憶體中保留產生目前統計所需的 flow metadata，且 MUST NOT 保存或顯示封包內容、目的網址、流量 bytes 或跨 VPN session 的連線歷史。鎖定畫面 MUST NOT 預設揭露 APP 名稱或連線數。

#### Scenario: 裝置鎖定時顯示通知
- **WHEN** 裝置鎖定且 VPN 前景通知存在
- **THEN** 鎖定畫面 SHALL 只顯示一般性的「4G 路由已啟用」內容，不顯示 APP 名稱或連線數

#### Scenario: 裝置鎖定時已有懸浮窗
- **WHEN** 裝置從解鎖狀態轉為鎖定
- **THEN** 系統 SHALL 隱藏包含 APP 名稱的懸浮窗內容

#### Scenario: VPN session 結束
- **WHEN** VPN session 停止
- **THEN** 系統 SHALL 清除該 session 的 APP 身分與 flow metadata，且不得保存可供下次 session 查閱的歷史
