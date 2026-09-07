package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驗證已套用 APP 索引的 UID 映射行為。
 */
class AppliedAppIndexTest {

    @Test
    fun singlePackageUidMapsToResolvedIdentity() {
        val index = AppliedAppIndex.build(listOf(AppliedApp("com.example.a", 10101, "APP A")))
        val identity = index.identityFor(10101)
        assertEquals(AppIdentity.Resolved(10101, "com.example.a", "APP A"), identity)
    }

    @Test
    fun sharedUidMapsToOneSharedGroupWithAllPackages() {
        val index = AppliedAppIndex.build(
            listOf(
                AppliedApp("com.example.b", 10500, "APP B"),
                AppliedApp("com.example.a", 10500, "APP A")
            )
        )
        val identity = index.identityFor(10500)
        assertTrue(identity is AppIdentity.SharedUid)
        identity as AppIdentity.SharedUid
        assertEquals(listOf("com.example.a", "com.example.b"), identity.packageNames)
        assertEquals(listOf("APP A", "APP B"), identity.labels)
    }

    @Test
    fun duplicateLabelsOnDifferentUidsStayInSeparateGroups() {
        val index = AppliedAppIndex.build(
            listOf(
                AppliedApp("com.example.a", 10101, "相機"),
                AppliedApp("com.example.b", 10102, "相機")
            )
        )
        val first = index.identityFor(10101)
        val second = index.identityFor(10102)
        assertEquals("相機", first.displayLabel)
        assertEquals("相機", second.displayLabel)
        assertNotEquals("名稱重複時仍必須是不同分組", first.groupKey, second.groupKey)
        assertEquals(listOf("com.example.a"), first.packageNames)
        assertEquals(listOf("com.example.b"), second.packageNames)
    }

    @Test
    fun uidOutsideTheAppliedSetIsUnknown() {
        val index = AppliedAppIndex.build(listOf(AppliedApp("com.example.a", 10101, "APP A")))
        assertEquals("未套用的 UID 不得被猜測歸屬", AppIdentity.Unknown, index.identityFor(10999))
    }

    @Test
    fun invalidUidIsUnknown() {
        val index = AppliedAppIndex.build(listOf(AppliedApp("com.example.a", 10101, "APP A")))
        assertEquals(AppIdentity.Unknown, index.identityFor(INVALID_UID))
    }

    @Test
    fun emptyAppliedListYieldsAnEmptyIndex() {
        val index = AppliedAppIndex.build(emptyList())
        assertTrue(index.isEmpty)
        assertEquals(AppIdentity.Unknown, index.identityFor(10101))
    }

    @Test
    fun appliedPackagesOnlyContainsValidatedPackages() {
        val index = AppliedAppIndex.build(
            listOf(
                AppliedApp("com.example.a", 10101, "APP A"),
                AppliedApp("com.example.a", 10101, "APP A"),
                AppliedApp("com.example.b", 10102, "APP B")
            )
        )
        assertEquals(listOf("com.example.a", "com.example.b"), index.appliedPackages)
    }
}
