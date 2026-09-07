package com.tw.perapp4grouter

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 儀器化測試環境的煙霧測試，確認 androidTest source set 可被 connectedDebugAndroidTest 探索到，
 * 並驗證受測裝置符合 Android 10（API 29）平台基準。
 */
@RunWith(AndroidJUnit4::class)
class SmokeInstrumentationTest {

    @Test
    fun instrumentationRunsAgainstApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.tw.perapp4grouter", context.packageName)
    }

    @Test
    fun deviceMeetsAndroid10Baseline() {
        assertTrue(
            "受測裝置必須為 Android 10（API 29）或更新版本",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        )
    }
}
