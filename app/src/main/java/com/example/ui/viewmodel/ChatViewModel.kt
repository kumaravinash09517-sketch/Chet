package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.HelloSirApplication
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.local.MessageType
import com.example.data.local.UserEntity
import com.example.data.repository.MediaTransferProgress
import com.example.platform.DeviceContact
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HelloSirApplication
    private val repository = app.chatRepository
    private val locationHelper = app.locationHelper

    private val _currentPeerId = MutableStateFlow("")
    private val _currentPeerName = MutableStateFlow("")

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isPeerTyping = MutableStateFlow(false)
    val isPeerTyping: StateFlow<Boolean> = _isPeerTyping.asStateFlow()

    private val _liveLocationMessageId = MutableStateFlow<String?>(null)
    val liveLocationMessageId: StateFlow<String?> = _liveLocationMessageId.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _peerUser = MutableStateFlow<UserEntity?>(null)
    val peerUser: StateFlow<UserEntity?> = _peerUser.asStateFlow()

    val mediaTransferProgress: StateFlow<Map<String, MediaTransferProgress>> =
        repository.mediaTransferProgress

    val myUserId: StateFlow<String> = repository.sessionFlow
        .combine(MutableStateFlow("")) { session, _ -> session?.userId.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private var messagesJob: Job? = null
    private var conversationJob: Job? = null
    private var peerUserJob: Job? = null
    private var typingTimeoutJob: Job? = null
    private var liveLocationJob: Job? = null
    private var periodicSyncJob: Job? = null

    fun bindConversation(peerId: String, peerName: String) {
        val samePeer = _currentPeerId.value == peerId
        _currentPeerId.value = peerId
        _currentPeerName.value = peerName

        if (!samePeer) {
            messagesJob?.cancel()
            messagesJob = viewModelScope.launch {
                repository.observeMessages(peerId).collect { list ->
                    _messages.value = list
                }
            }

            conversationJob?.cancel()
            conversationJob = viewModelScope.launch {
                repository.conversationsFlow.collect { convs: List<ConversationEntity> ->
                    val conv = convs.firstOrNull { it.peerId == peerId }
                    _isPeerTyping.value = conv?.isPeerTyping == true
                }
            }

            peerUserJob?.cancel()
            peerUserJob = viewModelScope.launch {
                repository.observeUser(peerId).collect { user ->
                    _peerUser.value = user
                }
            }

            periodicSyncJob?.cancel()
            periodicSyncJob = viewModelScope.launch {
                while (isActive) {
                    delay(3_500L)
                    repository.retryPendingMessages(peerId)
                    repository.syncMessagesWithServer(peerId)
                }
            }
        }

        viewModelScope.launch {
            repository.startOrOpenConversation(peerId, peerName)
        }
    }

    fun markRead() {
        val peerId = _currentPeerId.value
        if (peerId.isNotBlank()) {
            viewModelScope.launch {
                repository.markConversationAsRead(peerId)
            }
        }
    }

    fun onComposerTextChanged(text: String) {
        val peerId = _currentPeerId.value
        if (peerId.isBlank()) return
        typingTimeoutJob?.cancel()
        if (text.isNotBlank()) {
            viewModelScope.launch {
                repository.sendTypingIndicator(peerId, true)
            }
            typingTimeoutJob = viewModelScope.launch {
                delay(2_200L)
                repository.sendTypingIndicator(peerId, false)
            }
        } else {
            viewModelScope.launch {
                repository.sendTypingIndicator(peerId, false)
            }
        }
    }

    fun sendText(text: String) {
        val clean = text.trim()
        val peerId = _currentPeerId.value
        val peerName = _currentPeerName.value
        if (clean.isBlank() || peerId.isBlank()) return
        typingTimeoutJob?.cancel()
        viewModelScope.launch {
            repository.sendTypingIndicator(peerId, false)
            repository.sendTextMessage(peerId, peerName, clean)
        }
    }

    fun sendMedia(uri: Uri, type: MessageType, caption: String = "") {
        val peerId = _currentPeerId.value
        val peerName = _currentPeerName.value
        if (peerId.isBlank()) return
        viewModelScope.launch {
            repository.sendMediaMessage(peerId, peerName, uri, type, caption)
        }
    }

    fun ensureVideoDownloaded(message: MessageEntity) {
        val remoteUrl = message.mediaUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            repository.ensureVideoDownloaded(
                messageId = message.id,
                mediaUrl = remoteUrl,
                fileName = message.fileName
            )
        }
    }

    fun sendContact(contact: DeviceContact) {
        val peerId = _currentPeerId.value
        val peerName = _currentPeerName.value
        if (peerId.isBlank()) return
        viewModelScope.launch {
            repository.sendContactCard(peerId, peerName, contact.name, contact.phoneNumber)
        }
    }

    fun shareCurrentLocation() {
        val peerId = _currentPeerId.value
        val peerName = _currentPeerName.value
        if (peerId.isBlank()) return
        viewModelScope.launch {
            _statusBanner.value = "Acquiring GPS fix…"
            val loc = locationHelper.getCurrentLocation()
            _statusBanner.value = null
            if (loc != null) {
                repository.sendLocationMessage(
                    peerId = peerId,
                    peerName = peerName,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    isLive = false,
                    label = "Current GPS Location (±${loc.accuracy.toInt()}m)"
                )
            } else {
                _statusBanner.value = "Unable to acquire GPS fix. Check location services."
            }
        }
    }

    fun startLiveLocationSharing() {
        val peerId = _currentPeerId.value
        val peerName = _currentPeerName.value
        if (peerId.isBlank()) return
        liveLocationJob?.cancel()
        liveLocationJob = viewModelScope.launch {
            _statusBanner.value = "Starting Live GPS stream…"
            val initial = locationHelper.getCurrentLocation()
            _statusBanner.value = null
            if (initial == null) {
                _statusBanner.value = "GPS signal unavailable."
                return@launch
            }
            val msgId = repository.sendLocationMessage(
                peerId = peerId,
                peerName = peerName,
                latitude = initial.latitude,
                longitude = initial.longitude,
                isLive = true,
                label = "Live GPS Tracking Active"
            ) ?: return@launch
            _liveLocationMessageId.value = msgId

            locationHelper.observeLiveLocation(5_000L).collect { loc ->
                repository.updateLiveLocation(
                    messageId = msgId,
                    peerId = peerId,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    active = true
                )
            }
        }
    }

    fun stopLiveLocationSharing() {
        val msgId = _liveLocationMessageId.value
        val peerId = _currentPeerId.value
        liveLocationJob?.cancel()
        liveLocationJob = null
        _liveLocationMessageId.value = null
        if (msgId != null && peerId.isNotBlank()) {
            viewModelScope.launch {
                val msg = _messages.value.firstOrNull { it.id == msgId }
                repository.updateLiveLocation(
                    messageId = msgId,
                    peerId = peerId,
                    latitude = msg?.latitude ?: 0.0,
                    longitude = msg?.longitude ?: 0.0,
                    active = false
                )
            }
        }
    }

    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch {
            if (message.id == _liveLocationMessageId.value) {
                stopLiveLocationSharing()
            }
            repository.deleteMessage(message)
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    override fun onCleared() {
        liveLocationJob?.cancel()
        periodicSyncJob?.cancel()
        peerUserJob?.cancel()
        super.onCleared()
    }
}
