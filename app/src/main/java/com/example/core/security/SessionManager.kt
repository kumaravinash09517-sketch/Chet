package com.example.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.secureSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hello_sir_encrypted_session"
)

@Serializable
data class UserSession(
    val userId: String,
    val displayName: String,
    val sessionToken: String,
    val deviceLockId: String,
    val registeredAtMillis: Long,
    val registeredAtFormatted: String
)

/**
 * Enforces single-device session lock using Android Keystore AES-256-GCM encryption
 * persisted inside Jetpack DataStore.
 */
class SessionManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_ENCRYPTED_SESSION = stringPreferencesKey("encrypted_user_session")
        private val KEY_DEVICE_LOCK_SECRET = stringPreferencesKey("encrypted_device_lock_id")
    }

    val sessionFlow: Flow<UserSession?> = context.secureSessionDataStore.data.map { prefs ->
        val encrypted = prefs[KEY_ENCRYPTED_SESSION] ?: return@map null
        val decrypted = KeystoreCrypto.decrypt(encrypted)
        if (decrypted == null) {
            GlobalSecurityEventBus.emitTamper("Encrypted session integrity check failed.")
            return@map null
        }
        try {
            val session = json.decodeFromString<UserSession>(decrypted)
            val expectedFingerprint = KeystoreCrypto.computeDeviceFingerprint(context)
            if (session.deviceLockId != expectedFingerprint) {
                GlobalSecurityEventBus.emitTamper("Device lock mismatch: session bound to another hardware profile.")
                null
            } else {
                session
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getCurrentSession(): UserSession? = sessionFlow.first()

    suspend fun getOrCreateDeviceLockId(): String {
        val currentFingerprint = KeystoreCrypto.computeDeviceFingerprint(context)
        val prefs = context.secureSessionDataStore.data.first()
        val storedEncrypted = prefs[KEY_DEVICE_LOCK_SECRET]
        if (storedEncrypted != null) {
            val decrypted = KeystoreCrypto.decrypt(storedEncrypted)
            if (decrypted == currentFingerprint) {
                return currentFingerprint
            }
        }
        context.secureSessionDataStore.edit { mutablePrefs ->
            mutablePrefs[KEY_DEVICE_LOCK_SECRET] = KeystoreCrypto.encrypt(currentFingerprint)
        }
        return currentFingerprint
    }

    /**
     * Checks whether this device is already locked to a different User ID.
     */
    suspend fun canRegisterUser(candidateUserId: String): Boolean {
        val existing = getCurrentSession() ?: return true
        return existing.userId.equals(candidateUserId.trim(), ignoreCase = true)
    }

    suspend fun saveSession(
        userId: String,
        displayName: String,
        serverToken: String?,
        registeredAtMillis: Long,
        registeredAtFormatted: String
    ): UserSession {
        val deviceLockId = getOrCreateDeviceLockId()
        val session = UserSession(
            userId = userId.trim(),
            displayName = displayName.trim(),
            sessionToken = serverToken?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            deviceLockId = deviceLockId,
            registeredAtMillis = registeredAtMillis,
            registeredAtFormatted = registeredAtFormatted
        )
        val encoded = json.encodeToString(session)
        val encrypted = KeystoreCrypto.encrypt(encoded)
        context.secureSessionDataStore.edit { prefs ->
            prefs[KEY_ENCRYPTED_SESSION] = encrypted
            prefs[KEY_DEVICE_LOCK_SECRET] = KeystoreCrypto.encrypt(deviceLockId)
        }
        return session
    }

    suspend fun clearSession() {
        context.secureSessionDataStore.edit { prefs ->
            prefs.remove(KEY_ENCRYPTED_SESSION)
        }
        GlobalSecurityEventBus.clearBlocked()
    }
}
