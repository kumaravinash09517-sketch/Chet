package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.HelloSirApplication
import com.example.core.security.GlobalSecurityEventBus
import com.example.core.security.KeystoreCrypto
import com.example.core.security.ServerStatus
import com.example.core.security.UserSession
import com.example.data.local.ConversationEntity
import com.example.data.local.UserEntity
import com.example.platform.DeviceContact
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object Unauthenticated : AuthUiState
    data class Authenticated(val session: UserSession) : AuthUiState
}

enum class OtpLoginStep {
    PHONE_ENTRY,
    OTP_VERIFICATION,
    PROFILE_SETUP
}

typealias LoginStep = OtpLoginStep

data class LoginFlowState(
    val step: OtpLoginStep = OtpLoginStep.PHONE_ENTRY,
    val phone: String = "",
    val deviceId: String = "",
    val verifiedUserId: String = "",
    val verifiedToken: String? = null,
    val prefilledName: String = "",
    val avatarUrl: String? = null,
    val localAvatarPreviewUri: String? = null,
    val isAvatarUploading: Boolean = false,
    val telegramBotUrl: String? = null,
    val resendCountdownSeconds: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HelloSirApplication
    private val repository = app.chatRepository
    private val contactSyncHelper = app.contactSyncHelper

    val sessionState: StateFlow<UserSession?> = repository.sessionFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _authResolved = MutableStateFlow(false)
    val authResolved: StateFlow<Boolean> = _authResolved.asStateFlow()

    val conversations: StateFlow<List<ConversationEntity>> = repository.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val directoryUsers: StateFlow<List<UserEntity>> = repository.usersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalUnreadCount: StateFlow<Int?> = repository.totalUnreadCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isBlockedByAdmin: StateFlow<Boolean> = GlobalSecurityEventBus.isBlocked
    val serverStatus: StateFlow<ServerStatus> = GlobalSecurityEventBus.serverStatus
    val wsConnected: StateFlow<Boolean> = app.signalingClient.isConnected

    private val _loginFlowState = MutableStateFlow(LoginFlowState())
    val loginFlowState: StateFlow<LoginFlowState> = _loginFlowState.asStateFlow()

    private val _directorySyncing = MutableStateFlow(false)
    val directorySyncing: StateFlow<Boolean> = _directorySyncing.asStateFlow()

    private val _pickerContacts = MutableStateFlow<List<DeviceContact>>(emptyList())
    val pickerContacts: StateFlow<List<DeviceContact>> = _pickerContacts.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _remoteSearchResults = MutableStateFlow<List<UserEntity>>(emptyList())
    val remoteSearchResults: StateFlow<List<UserEntity>> = _remoteSearchResults.asStateFlow()

    private var countdownJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val deviceId = repository.getDeviceLockId()
            _loginFlowState.update { it.copy(deviceId = deviceId) }
            repository.warmUpServer()
        }
        viewModelScope.launch {
            repository.sessionFlow.collect { session ->
                _authResolved.value = true
                if (session != null) {
                    app.signalingClient.connect()
                    app.startBackgroundSignalingService()
                    refreshDirectory()
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _remoteSearchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300L)
            _remoteSearchResults.value = repository.searchUsers(query)
        }
    }

    fun sendOtp(phone: String) {
        val normalizedPhone = com.example.data.repository.PhoneAuthUtils.normalizePhoneNumber(phone)
        viewModelScope.launch {
            val deviceId = _loginFlowState.value.deviceId.ifBlank { repository.getDeviceLockId() }
            _loginFlowState.update {
                it.copy(
                    phone = normalizedPhone,
                    deviceId = deviceId,
                    isLoading = true,
                    errorMessage = null,
                    infoMessage = null
                )
            }
            val result = repository.sendOtp(normalizedPhone)
            result.onSuccess { resp ->
                val resolvedBotUrl = resp.resolvedTelegramBotUrl() ?: _loginFlowState.value.telegramBotUrl
                _loginFlowState.update {
                    it.copy(
                        step = OtpLoginStep.OTP_VERIFICATION,
                        telegramBotUrl = resolvedBotUrl,
                        isLoading = false,
                        errorMessage = null,
                        infoMessage = resp.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "OTP sent to your WhatsApp number ($normalizedPhone)."
                    )
                }
                startOtpCountdown(resp.resolvedExpiresInSeconds().coerceIn(30, 120))
            }.onFailure { err ->
                _loginFlowState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Failed to send OTP."
                    )
                }
            }
        }
    }

    fun verifyOtp(otp: String) {
        val normalizedPhone = com.example.data.repository.PhoneAuthUtils.normalizePhoneNumber(
            _loginFlowState.value.phone
        )
        val cleanOtp = otp.trim()
        viewModelScope.launch {
            _loginFlowState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.verifyOtp(normalizedPhone, cleanOtp)
            result.onSuccess { resp ->
                countdownJob?.cancel()
                if (resp.canCompleteExistingLoginDirectly()) {
                    val directResult = repository.completeVerifiedLogin(normalizedPhone, resp)
                    directResult.onSuccess {
                        val deviceId = _loginFlowState.value.deviceId.ifBlank { repository.getDeviceLockId() }
                        _loginFlowState.value = LoginFlowState(deviceId = deviceId)
                        return@launch
                    }.onFailure { directErr ->
                        if (directErr is SecurityException) {
                            _loginFlowState.update {
                                it.copy(isLoading = false, errorMessage = directErr.message)
                            }
                            return@launch
                        }
                    }
                }

                val cachedUser = repository.findCachedUserProfile(normalizedPhone, resp.resolvedUserId())
                val derivedId = resp.resolvedUserId()
                    ?: cachedUser?.userId?.takeIf { it.isNotBlank() }
                    ?: ("u_" + normalizedPhone.filter { it.isDigit() }.ifBlank { normalizedPhone })
                val prefilledName = resp.resolvedDisplayName()
                    ?: cachedUser?.displayName?.takeIf { it.isNotBlank() && it != derivedId }
                    ?: ""
                val prefilledAvatar = resp.resolvedAvatarUrl() ?: cachedUser?.avatarUrl
                _loginFlowState.update {
                    it.copy(
                        step = OtpLoginStep.PROFILE_SETUP,
                        phone = normalizedPhone,
                        verifiedUserId = derivedId,
                        verifiedToken = resp.resolvedToken(),
                        prefilledName = prefilledName,
                        avatarUrl = prefilledAvatar,
                        telegramBotUrl = null,
                        isLoading = false,
                        errorMessage = null,
                        infoMessage = "Phone verified! Enter your display name to continue."
                    )
                }
            }.onFailure { err ->
                if (com.example.data.repository.PhoneAuthUtils.isProfileSetupRequiredError(null, err.message)) {
                    countdownJob?.cancel()
                    val cachedUser = repository.findCachedUserProfile(normalizedPhone, null)
                    val derivedId = cachedUser?.userId?.takeIf { it.isNotBlank() }
                        ?: ("u_" + normalizedPhone.filter { it.isDigit() }.ifBlank { normalizedPhone })
                    val prefilledName = cachedUser?.displayName?.takeIf { it.isNotBlank() && it != derivedId }.orEmpty()
                    _loginFlowState.update {
                        it.copy(
                            step = OtpLoginStep.PROFILE_SETUP,
                            phone = normalizedPhone,
                            verifiedUserId = derivedId,
                            prefilledName = prefilledName,
                            avatarUrl = cachedUser?.avatarUrl,
                            telegramBotUrl = null,
                            isLoading = false,
                            errorMessage = null,
                            infoMessage = "Phone verified! Enter your display name to continue."
                        )
                    }
                } else {
                    _loginFlowState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.message ?: "Invalid OTP code."
                        )
                    }
                }
            }
        }
    }

    fun resetLoginToPhoneStep() {
        countdownJob?.cancel()
        _loginFlowState.update {
            it.copy(
                step = OtpLoginStep.PHONE_ENTRY,
                telegramBotUrl = null,
                resendCountdownSeconds = 0,
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun uploadProfilePhoto(uri: Uri) {
        val hint = _loginFlowState.value.verifiedUserId.ifBlank { _loginFlowState.value.phone }
        viewModelScope.launch {
            _loginFlowState.update {
                it.copy(
                    localAvatarPreviewUri = uri.toString(),
                    isAvatarUploading = true,
                    errorMessage = null
                )
            }
            val result = repository.uploadAvatarPhoto(uri, hint)
            result.onSuccess { url ->
                _loginFlowState.update {
                    it.copy(avatarUrl = url, isAvatarUploading = false)
                }
            }.onFailure { err ->
                _loginFlowState.update {
                    it.copy(
                        isAvatarUploading = false,
                        errorMessage = err.message ?: "Photo upload failed; you can skip for now."
                    )
                }
            }
        }
    }

    fun clearProfilePhoto() {
        _loginFlowState.update {
            it.copy(avatarUrl = null, localAvatarPreviewUri = null, isAvatarUploading = false)
        }
    }

    fun registerAndLogin(
        userId: String,
        displayName: String,
        phone: String,
        deviceId: String,
        avatarUrl: String?
    ) {
        viewModelScope.launch {
            _loginFlowState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.registerOrLogin(
                userId = userId,
                displayName = displayName,
                phone = phone,
                deviceId = deviceId,
                avatarUrl = avatarUrl,
                verifiedToken = _loginFlowState.value.verifiedToken
            )
            result.onSuccess {
                _loginFlowState.value = LoginFlowState(deviceId = deviceId)
            }.onFailure { err ->
                _loginFlowState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Unable to complete registration."
                    )
                }
            }
        }
    }

    fun clearLoginError() {
        _loginFlowState.update { it.copy(errorMessage = null) }
    }

    private fun startOtpCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining >= 0) {
                _loginFlowState.update { it.copy(resendCountdownSeconds = remaining) }
                if (remaining == 0) break
                delay(1000L)
                remaining--
            }
        }
    }

    fun refreshDirectory() {
        viewModelScope.launch {
            _directorySyncing.value = true
            repository.syncUserDirectory()
            _directorySyncing.value = false
        }
    }

    fun openConversation(peerId: String, peerName: String) {
        viewModelScope.launch {
            repository.startOrOpenConversation(peerId, peerName)
        }
    }

    fun loadInAppContactPicker() {
        viewModelScope.launch {
            _pickerContacts.value = contactSyncHelper.loadPickerContacts(directoryUsers.value)
        }
    }

    fun hasContactPermission(): Boolean = contactSyncHelper.hasContactPermission()

    fun isHardwareKeystoreActive(): Boolean = KeystoreCrypto.isHardwareBackedKeyPresent()

    fun dismissBlockedBanner() {
        GlobalSecurityEventBus.clearBlocked()
    }

    fun logout(onLoggedOut: (() -> Unit)? = null) {
        viewModelScope.launch {
            countdownJob?.cancel()
            app.stopBackgroundSignalingService()
            repository.logout()
            GlobalSecurityEventBus.clearBlocked()
            val deviceId = repository.getDeviceLockId()
            _loginFlowState.value = LoginFlowState(deviceId = deviceId)
            onLoggedOut?.invoke()
        }
    }
}
