package com.tw.perapp4grouter.stats

/**
 * 測試用假時鐘，可直接推進時間而不需要真的等待，讓 60 秒逾時邊界能被精確驗證。
 */
class FakeMonotonicClock(private var nowMillis: Long = 0L) : MonotonicClock {

    override fun elapsedMillis(): Long = nowMillis

    fun advanceBy(millis: Long) {
        nowMillis += millis
    }

    fun setTo(millis: Long) {
        nowMillis = millis
    }
}
