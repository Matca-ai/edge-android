package ai.matca.edge

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.io.encoding.Base64

/** ECDSA P-256 with SHA-256 over the exact bytes of a text, the key as SPKI DER: what the control plane signs with. */
internal object Signing {
    private const val ALGORITHM = "SHA256withECDSA"

    fun verifies(publicKeyBase64: String, text: String, signatureBase64: String): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64)))
        Signature.getInstance(ALGORITHM).run {
            initVerify(key)
            update(text.toByteArray(Charsets.UTF_8))
            verify(Base64.decode(signatureBase64))
        }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /**
     * Whether the manifest's signing key may be believed: it is the root the app configured, or the root
     * certified it until a moment still ahead. Anything else is refused, with the reason.
     */
    fun trust(signing: SigningBlock, rootPublicKey: String, nowMillis: Long) {
        if (signing.key == rootPublicKey) return
        val certificate = signing.certificate ?: throw EdgeError.Untrusted("the signing key is not the root and carries no certificate")
        val expires = parseExpiry(certificate.expires) ?: throw EdgeError.Untrusted("the certificate's expiry cannot be read")
        if (expires <= nowMillis) throw EdgeError.Untrusted("the certificate has expired")
        if (!verifies(rootPublicKey, certificateText(signing.key, certificate.expires), certificate.signature)) {
            throw EdgeError.Untrusted("the root did not sign the certificate")
        }
    }

    /** The text the root signed: keys sorted, no spaces, as the control plane's `certificate_text`. */
    fun certificateText(key: String, expires: String): String = """{"expires":"$expires","key":"$key"}"""

    private fun parseExpiry(value: String): Long? {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")
        format.isLenient = false
        return try {
            format.parse(value)?.time
        } catch (_: ParseException) {
            null
        }
    }
}
