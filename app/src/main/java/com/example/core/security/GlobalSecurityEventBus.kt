package com.example.core.security

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SecurityEvent {
    data class BlockedByAdmin(val message: String = "You are blocked by the administrator.") : SecurityEvent
    data class SessionTamperDetected(val reason: String) : SecurityEvent
}

enum class ServerStatus {
    READY,
    WAKING_UP,
    OFFLINE
}

data class IncomingMessageAlert(
    val messageId: String,
    val peerId: String,
    val senderName: String,
    val headline: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface CallNotificationAction {
    data class Decline(val callerId: String) : CallNotificationAction
    data class Answer(
        val callId: String,
        val callerId: String,
        val callerName: String,
        val isVideo: Boolean,
        val offerSdp: String
    ) : CallNotificationAction
}

object GlobalSecurityEventBus {
    private val _events = MutableSharedFlow<SecurityEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SecurityEvent> = _events.asSharedFlow()

    private val _messageAlerts = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 32)
    val messageAlerts: SharedFlow<IncomingMessageAlert> = _messageAlerts.asSharedFlow()

    private val _callActions = MutableSharedFlow<CallNotificationAction>(extraBufferCapacity = 8)
    val callActions: SharedFlow<CallNotificationAction> = _callActions.asSharedFlow()

    private val _isBlocked = MutableStateFlow(false)
    val isBlocked: StateFlow<Boolean> = _isBlocked.asStateFlow()

    private val _serverStatus = MutableStateFlow(ServerStatus.READY)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    fun emitBlocked(message: String = "You are blocked by the administrator.") {
        _isBlocked.value = true
        _events.tryEmit(SecurityEvent.BlockedByAdmin(message))
    }

    fun clearBlocked() {
        _isBlocked.value = false
    }

    fun emitTamper(reason: String) {
        _events.tryEmit(SecurityEvent.SessionTamperDetected(reason))
    }

    fun emitIncomingMessageAlert(alert: IncomingMessageAlert) {
        _messageAlerts.tryEmit(alert)
    }

    fun emitCallAction(action: CallNotificationAction) {
        _callActions.tryEmit(action)
    }

    fun updateServerStatus(status: ServerStatus) {
        _serverStatus.value = status
    }
}
