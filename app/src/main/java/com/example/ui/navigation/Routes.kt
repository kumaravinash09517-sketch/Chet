package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object LoginRoute

@Serializable
data object HomeRoute

@Serializable
data class ChatRoute(
    val peerId: String,
    val peerName: String
)

@Serializable
data class CallRoute(
    val peerId: String,
    val peerName: String,
    val isVideo: Boolean,
    val isIncoming: Boolean
)

@Serializable
data object SettingsRoute
