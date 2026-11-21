package com.example.rtc

sealed interface CallState {
    data object Idle : CallState
    data class OutgoingRinging(
        val peerId: String,
        val peerName: String,
        val isVideo: Boolean,
        val isRingingOnRemote: Boolean = false
    ) : CallState

    data class IncomingRinging(
        val callId: String,
        val callerId: String,
        val callerName: String,
        val isVideo: Boolean,
        val offerSdp: String
    ) : CallState

    data class Connecting(
        val peerId: String,
        val peerName: String,
        val isVideo: Boolean
    ) : CallState

    data class Connected(
        val peerId: String,
        val peerName: String,
        val isVideo: Boolean,
        val startedAtMillis: Long = System.currentTimeMillis()
    ) : CallState

    data class Ended(val reason: String) : CallState
}
