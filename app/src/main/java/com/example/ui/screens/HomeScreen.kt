package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.security.ServerStatus
import com.example.core.security.UserSession
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageStatus
import com.example.data.local.UserEntity
import com.example.platform.DeviceContact
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.ReadCheckEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: UserSession,
    conversations: List<ConversationEntity>,
    directoryUsers: List<UserEntity>,
    remoteSearchResults: List<UserEntity>,
    pickerContacts: List<DeviceContact>,
    totalUnreadCount: Int,
    searchQuery: String,
    isDirectorySyncing: Boolean,
    serverStatus: ServerStatus,
    wsConnected: Boolean,
    hasContactPermission: () -> Boolean,
    onSearchQueryChange: (String) -> Unit,
    onRefreshDirectory: () -> Unit,
    onLoadContactPicker: () -> Unit,
    onOpenChat: (peerId: String, peerName: String) -> Unit,
    onOpenProfileSettings: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showContactSheet by remember { mutableStateOf(false) }
    var showContactRationale by remember { mutableStateOf(false) }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onLoadContactPicker()
            showContactSheet = true
        }
    }

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter {
            it.peerName.contains(searchQuery, ignoreCase = true) ||
                it.peerPhone.contains(searchQuery, ignoreCase = true) ||
                it.peerId.contains(searchQuery, ignoreCase = true) ||
                it.lastMessagePreview.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredUsers = remember(directoryUsers, remoteSearchResults, searchQuery) {
        val merged = (directoryUsers + remoteSearchResults)
            .distinctBy { it.userId }
            .sortedByDescending { it.lastLoginMillis }
        if (searchQuery.isBlank()) merged
        else merged.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.phone.contains(searchQuery, ignoreCase = true) ||
                it.userId.contains(searchQuery, ignoreCase = true)
        }
    }

    val peopleSearchMatches = remember(filteredUsers, filteredConversations, searchQuery, session.userId) {
        if (searchQuery.isBlank()) emptyList()
        else {
            val existingPeerIds = filteredConversations.map { it.peerId }.toSet()
            filteredUsers.filter { it.userId != session.userId && it.userId !in existingPeerIds }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Hello Sir",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(
                                    if (wsConnected) OnlineGreen
                                    else MaterialTheme.colorScheme.tertiary
                                )
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefreshDirectory,
                        modifier = Modifier.testTag("refresh_directory_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Sync Directory"
                        )
                    }
                    // Hidden settings trigger: tapping user's profile avatar opens ProfileSettingsScreen
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .clickable(onClick = onOpenProfileSettings)
                            .testTag("profile_identity_trigger")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            UserAvatarCircle(
                                name = session.displayName,
                                avatarUrl = session.avatarUrl,
                                sizeDp = 28
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = session.displayName.substringBefore(" "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (totalUnreadCount > 0) {
                                    Badge { Text(totalUnreadCount.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Chat, contentDescription = "Chats")
                        }
                    },
                    label = { Text("Chats") },
                    modifier = Modifier.testTag("tab_chats")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        onRefreshDirectory()
                    },
                    icon = {
                        Icon(Icons.Rounded.Group, contentDescription = "Registered Users")
                    },
                    label = { Text("Directory (${directoryUsers.size})") },
                    modifier = Modifier.testTag("tab_directory")
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (hasContactPermission()) {
                        onLoadContactPicker()
                        showContactSheet = true
                    } else {
                        showContactRationale = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("open_contact_picker_fab")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Contacts,
                    contentDescription = "In-App Contact Picker"
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isDirectorySyncing || serverStatus == ServerStatus.WAKING_UP) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text("Search by phone number, name, or ID…")
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("home_search_input")
            )

            if (selectedTab == 0) {
                if (filteredConversations.isEmpty() && peopleSearchMatches.isEmpty()) {
                    EmptyHomeState(
                        title = if (searchQuery.isBlank()) "No conversations yet" else "No matching chats or users",
                        subtitle = if (searchQuery.isBlank()) {
                            "Search by phone number or name above, open Directory, or tap the Contact Picker."
                        } else {
                            "Try another phone number, name, or user ID."
                        }
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 88.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = filteredConversations,
                            key = { "conv_${it.peerId}" }
                        ) { conv ->
                            ConversationRow(
                                conversation = conv,
                                onClick = { onOpenChat(conv.peerId, conv.peerName) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                        }

                        if (peopleSearchMatches.isNotEmpty()) {
                            item(key = "people_results_header") {
                                Text(
                                    text = "People Found",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                            items(
                                items = peopleSearchMatches,
                                key = { "search_${it.userId}" }
                            ) { user ->
                                RegisteredUserRow(
                                    user = user,
                                    isSelf = false,
                                    onClick = { onOpenChat(user.userId, user.displayName) }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                )
                            }
                        }
                    }
                }
            } else {
                if (filteredUsers.isEmpty()) {
                    EmptyHomeState(
                        title = "Directory empty",
                        subtitle = "Tap Refresh or search by phone number to sync registered users."
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 88.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = filteredUsers,
                            key = { it.userId }
                        ) { user ->
                            RegisteredUserRow(
                                user = user,
                                isSelf = user.userId == session.userId,
                                onClick = {
                                    if (user.userId != session.userId) {
                                        onOpenChat(user.userId, user.displayName)
                                    } else {
                                        onOpenProfileSettings()
                                    }
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showContactRationale) {
        AlertDialog(
            onDismissRequest = { showContactRationale = false },
            title = { Text(stringResource(R.string.contact_rationale_title)) },
            text = { Text(stringResource(R.string.contact_rationale_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showContactRationale = false
                        contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactRationale = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showContactSheet) {
        ModalBottomSheet(onDismissRequest = { showContactSheet = false }) {
            ContactPickerSheetContent(
                contacts = pickerContacts,
                onSelectContact = { contact ->
                    showContactSheet = false
                    val target = contact.matchedDirectoryUser
                    if (target != null) {
                        onOpenChat(target.userId, target.displayName)
                    }
                }
            )
        }
    }
}

@Composable
fun UserAvatarCircle(
    name: String,
    avatarUrl: String?,
    sizeDp: Int
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.take(1).uppercase().ifBlank { "?" },
                style = if (sizeDp >= 48) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationEntity,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("chat_row_${conversation.peerId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatarCircle(
            name = conversation.peerName,
            avatarUrl = conversation.peerAvatarUrl,
            sizeDp = 50
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.peerName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatShortTime(conversation.lastMessageTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.unreadCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (conversation.isPeerTyping) {
                        Text(
                            text = "typing…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        if (conversation.lastMessageIsMine && conversation.lastMessageId != null) {
                            MessageStatusIcon(status = conversation.lastMessageStatus)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = conversation.lastMessagePreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisteredUserRow(
    user: UserEntity,
    isSelf: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("directory_user_${user.userId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(50.dp)) {
            UserAvatarCircle(
                name = user.displayName,
                avatarUrl = user.avatarUrl,
                sizeDp = 50
            )
            if (user.isOnline) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(OnlineGreen)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.displayName + if (isSelf) " (You)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = user.phone.ifBlank { "@${user.userId}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "Last login: ${user.lastLoginFormatted}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ContactPickerSheetContent(
    contacts: List<DeviceContact>,
    onSelectContact: (DeviceContact) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts
        else contacts.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.phoneNumber.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "In-App Contact Picker",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = "Select a contact to open a matched Hello Sir conversation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search device contacts…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (filtered.isEmpty()) {
            Text(
                text = "No contacts found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.height(340.dp)) {
                items(filtered, key = { it.id }) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectContact(contact) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = contact.name.take(1).uppercase(),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = contact.phoneNumber,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (contact.matchedDirectoryUser != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "On Hello Sir",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHomeState(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MessageStatusIcon(status: MessageStatus, tintOverride: androidx.compose.ui.graphics.Color? = null) {
    when (status) {
        MessageStatus.PENDING -> Icon(
            imageVector = Icons.Rounded.Schedule,
            contentDescription = "Pending",
            tint = tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        MessageStatus.SENT -> Icon(
            imageVector = Icons.Rounded.Done,
            contentDescription = "Sent",
            tint = tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        MessageStatus.DELIVERED -> Icon(
            imageVector = Icons.Rounded.DoneAll,
            contentDescription = "Delivered",
            tint = tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        MessageStatus.READ -> Icon(
            imageVector = Icons.Rounded.DoneAll,
            contentDescription = "Read",
            tint = ReadCheckEmerald,
            modifier = Modifier.size(16.dp)
        )
    }
}

fun formatShortTime(millis: Long): String {
    if (millis <= 0L) return ""
    return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
}
