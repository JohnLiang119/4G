package com.tw.perapp4grouter.stats

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 行程內唯一的統計快照發布點。
 *
 * 快照本身是不可變物件，且以 [AtomicReference] 整份替換，
 * 因此讀者永遠只會看到某一次發布的完整內容，不會讀到半更新狀態。
 */
object StatsSnapshotRepository {

    private val current = AtomicReference(StatsSnapshot.NONE)
    private val listeners = CopyOnWriteArrayList<(StatsSnapshot) -> Unit>()

    /** 取得最新一份完整快照。 */
    fun latest(): StatsSnapshot = current.get()

    /** 整份替換為新的快照並通知監聽者。 */
    fun publish(snapshot: StatsSnapshot) {
        current.set(snapshot)
        for (listener in listeners) {
            try {
                listener(snapshot)
            } catch (e: Exception) {
                // 單一監聽者失敗不得影響其他監聽者或封包轉送
            }
        }
    }

    /** VPN 停止時清空快照，不保留跨 session 歷史。 */
    fun clear() {
        publish(StatsSnapshot.NONE)
    }

    fun addListener(listener: (StatsSnapshot) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (StatsSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
