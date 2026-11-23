package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.painterResource
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
import com.example.platform.DailyShayari
import com.example.platform.DeviceContact
import com.example.platform.ShayariManager
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.ReadCheckBlue
import com.example.ui.theme.ReadCheckBlueOnBubble
import androidx.compose.ui.graphics.Color
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
    isSearchingUsers: Boolean = false,
    serverStatus: ServerStatus,
    wsConnected: Boolean,
    currentShayari: DailyShayari = ShayariManager.shayariCollection.first(),
    onNextShayari: () -> Unit = {},
    hasContactPermission: () -> Boolean,
    onSearchQueryChange: (String) -> Unit,
    onRefreshDirectory: () -> Unit,
    onLoadContactPicker: () -> Unit,
    onOpenChat: (peerId: String, peerName: String) -> Unit,
    onOpenProfileSettings: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var shayariExpanded by rememberSaveable { mutableStateOf(true) }
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
        else {
            val q = searchQuery.trim()
            val qNoAt = q.removePrefix("@").trim().ifBlank { q }
            val qDigits = qNoAt.filter { it.isDigit() }
            conversations
                .filter {
                    it.peerName.contains(qNoAt, ignoreCase = true) ||
                        it.peerPhone.contains(qNoAt, ignoreCase = true) ||
                        it.peerId.contains(qNoAt, ignoreCase = true) ||
                        it.lastMessagePreview.contains(qNoAt, ignoreCase = true) ||
                        (qDigits.length >= 4 && (
                            it.peerPhone.filter { ch -> ch.isDigit() }.contains(qDigits) ||
                                it.peerId.filter { ch -> ch.isDigit() }.contains(qDigits)
                            ))
                }
                .sortedWith(
                    compareByDescending<ConversationEntity> {
                        it.peerId.equals(qNoAt, ignoreCase = true) || it.peerName.equals(qNoAt, ignoreCase = true)
                    }.thenByDescending {
                        it.peerId.startsWith(qNoAt, ignoreCase = true) || it.peerName.startsWith(qNoAt, ignoreCase = true)
                    }.thenByDescending { it.lastMessageTimestamp }
                )
        }
    }

    val filteredUsers = remember(directoryUsers, remoteSearchResults, searchQuery) {
        val merged = (remoteSearchResults + directoryUsers)
            .distinctBy { it.userId.lowercase() }
            .sortedByDescending { it.lastLoginMillis }
        if (searchQuery.isBlank()) merged
        else {
            val q = searchQuery.trim()
            val qNoAt = q.removePrefix("@").trim().ifBlank { q }
            val qDigits = qNoAt.filter { it.isDigit() }
            val remoteIds = remoteSearchResults.map { it.userId.lowercase() }.toSet()
            merged
                .filter { user ->
                    user.userId.lowercase() in remoteIds ||
                        user.displayName.contains(qNoAt, ignoreCase = true) ||
                        user.phone.contains(qNoAt, ignoreCase = true) ||
                        user.userId.contains(qNoAt, ignoreCase = true) ||
                        (qDigits.length >= 4 && (
                            user.phone.filter { ch -> ch.isDigit() }.contains(qDigits) ||
                                user.userId.filter { ch -> ch.isDigit() }.contains(qDigits)
                            ))
                }
                .sortedWith(
                    compareByDescending<UserEntity> {
                        it.userId.equals(qNoAt, ignoreCase = true) || it.displayName.equals(qNoAt, ignoreCase = true)
                    }.thenByDescending {
                        it.userId.startsWith(qNoAt, ignoreCase = true) || it.displayName.startsWith(qNoAt, ignoreCase = true)
                    }.thenByDescending { it.lastLoginMillis }
                )
        }
    }

    val peopleSearchMatches = remember(filteredUsers, filteredConversations, searchQuery, session.userId) {
        if (searchQuery.isBlank()) emptyList()
        else {
            val existingPeerIds = filteredConversations.map { it.peerId.lowercase() }.toSet()
            filteredUsers.filter {
                !it.userId.equals(session.userId, ignoreCase = true) &&
                    it.userId.lowercase() !in existingPeerIds
            }
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
                        Image(
                            painter = painterResource(id = R.drawable.hello_sir_badge),
                            contentDescription = "Hello Sir Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
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
                                    Badge(
                                        containerColor = CrimsonAlert,
                                        contentColor = Color.White,
                                        modifier = Modifier.testTag("chats_tab_unread_badge")
                                    ) {
                                        Text(
                                            text = if (totalUnreadCount > 99) "99+" else totalUnreadCount.toString(),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Chat, contentDescription = "Chats")
                        }
                    },
                    label = {
                        Text(if (totalUnreadCount > 0) "Chats ($totalUnreadCount)" else "Chats")
                    },
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
            if (isDirectorySyncing || isSearchingUsers || serverStatus == ServerStatus.WAKING_UP) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LaunchedEffect(selectedTab) {
                if (selectedTab == 1) {
                    onRefreshDirectory()
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text("Search by name, user ID, or phone…")
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
                if (searchQuery.isBlank()) {
                    DailyShayariCard(
                        shayari = currentShayari,
                        expanded = shayariExpanded,
                        onToggleExpand = { shayariExpanded = !shayariExpanded },
                        onNextShayari = onNextShayari
                    )
                }
                if (filteredConversations.isEmpty() && peopleSearchMatches.isEmpty()) {
                    EmptyHomeState(
                        title = if (searchQuery.isBlank()) "No conversations yet" else "No matching chats or users",
                        subtitle = if (searchQuery.isBlank()) {
                            "Search by name, user ID, or phone above, open Directory, or tap the Contact Picker."
                        } else {
                            "Try another phone number, name, or user ID."
                        }
                    )
                } else {
                    val chatsListState = rememberLazyListState()
                    val topConv = filteredConversations.firstOrNull()
                    val topPeer = topConv?.peerId
                    val topTs = topConv?.lastMessageTimestamp ?: 0L
                    val topUnread = topConv?.unreadCount ?: 0
                    LaunchedEffect(topPeer, topTs) {
                        if (searchQuery.isBlank() &&
                            filteredConversations.isNotEmpty() &&
                            (topUnread > 0 || chatsListState.firstVisibleItemIndex <= 2)
                        ) {
                            chatsListState.animateScrollToItem(0)
                        }
                    }
                    LazyColumn(
                        state = chatsListState,
                        contentPadding = PaddingValues(bottom = 88.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = filteredConversations,
                            key = { "conv_${it.peerId}" }
                        ) { conv ->
                            ConversationRow(
                                conversation = conv,
                                showUserId = searchQuery.isNotBlank(),
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
                    val cleanQuery = searchQuery.trim()
                    when {
                        isDirectorySyncing || isSearchingUsers -> {
                            EmptyHomeState(
                                title = "Searching directory…",
                                subtitle = "Syncing registered users from Hello Sir server."
                            )
                        }
                        cleanQuery.isNotEmpty() -> {
                            EmptyHomeState(
                                title = "No matching user found for \"$cleanQuery\"",
                                subtitle = "Check the phone number, name, or user ID (e.g. u_919455877902), or tap Refresh."
                            )
                        }
                        else -> {
                            EmptyHomeState(
                                title = "Directory empty",
                                subtitle = "Tap Refresh or search by phone number to sync registered users."
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 88.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = filteredUsers,
                            key = { it.userId }
                        ) { user ->
                            val isSelfUser = user.userId.equals(session.userId, ignoreCase = true)
                            RegisteredUserRow(
                                user = user,
                                isSelf = isSelfUser,
                                onClick = {
                                    if (!isSelfUser) {
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
    val normalizedAvatar = remember(avatarUrl) {
        com.example.data.remote.BackendJsonParsers.normalizeMediaUrl(avatarUrl)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (!normalizedAvatar.isNullOrBlank()) {
            AsyncImage(
                model = normalizedAvatar,
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
    showUserId: Boolean = false,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = conversation.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showUserId) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "@${conversation.peerId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatShortTime(conversation.lastMessageTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (conversation.unreadCount > 0) {
                        CrimsonAlert
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
                            fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (conversation.unreadCount > 0) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = CrimsonAlert,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                            .testTag("unread_badge_${conversation.peerId}")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "@${user.userId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            val statusPrefix = when {
                user.isOnline -> "Online"
                user.lastLoginFormatted.isNotBlank() -> "Offline • Last seen: ${user.lastLoginFormatted}"
                else -> "Offline"
            }
            val secondaryMeta = if (user.phone.isNotBlank()) "${user.phone} • $statusPrefix" else statusPrefix
            Text(
                text = secondaryMeta,
                style = MaterialTheme.typography.labelSmall,
                color = if (user.isOnline) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
private fun DailyShayariCard(
    shayari: DailyShayari,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onNextShayari: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("daily_shayari_card")
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleExpand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "आज की शायरी • ${shayari.mood}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onNextShayari,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Next Shayari",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "Collapse Shayari" else "Expand Shayari",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = shayari.lines,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun MessageStatusIcon(
    status: MessageStatus,
    tintOverride: androidx.compose.ui.graphics.Color? = null,
    isOnDarkBubble: Boolean = false
) {
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
            tint = if (isOnDarkBubble) ReadCheckBlueOnBubble else ReadCheckBlue,
            modifier = Modifier.size(16.dp)
        )
    }
}

fun formatShortTime(millis: Long): String {
    if (millis <= 0L) return ""
    return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
}
