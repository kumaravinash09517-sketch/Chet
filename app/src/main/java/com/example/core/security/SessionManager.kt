package com.example.core.security

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    val phone: String = "",
    val avatarUrl: String? = null,
    val sessionToken: String,
    val deviceLockId: String,
    val registeredAtMillis: Long,
    val registeredAtFormatted: String
)

/**
 * Enforces single-device session lock using Android Keystore AES-256-GCM encryption
 * persisted inside Jetpack DataStore, backed by a same-device durable identity mirror
 * so users stay permanently signed in across app updates unless they explicitly sign out.
 */
class SessionManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingReSealJob: Job? = null
    private val durablePrefs = context.getSharedPreferences(DURABLE_PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedSession: UserSession? = null

    @Volatile
    private var cachedDeviceLockId: String? = null

    companion object {
        private const val DURABLE_PREFS_NAME = "hello_sir_persistent_identity"
        private const val PREF_USER_ID = "persist_user_id"
        private const val PREF_DISPLAY_NAME = "persist_display_name"
        private const val PREF_PHONE = "persist_phone"
        private const val PREF_AVATAR_URL = "persist_avatar_url"
        private const val PREF_SESSION_TOKEN = "persist_session_token"
        private const val PREF_DEVICE_LOCK_ID = "persist_device_lock_id"
        private const val PREF_ANDROID_ID = "persist_android_id"
        private const val PREF_REG_MILLIS = "persist_reg_millis"
        private const val PREF_REG_FORMATTED = "persist_reg_formatted"
        private const val PREF_EXPLICITLY_LOGGED_OUT = "persist_explicitly_logged_out"

        private val KEY_ENCRYPTED_SESSION = stringPreferencesKey("encrypted_user_session")
        private val KEY_DEVICE_LOCK_SECRET = stringPreferencesKey("encrypted_device_lock_id")

        fun isAllowedForActiveLock(
            existingUserId: String,
            existingPhone: String,
            candidateUserId: String,
            candidatePhone: String
        ): Boolean {
            val sameId = existingUserId.equals(candidateUserId.trim(), ignoreCase = true)
            val existingDigits = normalizeCompareDigits(existingPhone)
            val candidateDigits = normalizeCompareDigits(candidatePhone)
            val samePhone = candidateDigits.isNotEmpty() && existingDigits == candidateDigits
            return sameId || samePhone
        }

        private fun normalizeCompareDigits(raw: String): String {
            val digits = raw.filter { it.isDigit() }
            return when {
                digits.length == 10 -> "91$digits"
                digits.length == 11 && digits.startsWith("0") -> "91${digits.drop(1)}"
                else -> digits
            }
        }
    }

    val sessionFlow: Flow<UserSession?> = context.secureSessionDataStore.data.map { prefs ->
        if (durablePrefs.getBoolean(PREF_EXPLICITLY_LOGGED_OUT, false)) {
            cachedSession = null
            return@map null
        }
        val encrypted = prefs[KEY_ENCRYPTED_SESSION]
        val expectedFingerprint = KeystoreCrypto.computeDeviceFingerprint(context)

        if (encrypted != null) {
            val decrypted = KeystoreCrypto.decrypt(encrypted)
            if (decrypted != null) {
                try {
                    val session = json.decodeFromString<UserSession>(decrypted)
                    if (session.deviceLockId == expectedFingerprint) {
                        cachedSession = session
                        cachedDeviceLockId = session.deviceLockId
                        mirrorDurableIdentity(session)
                        return@map session
                    }
                } catch (_: Exception) {
                    // Fall through to same-device durable recovery check below
                }
            }
        }

        val recovered = recoverDurableSessionForThisDevice(expectedFingerprint)
        if (recovered != null) {
            cachedSession = recovered
            cachedDeviceLockId = recovered.deviceLockId
            pendingReSealJob?.cancel()
            pendingReSealJob = recoveryScope.launch {
                reSealIntoDataStore(recovered)
            }
            return@map recovered
        }

        if (encrypted != null && !durablePrefs.getBoolean(PREF_EXPLICITLY_LOGGED_OUT, false)) {
            GlobalSecurityEventBus.emitTamper("Device lock mismatch: session bound to another hardware profile.")
        }
        cachedSession = null
        null
    }

    private fun currentAndroidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-id"

    private fun mirrorDurableIdentity(session: UserSession) {
        if (durablePrefs.getString(PREF_USER_ID, null) == session.userId &&
            durablePrefs.getString(PREF_DISPLAY_NAME, null) == session.displayName &&
            durablePrefs.getString(PREF_SESSION_TOKEN, null) == session.sessionToken &&
            !durablePrefs.getBoolean(PREF_EXPLICITLY_LOGGED_OUT, false)
        ) {
            return
        }
        durablePrefs.edit()
            .putString(PREF_USER_ID, session.userId)
            .putString(PREF_DISPLAY_NAME, session.displayName)
            .putString(PREF_PHONE, session.phone)
            .putString(PREF_AVATAR_URL, session.avatarUrl)
            .putString(PREF_SESSION_TOKEN, session.sessionToken)
            .putString(PREF_DEVICE_LOCK_ID, session.deviceLockId)
            .putString(PREF_ANDROID_ID, currentAndroidId())
            .putLong(PREF_REG_MILLIS, session.registeredAtMillis)
            .putString(PREF_REG_FORMATTED, session.registeredAtFormatted)
            .putBoolean(PREF_EXPLICITLY_LOGGED_OUT, false)
            .apply()
    }

    private fun recoverDurableSessionForThisDevice(expectedFingerprint: String): UserSession? {
        if (durablePrefs.getBoolean(PREF_EXPLICITLY_LOGGED_OUT, false)) return null
        val savedUserId = durablePrefs.getString(PREF_USER_ID, null)?.trim().orEmpty()
        val savedDisplayName = durablePrefs.getString(PREF_DISPLAY_NAME, null)?.trim().orEmpty()
        if (savedUserId.isBlank() || savedDisplayName.isBlank()) return null

        val savedAndroidId = durablePrefs.getString(PREF_ANDROID_ID, null)
        val savedFingerprint = durablePrefs.getString(PREF_DEVICE_LOCK_ID, null)
        val samePhysicalDevice = (savedAndroidId != null && savedAndroidId == currentAndroidId()) ||
            (savedFingerprint != null && savedFingerprint == expectedFingerprint)
        if (!samePhysicalDevice) return null

        val savedToken = durablePrefs.getString(PREF_SESSION_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val savedPhone = durablePrefs.getString(PREF_PHONE, "").orEmpty()
        val savedAvatar = durablePrefs.getString(PREF_AVATAR_URL, null)?.takeIf { it.isNotBlank() }
        val regMillis = durablePrefs.getLong(PREF_REG_MILLIS, System.currentTimeMillis())
        val regFmt = durablePrefs.getString(PREF_REG_FORMATTED, "").orEmpty()

        return UserSession(
            userId = savedUserId,
            displayName = savedDisplayName,
            phone = savedPhone,
            avatarUrl = savedAvatar,
            sessionToken = savedToken,
            deviceLockId = expectedFingerprint,
            registeredAtMillis = regMillis,
            registeredAtFormatted = regFmt
        )
    }

    private suspend fun reSealIntoDataStore(session: UserSession) {
        if (durablePrefs.getBoolean(PREF_EXPLICITLY_LOGGED_OUT, false)) return
        runCatching {
            val encoded = json.encodeToString(session)
            val encrypted = KeystoreCrypto.encrypt(encoded)
            val encryptedLock = KeystoreCrypto.encrypt(session.deviceLockId)
            context.secureSessionDataStore.edit { prefs ->
                if (durablePrefs.getBoolean(PREF_EXPLICITLY_LOGGED_OUT, false)) return@edit
                prefs[KEY_ENCRYPTED_SESSION] = encrypted
                prefs[KEY_DEVICE_LOCK_SECRET] = encryptedLock
            }
        }
    }

    /**
     * Exposed for testing / diagnostics: corrupts only the encrypted DataStore blob without
     * marking an explicit logout, verifying durable recovery keeps the user signed in.
     */
    suspend fun simulateUpdateKeyRotationForTest() {
        cachedSession = null
        context.secureSessionDataStore.edit { prefs ->
            prefs[KEY_ENCRYPTED_SESSION] = "corrupted_iv:corrupted_payload"
        }
    }

    suspend fun getCurrentSession(): UserSession? {
        cachedSession?.let { return it }
        return sessionFlow.first().also { cachedSession = it }
    }

    suspend fun getOrCreateDeviceLockId(): String {
        cachedDeviceLockId?.let { return it }
        val currentFingerprint = KeystoreCrypto.computeDeviceFingerprint(context)
        val prefs = context.secureSessionDataStore.data.first()
        val storedEncrypted = prefs[KEY_DEVICE_LOCK_SECRET]
        if (storedEncrypted != null) {
            val decrypted = KeystoreCrypto.decrypt(storedEncrypted)
            if (decrypted == currentFingerprint) {
                cachedDeviceLockId = currentFingerprint
                return currentFingerprint
            }
        }
        runCatching {
            context.secureSessionDataStore.edit { mutablePrefs ->
                mutablePrefs[KEY_DEVICE_LOCK_SECRET] = KeystoreCrypto.encrypt(currentFingerprint)
            }
        }
        cachedDeviceLockId = currentFingerprint
        return currentFingerprint
    }

    /**
     * Checks whether this device is already locked to a different User ID or Phone Number.
     */
    suspend fun canRegisterUser(candidateUserId: String, candidatePhone: String = ""): Boolean {
        val existing = getCurrentSession() ?: return true
        return isAllowedForActiveLock(
            existingUserId = existing.userId,
            existingPhone = existing.phone,
            candidateUserId = candidateUserId,
            candidatePhone = candidatePhone
        )
    }

    suspend fun saveSession(
        userId: String,
        displayName: String,
        phone: String,
        avatarUrl: String?,
        serverToken: String?,
        registeredAtMillis: Long,
        registeredAtFormatted: String,
        deviceIdOverride: String? = null
    ): UserSession {
        val deviceLockId = getOrCreateDeviceLockId()
        val effectiveDeviceId = deviceIdOverride?.takeIf { it == deviceLockId } ?: deviceLockId
        val session = UserSession(
            userId = userId.trim(),
            displayName = displayName.trim(),
            phone = phone.trim(),
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
            sessionToken = serverToken?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            deviceLockId = effectiveDeviceId,
            registeredAtMillis = registeredAtMillis,
            registeredAtFormatted = registeredAtFormatted
        )
        pendingReSealJob?.cancel()
        pendingReSealJob = null
        cachedSession = session
        cachedDeviceLockId = session.deviceLockId
        durablePrefs.edit()
            .putString(PREF_USER_ID, session.userId)
            .putString(PREF_DISPLAY_NAME, session.displayName)
            .putString(PREF_PHONE, session.phone)
            .putString(PREF_AVATAR_URL, session.avatarUrl)
            .putString(PREF_SESSION_TOKEN, session.sessionToken)
            .putString(PREF_DEVICE_LOCK_ID, session.deviceLockId)
            .putString(PREF_ANDROID_ID, currentAndroidId())
            .putLong(PREF_REG_MILLIS, session.registeredAtMillis)
            .putString(PREF_REG_FORMATTED, session.registeredAtFormatted)
            .putBoolean(PREF_EXPLICITLY_LOGGED_OUT, false)
            .commit()

        runCatching {
            val encoded = json.encodeToString(session)
            val encrypted = KeystoreCrypto.encrypt(encoded)
            context.secureSessionDataStore.edit { prefs ->
                prefs[KEY_ENCRYPTED_SESSION] = encrypted
                prefs[KEY_DEVICE_LOCK_SECRET] = KeystoreCrypto.encrypt(deviceLockId)
            }
        }
        return session
    }

    suspend fun clearSession() {
        pendingReSealJob?.cancel()
        pendingReSealJob = null
        cachedSession = null
        durablePrefs.edit()
            .clear()
            .putBoolean(PREF_EXPLICITLY_LOGGED_OUT, true)
            .commit()
        context.secureSessionDataStore.edit { prefs ->
            prefs.remove(KEY_ENCRYPTED_SESSION)
        }
        GlobalSecurityEventBus.clearBlocked()
    }
}
