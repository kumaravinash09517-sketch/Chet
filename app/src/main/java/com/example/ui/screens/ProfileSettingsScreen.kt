package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.security.UserSession

private val BIO_PRESETS = listOf(
    "Hey there! I am using Hello Sir.",
    "Available",
    "At work",
    "In a meeting",
    "Busy"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    session: UserSession,
    isHardwareKeystoreActive: Boolean,
    wsConnected: Boolean,
    isSavingProfile: Boolean = false,
    profileFeedback: String? = null,
    onBack: () -> Unit,
    onSaveProfile: (displayName: String, bio: String, pickedAvatarUri: Uri?, removeAvatar: Boolean) -> Unit = { _, _, _, _ -> },
    onClearProfileFeedback: () -> Unit = {},
    onLogoutConfirmed: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showConfirmLogout by remember { mutableStateOf(false) }
    var nameInput by rememberSaveable(session.userId, session.displayName) {
        mutableStateOf(session.displayName)
    }
    var bioInput by rememberSaveable(session.userId, session.bio) {
        mutableStateOf(session.bio.ifBlank { UserSession.DEFAULT_BIO })
    }
    var pendingAvatarUri by remember { mutableStateOf<Uri?>(null) }
    var removeAvatarFlag by remember { mutableStateOf(false) }
    var copiedUserId by remember { mutableStateOf(false) }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingAvatarUri = uri
            removeAvatarFlag = false
            onClearProfileFeedback()
        }
    }

    val effectiveAvatarPreview: String? = when {
        removeAvatarFlag -> null
        pendingAvatarUri != null -> pendingAvatarUri.toString()
        else -> session.avatarUrl
    }

    val hasChanges = nameInput.trim() != session.displayName ||
        bioInput.trim() != session.bio ||
        pendingAvatarUri != null ||
        (removeAvatarFlag && session.avatarUrl != null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile & Device Security") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Editable WhatsApp-style Profile Card
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clickable(enabled = !isSavingProfile) {
                                    avatarPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .testTag("profile_edit_avatar_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!effectiveAvatarPreview.isNullOrBlank()) {
                                val normalized = com.example.data.remote.BackendJsonParsers
                                    .normalizeMediaUrl(effectiveAvatarPreview)
                                AsyncImage(
                                    model = normalized ?: effectiveAvatarPreview,
                                    contentDescription = "Profile photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            } else {
                                UserAvatarCircle(
                                    name = nameInput.ifBlank { session.displayName },
                                    avatarUrl = null,
                                    sizeDp = 92
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(32.dp)
                                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.PhotoCamera,
                                        contentDescription = "Change profile photo",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        if (!effectiveAvatarPreview.isNullOrBlank()) {
                            TextButton(
                                onClick = {
                                    pendingAvatarUri = null
                                    removeAvatarFlag = true
                                    onClearProfileFeedback()
                                },
                                enabled = !isSavingProfile
                            ) {
                                Text("Remove Photo")
                            }
                        }

                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = {
                                nameInput = it
                                onClearProfileFeedback()
                            },
                            label = { Text("Display Name") },
                            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profile_edit_name_input")
                        )

                        OutlinedTextField(
                            value = bioInput,
                            onValueChange = {
                                bioInput = it
                                onClearProfileFeedback()
                            },
                            label = { Text("About / Bio") },
                            placeholder = { Text(UserSession.DEFAULT_BIO) },
                            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                            maxLines = 2,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profile_edit_bio_input")
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BIO_PRESETS.forEach { preset ->
                                AssistChip(
                                    onClick = {
                                        bioInput = preset
                                        onClearProfileFeedback()
                                    },
                                    label = { Text(preset, maxLines = 1) }
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Badge,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (session.phone.isNotBlank()) {
                                            "@${session.userId} • ${session.phone}"
                                        } else {
                                            "@${session.userId}"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Registered: ${session.registeredAtFormatted}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(session.userId))
                                        copiedUserId = true
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (copiedUserId) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy User ID",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        if (!profileFeedback.isNullOrBlank()) {
                            Text(
                                text = profileFeedback,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (profileFeedback.contains("unable", ignoreCase = true) ||
                                    profileFeedback.contains("fail", ignoreCase = true)
                                ) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                onSaveProfile(
                                    nameInput.trim(),
                                    bioInput.trim().ifBlank { UserSession.DEFAULT_BIO },
                                    pendingAvatarUri,
                                    removeAvatarFlag
                                )
                            },
                            enabled = !isSavingProfile && nameInput.isNotBlank() && hasChanges,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("profile_save_button")
                        ) {
                            if (isSavingProfile) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Saving Profile…")
                            } else {
                                Text("Save Profile Changes", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Hardware Security Card
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Single-Device Lock & Keystore",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        SecurityDetailItem(
                            icon = Icons.Rounded.Lock,
                            label = "Android Keystore AES-256-GCM",
                            value = if (isHardwareKeystoreActive) "Hardware Key Active" else "Software Fallback"
                        )
                        SecurityDetailItem(
                            icon = Icons.Rounded.Fingerprint,
                            label = "Device Lock SHA-256",
                            value = session.deviceLockId.take(24) + "…"
                        )
                        SecurityDetailItem(
                            icon = Icons.Rounded.VerifiedUser,
                            label = "Render Signaling Relay",
                            value = if (wsConnected) "Connected (wss://rendar-ud3i.onrender.com)" else "Reconnecting…"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Logout Button
                Button(
                    onClick = { showConfirmLogout = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("hidden_logout_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sign Out & Release Device Lock",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (showConfirmLogout) {
        AlertDialog(
            onDismissRequest = { showConfirmLogout = false },
            title = { Text("Sign out of Hello Sir?") },
            text = {
                Text("This clears your encrypted Keystore session on this device. Your local chat cache stays safely intact unless you clear app data.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmLogout = false
                        onLogoutConfirmed()
                    }
                ) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmLogout = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SecurityDetailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
