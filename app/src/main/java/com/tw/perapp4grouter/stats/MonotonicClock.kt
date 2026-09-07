package com.tw.perapp4grouter.stats

/**
 * 可注入的單調時鐘。使用單調來源而非牆鐘時間，讓 UDP 60 秒逾時不受系統時間調整影響，
 * 並且可以在單元測試中以假時鐘直接推進時間而不需要真的等待。
 */
interface MonotonicClock {
    fun elapsedMillis(): Long
}

/** 正式環境使用的單調時鐘，以 [System.nanoTime] 為基準。 */
object SystemMonotonicClock : MonotonicClock {
    override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L
}
