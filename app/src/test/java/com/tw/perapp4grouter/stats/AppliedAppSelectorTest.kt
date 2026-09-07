package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驗證 APP 選擇在 establish() 之前的驗證與套用行為。
 */
class AppliedAppSelectorTest {

    private class FakeResolver(private val installed: Map<String, AppliedApp>) : AppliedPackageResolver {
        override fun resolve(packageName: String): AppliedApp? = installed[packageName]
    }

    private class RecordingSink(private val rejected: Set<String> = emptySet()) : AllowedApplicationSink {
        val added = ArrayList<String>()
        val attempted = ArrayList<String>()

        override fun addAllowedApplication(packageName: String): Boolean {
            attempted.add(packageName)
            if (packageName in rejected) return false
            added.add(packageName)
            return true
        }
    }

    private val appA = AppliedApp("com.example.a", 10101, "APP A")
    private val appB = AppliedApp("com.example.b", 10102, "APP B")

    @Test
    fun validatedPackagesAreAddedToTheBuilder() {
        val sink = RecordingSink()
        val applied = AppliedAppSelector.apply(
            listOf("com.example.a", "com.example.b"),
            FakeResolver(mapOf("com.example.a" to appA, "com.example.b" to appB)),
            sink
        )
        assertEquals(listOf(appA, appB), applied)
        assertEquals(listOf("com.example.a", "com.example.b"), sink.added)
    }

    @Test
    fun emptySelectionYieldsNoAppliedAppsAndNeverTouchesTheBuilder() {
        val sink = RecordingSink()
        val applied = AppliedAppSelector.apply(emptyList(), FakeResolver(emptyMap()), sink)
        assertTrue("空選擇必須產生空結果，讓呼叫端中止建立 VPN", applied.isEmpty())
        assertTrue("不得把任何 APP 加入允許清單", sink.attempted.isEmpty())
    }

    @Test
    fun anAppUninstalledJustBeforeStartIsNotAddedToTheBuilder() {
        val sink = RecordingSink()
        // 唯一被選取的 APP 在啟動前已被解除安裝
        val applied = AppliedAppSelector.apply(
            listOf("com.example.a"),
            FakeResolver(emptyMap()),
            sink
        )
        assertTrue("結果為空，呼叫端必須中止而非退化成全部 APP", applied.isEmpty())
        assertTrue("已解除安裝的 package 不得被加入允許清單", sink.attempted.isEmpty())
    }

    @Test
    fun onlyPackagesAcceptedByTheBuilderAreRetained() {
        val sink = RecordingSink(rejected = setOf("com.example.b"))
        val applied = AppliedAppSelector.apply(
            listOf("com.example.a", "com.example.b"),
            FakeResolver(mapOf("com.example.a" to appA, "com.example.b" to appB)),
            sink
        )
        assertEquals("builder 拒絕的 package 不得列入已套用清單", listOf(appA), applied)
    }

    @Test
    fun aMixOfInstalledAndMissingPackagesKeepsOnlyTheInstalledOnes() {
        val sink = RecordingSink()
        val applied = AppliedAppSelector.apply(
            listOf("com.example.a", "com.example.gone"),
            FakeResolver(mapOf("com.example.a" to appA)),
            sink
        )
        assertEquals(listOf(appA), applied)
        assertEquals(listOf("com.example.a"), sink.added)
    }

    @Test
    fun duplicateAndBlankSelectionsAreIgnored() {
        val sink = RecordingSink()
        val applied = AppliedAppSelector.apply(
            listOf("com.example.a", "com.example.a", "", "   "),
            FakeResolver(mapOf("com.example.a" to appA)),
            sink
        )
        assertEquals(listOf(appA), applied)
        assertEquals(1, sink.attempted.size)
    }

    @Test
    fun anEmptyResultBuildsAnEmptyIndexThatResolvesNothing() {
        val index = AppliedAppIndex.build(emptyList())
        assertTrue(index.isEmpty)
        assertEquals(AppIdentity.Unknown, index.identityFor(10101))
    }
}
