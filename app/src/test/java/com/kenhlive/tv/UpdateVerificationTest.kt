package com.kenhlive.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Test cho logic kiểm chứng cập nhật đã tách ra UpdateVerification (Tier 0).
 * Các test này không cần Android runtime: chỉ dùng File tạm và byte thuần.
 * Chúng chứng minh hành vi fail-closed: thiếu SHA, sai SHA, sai package,
 * sai signer hoặc downgrade đều phải bị từ chối.
 */
class UpdateVerificationTest {

    @Test
    fun testIsNewerVersion() {
        assertTrue(UpdateVerification.isNewerVersion("1.2", "1.1"))
        assertTrue(UpdateVerification.isNewerVersion("2.0", "1.9.9"))
        assertTrue(UpdateVerification.isNewerVersion("1.10", "1.9"))
        assertFalse(UpdateVerification.isNewerVersion("1.1", "1.1"))
        assertFalse(UpdateVerification.isNewerVersion("1.0", "1.1"))
        assertFalse(UpdateVerification.isNewerVersion("1.2", "x"))
    }

    @Test
    fun testSha256HexKnownVector() {
        val tmp = File.createTempFile("sha-test", ".bin")
        try {
            tmp.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateVerification.sha256Hex(tmp)
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun testSha256HexEmptyFile() {
        val tmp = File.createTempFile("sha-empty", ".bin")
        try {
            tmp.writeBytes(ByteArray(0))
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                UpdateVerification.sha256Hex(tmp)
            )
        } finally {
            tmp.delete()
        }
    }

    private fun validCandidate(): UpdateVerificationTest.Candidate {
        return Candidate(
            packageName = "com.kenhlive.tv",
            archivePackage = "com.kenhlive.tv",
            archiveVersionCode = 162L,
            installedVersionCode = 161L,
            archiveVersionName = "0.1.12",
            installedVersionName = "0.1.11",
            expectedSha256 = "aa".repeat(32),
            actualSha256 = "aa".repeat(32),
            signerMatches = true
        )
    }

    private data class Candidate(
        val packageName: String,
        val archivePackage: String,
        val archiveVersionCode: Long,
        val installedVersionCode: Long,
        val archiveVersionName: String,
        val installedVersionName: String,
        val expectedSha256: String?,
        val actualSha256: String?,
        val signerMatches: Boolean
    )

    private fun validate(c: Candidate): String? = UpdateVerification.validateCandidate(
        packageName = c.packageName,
        archivePackage = c.archivePackage,
        archiveVersionCode = c.archiveVersionCode,
        installedVersionCode = c.installedVersionCode,
        archiveVersionName = c.archiveVersionName,
        installedVersionName = c.installedVersionName,
        expectedSha256 = c.expectedSha256,
        actualSha256 = c.actualSha256,
        signerMatches = c.signerMatches
    )

    @Test
    fun testValidateValidCandidatePasses() {
        assertNull(validate(validCandidate()))
    }

    @Test
    fun testValidateMissingShaIsRejected() {
        val error = validate(validCandidate().copy(expectedSha256 = null))
        assertNotNull(error)
        assertTrue(error!!.contains("SHA-256"))
    }

    @Test
    fun testValidateWrongShaIsRejected() {
        val error = validate(validCandidate().copy(actualSha256 = "bb".repeat(32)))
        assertNotNull(error)
        assertTrue(error!!.contains("SHA-256"))
    }

    @Test
    fun testValidateWrongPackageIsRejected() {
        val error = validate(validCandidate().copy(archivePackage = "com.evil.app"))
        assertNotNull(error)
        assertTrue(error!!.contains("com.evil.app"))
    }

    @Test
    fun testValidateSignerMismatchIsRejected() {
        val error = validate(validCandidate().copy(signerMatches = false))
        assertNotNull(error)
        assertTrue(error!!.contains("chữ ký"))
    }

    @Test
    fun testValidateDowngradeIsRejected() {
        val error = validate(
            validCandidate().copy(
                archiveVersionCode = 160L,
                archiveVersionName = "0.1.10"
            )
        )
        assertNotNull(error)
    }

    @Test
    fun testValidateSameCodeButNewerNamePasses() {
        assertNull(
            validate(
                validCandidate().copy(
                    archiveVersionCode = 161L,
                    archiveVersionName = "0.1.12"
                )
            )
        )
    }

    @Test
    fun testSignaturesMatch() {
        val a = "cert-a".toByteArray()
        val b = "cert-b".toByteArray()
        assertTrue(UpdateVerification.signaturesMatch(listOf(a, b), listOf(b)))
        assertFalse(UpdateVerification.signaturesMatch(listOf(a), listOf(b)))
        assertFalse(UpdateVerification.signaturesMatch(listOf(a), emptyList()))
        assertFalse(UpdateVerification.signaturesMatch(emptyList(), listOf(a)))
    }
}
