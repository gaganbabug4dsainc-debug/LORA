package com.example.phoneagent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys ko Android Keystore (AES-GCM) se encrypt karke rakhta hai. Fail ho to plain (app na toote). */
object SecureStore {
    private const val ALIAS = "lora_keys"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    fun enc(s: String): String {
        if (s.isEmpty()) return s
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(s.toByteArray())
            "enc:" + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            s
        }
    }

    fun dec(s: String): String {
        if (!s.startsWith("enc:")) return s
        return try {
            val raw = Base64.decode(s.substring(4), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) {
            ""
        }
    }
}
