package com.example.phoneagent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sensitive data (API keys, chat text) ko device ke Android Keystore wali AES-256-GCM key se
 * encrypt karta hai. Key phone se bahar nahi jaati. Keystore na chale to plain text par fallback
 * hota hai (app band na ho), aur `fellBack` true ho jata hai.
 */
object Secure {
    private const val ALIAS = "phone_agent_main_key"
    private const val PREFIX = "enc1:"
    private const val PASS_PREFIX = "pa-enc1:"

    @Volatile
    var fellBack = false

    private fun key(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val existing = ks.getKey(ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    fun isEncrypted(s: String): Boolean = s.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        val k = key()
        if (k == null) {
            fellBack = true
            return plain
        }
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            fellBack = true
            plain
        }
    }

    /** Encrypted ho to decrypt karta hai, warna jaisa hai waisa lauta deta hai. Fail par "". */
    fun decrypt(s: String): String {
        if (!s.startsWith(PREFIX)) return s
        val k = key() ?: return ""
        return try {
            val raw = Base64.decode(s.substring(PREFIX.length), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // ---- passphrase-based (backup file ke liye) ----

    private fun passKey(pass: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pass.toCharArray(), salt, 120_000, 256)
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    fun isPassEncrypted(s: String): Boolean = s.trimStart().startsWith(PASS_PREFIX)

    fun encryptWithPass(plain: String, pass: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, passKey(pass, salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PASS_PREFIX + Base64.encodeToString(salt + iv + ct, Base64.NO_WRAP)
    }

    /** Galat passphrase ya kharab file par null. */
    fun decryptWithPass(data: String, pass: String): String? {
        return try {
            val raw = Base64.decode(data.trim().substring(PASS_PREFIX.length), Base64.NO_WRAP)
            val salt = raw.copyOfRange(0, 16)
            val iv = raw.copyOfRange(16, 28)
            val ct = raw.copyOfRange(28, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, passKey(pass, salt), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
