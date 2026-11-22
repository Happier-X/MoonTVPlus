package com.moontvplus.phone.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.moontvplus.phone.core.storage.CredentialVault
import com.moontvplus.phone.core.storage.SavedCredentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「记住密码」的 Android 实现：AES/GCM 密钥放在 Android Keystore，
 * 只有密文写进应用私有 SharedPreferences。取消勾选/换站点时会删除密文。
 *
 * 密钥不会导出，也不写日志；Keystore 被重置导致解密失败时按未记住处理。
 */
class AndroidCredentialVault(context: Context) : CredentialVault {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun load(): SavedCredentials? = withContext(Dispatchers.IO) {
        val encoded = prefs.getString(KEY, null) ?: return@withContext null
        runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return@runCatching null
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipherText = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            val parts = plain.split(SEPARATOR, limit = 2)
            if (parts.size != 2) null else SavedCredentials(
                username = parts[0].takeIf { it.isNotEmpty() },
                password = parts[1],
            )
        }.getOrNull()
    }

    override suspend fun save(username: String?, password: String) = withContext(Dispatchers.IO) {
        if (password.isBlank()) {
            clearBlocking()
            return@withContext
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = (username?.trim().orEmpty() + SEPARATOR + password).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.iv + cipher.doFinal(body)
        prefs.edit().putString(KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { clearBlocking() }

    private fun clearBlocking() {
        prefs.edit().remove(KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "moontv_credentials"
        const val KEY = "remembered"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "moontv_remembered_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val SEPARATOR = "\u0000"
    }
}
