package ai.matca.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 10, test 1: the fixture manifest signed by the control plane's own code, and the ways a key is trusted. */
internal class SigningTest {
    private val fixture = fixtureJson("manifest.json")
    private val publicKey = fixture.getString("public_key")
    private val text = fixture.getString("manifest")
    private val signature = fixture.getString("signature")
    // 15 January 2027; the certificates below expire a year later or years earlier.
    private val now = 1_800_000_000_000L

    @Test
    fun theFixtureManifestVerifies() {
        assertTrue(Signing.verifies(publicKey, text, signature))
        assertEquals(publicKey, SigningBlock.of(text).key)
    }

    @Test
    fun theTamperedManifestDoesNotVerify() {
        assertFalse(Signing.verifies(publicKey, fixture.getString("tampered"), signature))
    }

    @Test
    fun aWrongKeyDoesNotVerify() {
        assertFalse(Signing.verifies(TestKey().publicKeyBase64, text, signature))
        assertFalse(Signing.verifies("not a key", text, signature))
        assertFalse(Signing.verifies(publicKey, text, "not a signature"))
    }

    @Test
    fun aKeyEqualToTheRootIsTrustedWithoutACertificate() {
        Signing.trust(SigningBlock.of(text), publicKey, now)
    }

    @Test
    fun anotherKeyWithoutACertificateIsRefused() {
        assertThrows(EdgeError.Untrusted::class.java) { Signing.trust(SigningBlock.of(text), TestKey().publicKeyBase64, now) }
    }

    @Test
    fun aCertificateUnderTheRootIsTrustedUntilItExpires() {
        val root = TestKey()
        val environment = TestKey()
        val expires = "2028-01-01T00:00:00+00:00"
        val certificate = root.sign(Signing.certificateText(environment.publicKeyBase64, expires))
        val manifest = SigningBlock.of(manifestText(environment.publicKeyBase64, certificate = certificateJson(expires, certificate)))
        Signing.trust(manifest, root.publicKeyBase64, now)
        val afterExpiry = 1_900_000_000_000L
        assertThrows(EdgeError.Untrusted::class.java) { Signing.trust(manifest, root.publicKeyBase64, afterExpiry) }
    }

    @Test
    fun anExpiredCertificateIsRefused() {
        val root = TestKey()
        val environment = TestKey()
        val expires = "2020-01-01T00:00:00+00:00"
        val certificate = root.sign(Signing.certificateText(environment.publicKeyBase64, expires))
        val manifest = SigningBlock.of(manifestText(environment.publicKeyBase64, certificate = certificateJson(expires, certificate)))
        assertThrows(EdgeError.Untrusted::class.java) { Signing.trust(manifest, root.publicKeyBase64, now) }
    }

    @Test
    fun aCertificateSignedByAnotherKeyIsRefused() {
        val root = TestKey()
        val environment = TestKey()
        val expires = "2028-01-01T00:00:00+00:00"
        val certificate = TestKey().sign(Signing.certificateText(environment.publicKeyBase64, expires))
        val manifest = SigningBlock.of(manifestText(environment.publicKeyBase64, certificate = certificateJson(expires, certificate)))
        assertThrows(EdgeError.Untrusted::class.java) { Signing.trust(manifest, root.publicKeyBase64, now) }
    }

    @Test
    fun theCertificateTextMatchesTheControlPlane() {
        val text = Signing.certificateText("AQID", "2027-01-01T00:00:00+00:00")
        assertEquals("""{"expires":"2027-01-01T00:00:00+00:00","key":"AQID"}""", text)
    }

    private fun certificateJson(expires: String, signature: String) = """{"expires":"$expires","signature":"$signature"}"""
}
