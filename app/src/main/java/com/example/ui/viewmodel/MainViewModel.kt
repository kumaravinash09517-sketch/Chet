package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.HelloSirApplication
import com.example.core.security.GlobalSecurityEventBus
import com.example.core.security.IncomingMessageAlert
import com.example.core.security.KeystoreCrypto
import com.example.core.security.ServerStatus
import com.example.core.security.UserSession
import com.example.data.local.ConversationEntity
import com.example.data.local.UserEntity
import com.example.platform.DailyShayari
import com.example.platform.DeviceContact
import com.example.platform.ShayariManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

data class LoginFlowState(
    val deviceId: String = "",
    val avatarUrl: String? = null,
    val localAvatarPreviewUri: String? = null,
    val isAvatarUploading: Boolean = false,
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
    val incomingMessageAlerts: SharedFlow<IncomingMessageAlert> = GlobalSecurityEventBus.messageAlerts

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

    private val _isSearchingUsers = MutableStateFlow(false)
    val isSearchingUsers: StateFlow<Boolean> = _isSearchingUsers.asStateFlow()

    private val _currentShayari = MutableStateFlow(ShayariManager.nextShayariForAppOpen(app))
    val currentShayari: StateFlow<DailyShayari> = _currentShayari.asStateFlow()

    private var searchJob: Job? = null

    init {
        ShayariManager.scheduleDailyMorningNotification(app)
        viewModelScope.launch {
            val deviceId = repository.getDeviceLockId()
            _loginFlowState.update { it.copy(deviceId = deviceId) }
            repository.warmUpServer()
        }
        viewModelScope.launch {
            repository.sessionFlow.collect { session ->
                _authResolved.value = true
                if (session != null) {
                    if (app.isUiInForeground) {
                        app.chatRepository.markLocalSelfPresence(true)
                        app.signalingClient.setAppForeground(true)
                    }
                    app.startBackgroundSignalingService()
                    launch { repository.fetchIceServers() }
                    refreshDirectory()
                }
            }
        }
        viewModelScope.launch {
            app.signalingClient.isConnected.collect { connected ->
                if (connected && sessionState.value != null) {
                    refreshDirectory()
                }
            }
        }
    }

    fun rotateDailyShayari() {
        _currentShayari.value = ShayariManager.getShayariAfterIndex(_currentShayari.value.id)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        val trimmed = query.trim().removePrefix("@").trim()
        if (trimmed.isEmpty()) {
            _remoteSearchResults.value = emptyList()
            _isSearchingUsers.value = false
            return
        }
        _isSearchingUsers.value = true
        searchJob = viewModelScope.launch {
            try {
                delay(120L)
                _remoteSearchResults.value = repository.searchUsers(query)
            } finally {
                _isSearchingUsers.value = false
            }
        }
    }

    fun uploadProfilePhoto(uri: Uri) {
        viewModelScope.launch {
            _loginFlowState.update {
                it.copy(
                    localAvatarPreviewUri = uri.toString(),
                    isAvatarUploading = true,
                    errorMessage = null
                )
            }
            val hint = _loginFlowState.value.deviceId.ifBlank { "avatar" }
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

    fun loginDirect(
        username: String,
        userId: String,
        avatarUrl: String?
    ) {
        val cleanName = username.trim()
        val cleanId = userId.trim().replace(" ", "_")
        if (cleanName.isBlank() || cleanId.isBlank()) {
            _loginFlowState.update {
                it.copy(errorMessage = "Please enter both Username and User ID.")
            }
            return
        }
        viewModelScope.launch {
            val deviceId = _loginFlowState.value.deviceId.ifBlank { repository.getDeviceLockId() }
            _loginFlowState.update {
                it.copy(
                    deviceId = deviceId,
                    isLoading = true,
                    errorMessage = null,
                    infoMessage = null
                )
            }
            val result = repository.loginDirect(
                userId = cleanId,
                displayName = cleanName,
                avatarUrl = avatarUrl,
                deviceId = deviceId
            )
            result.onSuccess {
                _loginFlowState.value = LoginFlowState(deviceId = deviceId)
            }.onFailure { err ->
                _loginFlowState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Unable to sign in. Please try again."
                    )
                }
            }
        }
    }

    fun clearLoginError() {
        _loginFlowState.update { it.copy(errorMessage = null) }
    }

    fun refreshDirectory() {
        viewModelScope.launch {
            _directorySyncing.value = true
            repository.syncUserDirectory()
            _directorySyncing.value = false
        }
    }

    fun openConversation(peerId: String, peerName: String) {
        app.activeChatPeerId = peerId
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
            app.stopBackgroundSignalingService()
            repository.logout()
            GlobalSecurityEventBus.clearBlocked()
            val deviceId = repository.getDeviceLockId()
            _loginFlowState.value = LoginFlowState(deviceId = deviceId)
            onLoggedOut?.invoke()
        }
    }
}
