package com.example.core.security

/**
 * Auth abstraction decoupling the UI/session layer from specific sign-in mechanisms.
 * Currently backed by direct Username + User ID authentication with single-device hardware lock,
 * and structured cleanly for optional Google Sign-In (CredentialManager) or OAuth providers later.
 */
sealed interface AuthCredentials {
    data class DirectUserId(
        val username: String,
        val userId: String,
        val avatarUrl: String? = null,
        val deviceId: String = ""
    ) : AuthCredentials

    data class GoogleIdToken(
        val idToken: String,
        val email: String? = null,
        val displayName: String? = null,
        val avatarUrl: String? = null
    ) : AuthCredentials
}

interface AuthGateway {
    suspend fun signIn(credentials: AuthCredentials): Result<UserSession>
    suspend fun signOut()
}
