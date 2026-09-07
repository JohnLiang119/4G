package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驗證 APP 歸屬結果的分組鍵值與顯示名稱。
 */
class AppIdentityTest {

    @Test
    fun resolvedIdentityGroupsByUidAndPackage() {
        val a = AppIdentity.Resolved(10123, "com.example.a", "APP A")
        val b = AppIdentity.Resolved(10123, "com.example.a", "APP A")
        assertEquals(a.groupKey, b.groupKey)
        assertEquals("APP A", a.displayLabel)
        assertEquals(listOf("com.example.a"), a.packageNames)
    }

    @Test
    fun differentPackagesAreDifferentGroups() {
        val a = AppIdentity.Resolved(10123, "com.example.a", "APP A")
        val b = AppIdentity.Resolved(10124, "com.example.b", "APP B")
        assertNotEquals(a.groupKey, b.groupKey)
    }

    @Test
    fun sharedUidCollapsesIntoOneGroupWithAllLabels() {
        val shared = AppIdentity.SharedUid(
            uid = 10500,
            packageNames = listOf("com.example.a", "com.example.b"),
            labels = listOf("APP A", "APP B")
        )
        assertEquals("shared:10500", shared.groupKey)
        assertTrue(shared.displayLabel.contains("APP A"))
        assertTrue(shared.displayLabel.contains("APP B"))
        assertEquals(2, shared.packageNames.size)
    }

    @Test
    fun unknownIdentityIsASingletonGroup() {
        assertEquals("unknown", AppIdentity.Unknown.groupKey)
        assertEquals("未知 APP", AppIdentity.Unknown.displayLabel)
        assertTrue(AppIdentity.Unknown.packageNames.isEmpty())
    }
}
