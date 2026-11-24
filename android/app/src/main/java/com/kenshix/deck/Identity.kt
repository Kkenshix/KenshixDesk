package com.kenshix.deck

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class Identity(val fingerprint: String, val token: String, val lan: String)

internal class Credentials(private val preferences: SharedPreferences) {
    private val alias = "KenshixDeck.Connection"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    fun save(identity: Identity) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(identity.token.toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString("fingerprint", identity.fingerprint)
            .putString("lan", identity.lan)
            .putString("identity", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
            .commit())
    }

    fun load(): Identity? {
        val encoded = preferences.getString("identity", null) ?: return null
        val fingerprint = preferences.getString("fingerprint", null) ?: return null
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        }
        return Identity(fingerprint, cipher.doFinal(raw.copyOfRange(12, raw.size)).toString(Charsets.UTF_8),
            preferences.getString("lan", "").orEmpty())
    }

    fun forget() {
        check(preferences.edit().remove("identity").remove("fingerprint").remove("lan").commit())
    }
}

internal fun validLan(address: String): Boolean {
    if (address.isEmpty()) return true
    val octets = address.split('.').map { part ->
        part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part } ?: return false
    }
    return octets.size == 4 && when (octets[0]) {
        10 -> true
        172 -> octets[1] in 16..31
        192 -> octets[1] == 168
        else -> false
    }
}
