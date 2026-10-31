package org.owlear.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Locks and unlocks everything OwlEar saves to disk.
 *
 * Tech: AES-256-GCM with a key generated inside Android Keystore. The key never leaves the
 * phone's secure hardware and can't be exported, so a copied file is unreadable anywhere else.
 * Signal addresses are stored only as HMAC-SHA256 fingerprints made with a second Keystore key.
 *
 * Mechanic version: the saved data sits in a safe bolted to the phone's frame. The key is
 * welded inside it. Part numbers are written in a code only this phone can match, so even
 * if someone pried the safe off, they couldn't read what's inside or look anything up.
 */
object Vault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ENC_ALIAS = "owlear_vault_enc"
    private const val MAC_ALIAS = "owlear_vault_mac"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private var mac: Mac? = null

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    @Synchronized
    private fun encKey(): SecretKey {
        (keyStore().getKey(ENC_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ENC_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    private fun macKey(): SecretKey {
        (keyStore().getKey(MAC_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        gen.init(KeyGenParameterSpec.Builder(MAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    /** Encrypts: returns IV followed by ciphertext. */
    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, encKey())
        return c.iv + c.doFinal(plain)
    }

    @Synchronized
    fun open(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, encKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    /** A one-way fingerprint of a signal address. Returns null if the Keystore misbehaves. */
    @Synchronized
    fun fingerprint(text: String): String? = try {
        val m = mac ?: Mac.getInstance("HmacSHA256").also {
            it.init(macKey())
            mac = it
        }
        m.doFinal(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        mac = null
        null
    }

    /** Destroys both keys. Anything sealed before this is gone for good. */
    @Synchronized
    fun destroyKeys() {
        mac = null
        try {
            val ks = keyStore()
            ks.deleteEntry(ENC_ALIAS)
            ks.deleteEntry(MAC_ALIAS)
        } catch (e: Exception) {
            // nothing to delete
        }
    }
}
