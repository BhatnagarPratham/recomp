package app.recomp.data.ai

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.di.SecretsStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Authenticated encryption for secrets at rest. Tests use a stand-in, since Robolectric has no Android Keystore. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * Tink AEAD (AES-256-GCM) with its keyset wrapped by an Android Keystore master key (TRD §6.2). The keyset
 * lives in its own shared-preferences file, which `data_extraction_rules.xml` keeps out of backups; the
 * Keystore key never leaves the phone, so a copy restored elsewhere couldn't decrypt anything anyway.
 */
@Singleton
class TinkSecretCipher @Inject constructor(@ApplicationContext private val context: Context) : SecretCipher {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = aead.encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = aead.decrypt(ciphertext, associatedData)

    companion object {
        /** Also named in res/xml/data_extraction_rules.xml. */
        const val KEYSET_PREFS = "recomp_ai_keyset"
        private const val KEYSET_NAME = "ai_keyset"
        private const val MASTER_KEY_URI = "android-keystore://recomp_ai_master_key"
    }
}

/**
 * The Claude API key (TRD §6.2): entered once in Settings → AI, stored only as ciphertext in the `secrets`
 * DataStore, apart from the settings so no export can include it. It's never logged or shown again. A
 * stored value that no longer decrypts (a restore onto another phone) reads as no key.
 */
@Singleton
class AiKeyStore @Inject constructor(
    @SecretsStore private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    val hasKey: Flow<Boolean> = store.data.map { prefs -> prefs[KEY]?.let(::decryptOrNull) != null }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    /** The key for one request. Null when none is stored or it can't be decrypted. */
    suspend fun key(): String? = withContext(Dispatchers.IO) { store.data.first()[KEY]?.let(::decryptOrNull) }

    suspend fun save(apiKey: String) {
        val trimmed = apiKey.trim()
        require(looksLikeKey(trimmed)) { "Not an API key" }
        val sealed = withContext(Dispatchers.IO) { cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8), AD) }
        store.edit { it[KEY] = Base64.encodeToString(sealed, Base64.NO_WRAP) }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    /** A stored key that no longer decrypts, or that a build before this check let through, reads as no key. */
    private fun decryptOrNull(stored: String): String? = runCatching {
        String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP), AD), Charsets.UTF_8)
    }.getOrNull()?.takeIf(::looksLikeKey)

    companion object {
        private val KEY = stringPreferencesKey("anthropic_api_key")

        /**
         * Printable ASCII with no spaces, which is all an API key is made of. Anything else, such as a zero-width
         * space, a BOM or an en dash picked up in a copy, would make OkHttp reject the request header with the
         * whole key in its error message.
         */
        fun looksLikeKey(key: String): Boolean = key.isNotEmpty() && key.all { it in '!'..'~' }


        /** Binds the ciphertext to what it is, so it can't be swapped in for another secret. */
        private val AD = "recomp:anthropic_api_key".toByteArray(Charsets.UTF_8)
    }
}
