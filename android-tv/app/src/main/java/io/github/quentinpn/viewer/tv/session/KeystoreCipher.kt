package io.github.quentinpn.viewer.tv.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE = "AndroidKeyStore"
private const val GCM_TAG_BITS = 128

/** Alias of the key that seals the Emby tokens: it must never change, or signed-in users lose their session. */
internal const val SESSION_KEY_ALIAS = "viewer.session"

/**
 * `base64(iv) . base64(ciphertext)`, the format of earlier versions; the Keystore picks the IV.
 * Plain base64 without line breaks, as `android.util.Base64.NO_WRAP` wrote it.
 */
internal object SealedFormat {
    fun seal(iv: ByteArray, data: ByteArray): String = encode(iv) + "." + encode(data)

    /** Returns the IV and the ciphertext; throws [IllegalArgumentException] on anything else. */
    fun open(sealed: String): Pair<ByteArray, ByteArray> {
        val parts = sealed.split('.')
        require(parts.size == 2)
        return Base64.getDecoder().decode(parts[0]) to Base64.getDecoder().decode(parts[1])
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}

/** AES-GCM with a non-exportable Android Keystore key named [alias], created on first use. */
class KeystoreCipher(private val alias: String) : TokenCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return SealedFormat.seal(cipher.iv, cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(sealed: String): String {
        val (iv, data) = SealedFormat.open(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
