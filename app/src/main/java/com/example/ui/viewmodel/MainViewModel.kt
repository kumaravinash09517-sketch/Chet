package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.HelloSirApplication
import com.example.core.security.GlobalSecurityEventBus
import com.example.core.security.KeystoreCrypto
import com.example.core.security.ServerStatus
import com.example.core.security.UserSession
import com.example.data.local.ConversationEntity
import com.example.data.local.UserEntity
import com.example.data.remote.RetrofitClient
import com.example.platform.DeviceContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object Unauthenticated : AuthUiState
    data class Authenticated(val session: UserSession) : AuthUiState
}

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

    private val _loginLoading = MutableStateFlow(false)
    val loginLoading: StateFlow<Boolean> = _loginLoading.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _directorySyncing = MutableStateFlow(false)
    val directorySyncing: StateFlow<Boolean> = _directorySyncing.asStateFlow()

    private val _pickerContacts = MutableStateFlow<List<DeviceContact>>(emptyList())
    val pickerContacts: StateFlow<List<DeviceContact>> = _pickerContacts.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        viewModelScope.launch {
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
    }

    fun registerAndLogin(userId: String, displayName: String) {
        registerAndLogin(
            userId = userId,
            displayName = displayName,
            phone = "",
            deviceId = "",
            avatarUrl = ""
        )
    }

    fun registerAndLogin(
        userId: String,
        displayName: String,
        phone: String,
        deviceId: String,
        avatarUrl: String
    ) {
        viewModelScope.launch {
            _loginError.value = null
            _loginLoading.value = true
            val result = repository.registerOrLogin(userId, displayName)
            result.onSuccess {
                val payload = buildMap<String, Any> {
                    put("user_id", userId)
                    put("name", displayName)
                    if (phone.isNotBlank()) put("phone", phone)
                    if (deviceId.isNotBlank()) put("device_id", deviceId)
                    if (avatarUrl.isNotBlank()) put("avatar_url", avatarUrl)
                }
                if (payload.size > 2) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            RetrofitClient.api.updateProfile(payload)
                        }
                    }
                }
            }
            _loginLoading.value = false
            result.onFailure { err ->
                _loginError.value = err.message ?: "Unable to register on server."
            }
        }
    }

    fun clearLoginError() {
        _loginError.value = null
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

    fun logout() {
        viewModelScope.launch {
            app.stopBackgroundSignalingService()
            repository.logout()
        }
    }
}
