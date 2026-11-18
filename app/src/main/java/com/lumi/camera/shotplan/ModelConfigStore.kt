package com.lumi.camera.shotplan

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.lumi.camera.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ModelConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("model_settings", Context.MODE_PRIVATE)

    fun read(): ModelConfig = initialModelConfig(prefs.contains("ciphertext"), BuildConfig.DEBUG, saved = {
        // A restored preference file can outlive its Keystore key. Keep the endpoint editable.
        val apiKey = try {
            val key = checkNotNull(storedKey())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode("iv")))
            cipher.doFinal(decode("ciphertext")).toString(Charsets.UTF_8)
        } catch (_: Exception) { "" }
        ModelConfig(prefs.getString("address", "").orEmpty(), prefs.getString("model", "").orEmpty(), apiKey)
    }, defaults = {
        ModelConfig(BuildConfig.LUMI_LLM_BASE_URL, BuildConfig.LUMI_LLM_MODEL, BuildConfig.LUMI_LLM_API_KEY)
    })

    // The IO worker checks commit's result before the camera switches configurations.
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun save(config: ModelConfig): Boolean = try {
        val key = storedKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(config.apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("address", config.address).putString("model", config.model)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
    } catch (_: Exception) { false }

    var lastFailed: Boolean
        get() = prefs.getBoolean("last_failed", false)
        set(value) { prefs.edit { putBoolean("last_failed", value) } }

    private fun storedKey(): SecretKey? = KeyStore.getInstance(KEYSTORE).run {
        load(null)
        getKey(ALIAS, null) as? SecretKey
    }

    private fun decode(name: String) = Base64.decode(prefs.getString(name, ""), Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "lumi_model_api_key"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
