package acr.browser.lightning.browser.access

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Decodes and authenticates a Windows-generated .lbconfig file. */
object AllowlistConfigCodec {
    private val MAGIC = "LBCFG001".toByteArray(Charsets.US_ASCII)
    private const val KEY_ID: Byte = 1
    private const val NONCE_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_FILE_BYTES = 1_048_576
    private const val MAX_DOMAINS = 2_000

    // The matching encryption key and ECDSA private key are documented in
    // docs/allowlist-config-protocol.md for the Windows configuration generator.
    private const val AES_KEY_HEX =
        "51e358691384305b847970148cf77ab1123bccc0e3a41b81620ff8fe691fb812"
    private const val PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXtbsRto7NbZzPZsKttuUUjSXS9JC" +
            "bxGJnJ/tC0p+vgHkxycYHXuzDKPqfp5o2V/7Bbcs6V5uXTA1kbf2h+Uw2w=="

    fun decode(file: ByteArray): List<String> {
        if (file.size > MAX_FILE_BYTES) throw ConfigException.TooLarge
        if (file.size < MAGIC.size + 1 + NONCE_LENGTH + Int.SIZE_BYTES + 16 + Short.SIZE_BYTES) {
            throw ConfigException.InvalidFormat
        }

        val buffer = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw ConfigException.UnsupportedVersion
        val keyId = buffer.get()
        if (keyId != KEY_ID) throw ConfigException.UnsupportedKey
        val nonce = ByteArray(NONCE_LENGTH).also(buffer::get)
        val encryptedLength = buffer.int
        if (encryptedLength < 16 || encryptedLength > buffer.remaining() - Short.SIZE_BYTES) {
            throw ConfigException.InvalidFormat
        }
        val encrypted = ByteArray(encryptedLength).also(buffer::get)
        val signedLength = buffer.position()
        val signatureLength = buffer.short.toInt() and 0xffff
        if (signatureLength == 0 || signatureLength != buffer.remaining()) {
            throw ConfigException.InvalidFormat
        }
        val signatureBytes = ByteArray(signatureLength).also(buffer::get)

        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64))
        )
        val verified = try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(file, 0, signedLength)
            verifier.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
        if (!verified) throw ConfigException.InvalidSignature

        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(AES_KEY_HEX.hexToBytes(), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            cipher.updateAAD(file, 0, MAGIC.size + 1)
            cipher.doFinal(encrypted)
        } catch (_: AEADBadTagException) {
            throw ConfigException.DecryptionFailed
        } catch (_: Exception) {
            throw ConfigException.DecryptionFailed
        }

        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString()
        } catch (_: Exception) {
            throw ConfigException.InvalidContent
        }
        val domains = text.lineSequence().filter(String::isNotBlank).toList()
        if (domains.isEmpty() || domains.size > MAX_DOMAINS) throw ConfigException.InvalidContent
        return domains
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    sealed class ConfigException : Exception() {
        data object TooLarge : ConfigException()
        data object InvalidFormat : ConfigException()
        data object UnsupportedVersion : ConfigException()
        data object UnsupportedKey : ConfigException()
        data object InvalidSignature : ConfigException()
        data object DecryptionFailed : ConfigException()
        data object InvalidContent : ConfigException()
    }
}
