package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.core.security.ServerStatus
import com.example.ui.viewmodel.LoginFlowState
import com.example.ui.viewmodel.OtpLoginStep

@Composable
fun LoginScreen(
    flowState: LoginFlowState,
    serverStatus: ServerStatus,
    onSendOtp: (phone: String) -> Unit,
    onVerifyOtp: (otp: String) -> Unit,
    onChangePhone: () -> Unit,
    onPickAvatarPhoto: (Uri) -> Unit,
    onSkipAvatarPhoto: () -> Unit,
    onCompleteRegistration: (
        userId: String,
        displayName: String,
        phone: String,
        deviceId: String,
        avatarUrl: String?
    ) -> Unit,
    onClearError: () -> Unit
) {
    val context = LocalContext.current
    var phoneInput by rememberSaveable { mutableStateOf(flowState.phone) }
    var otpInput by rememberSaveable { mutableStateOf("") }
    var displayNameInput by rememberSaveable { mutableStateOf(flowState.prefilledName) }
    var userIdInput by rememberSaveable { mutableStateOf(flowState.verifiedUserId) }
    var skippedAvatar by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(flowState.step, flowState.verifiedUserId, flowState.prefilledName) {
        if (flowState.step == OtpLoginStep.PHONE_ENTRY) {
            otpInput = ""
        }
        if (flowState.step == OtpLoginStep.PROFILE_SETUP) {
            if (flowState.verifiedUserId.isNotBlank()) {
                userIdInput = flowState.verifiedUserId
            }
            if (displayNameInput.isBlank() && flowState.prefilledName.isNotBlank()) {
                displayNameInput = flowState.prefilledName
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            skippedAvatar = false
            onPickAvatarPhoto(uri)
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        containerColor.copy(alpha = 0.28f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .systemBarsPadding()
            .imePadding()
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            val center = Offset(size.width * 0.85f, size.height * 0.25f)
            drawCircle(
                color = primaryColor.copy(alpha = 0.08f),
                radius = 120.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = primaryColor.copy(alpha = 0.05f),
                radius = 190.dp.toPx(),
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(68.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.hello_sir_badge),
                        contentDescription = "Hello Sir Brand Logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Hello Sir",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Phone-verified messaging, WebRTC calls, and live GPS bound to your single-device Keystore.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                AnimatedVisibility(visible = serverStatus == ServerStatus.WAKING_UP) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Waking secure Render server…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        // Step Progress Pill
                        StepHeaderRow(currentStep = flowState.step)
                        Spacer(modifier = Modifier.height(16.dp))

                        AnimatedContent(
                            targetState = flowState.step,
                            label = "otp_login_step"
                        ) { step ->
                            when (step) {
                                OtpLoginStep.PHONE_ENTRY -> {
                                    PhoneEntrySection(
                                        phone = phoneInput,
                                        deviceId = flowState.deviceId,
                                        isLoading = flowState.isLoading,
                                        errorMessage = flowState.errorMessage,
                                        onPhoneChange = {
                                            phoneInput = it
                                            if (flowState.errorMessage != null) onClearError()
                                        },
                                        onSubmit = { onSendOtp(phoneInput) }
                                    )
                                }

                                OtpLoginStep.OTP_VERIFICATION -> {
                                    OtpVerificationSection(
                                        phone = flowState.phone,
                                        otp = otpInput,
                                        resendSeconds = flowState.resendCountdownSeconds,
                                        telegramBotUrl = flowState.telegramBotUrl,
                                        isLoading = flowState.isLoading,
                                        infoMessage = flowState.infoMessage,
                                        errorMessage = flowState.errorMessage,
                                        onOtpChange = { rawOtp ->
                                            val cleaned = rawOtp.trim().filter { ch -> ch.isDigit() }.take(6)
                                            otpInput = cleaned
                                            if (flowState.errorMessage != null) onClearError()
                                        },
                                        onOpenTelegramBot = { botUrl ->
                                            runCatching {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(botUrl))
                                                context.startActivity(intent)
                                            }.onFailure {
                                                Toast.makeText(
                                                    context,
                                                    "Unable to open Telegram link on this device.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        onVerify = { onVerifyOtp(otpInput) },
                                        onResend = { onSendOtp(flowState.phone) },
                                        onChangePhone = {
                                            otpInput = ""
                                            onChangePhone()
                                        }
                                    )
                                }

                                OtpLoginStep.PROFILE_SETUP -> {
                                    ProfileSetupSection(
                                        phone = flowState.phone,
                                        deviceId = flowState.deviceId,
                                        userId = userIdInput,
                                        displayName = displayNameInput,
                                        avatarPreview = if (skippedAvatar) null else (flowState.localAvatarPreviewUri ?: flowState.avatarUrl),
                                        isAvatarUploading = flowState.isAvatarUploading,
                                        isSkipped = skippedAvatar,
                                        isLoading = flowState.isLoading,
                                        infoMessage = flowState.infoMessage,
                                        errorMessage = flowState.errorMessage,
                                        onUserIdChange = {
                                            userIdInput = it
                                            if (flowState.errorMessage != null) onClearError()
                                        },
                                        onDisplayNameChange = {
                                            displayNameInput = it
                                            if (flowState.errorMessage != null) onClearError()
                                        },
                                        onPickPhotoClick = {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        onSkipPhotoClick = {
                                            skippedAvatar = true
                                            onSkipAvatarPhoto()
                                        },
                                        onComplete = {
                                            val finalAvatar = if (skippedAvatar) null else flowState.avatarUrl
                                            onCompleteRegistration(
                                                userIdInput,
                                                displayNameInput,
                                                flowState.phone,
                                                flowState.deviceId,
                                                finalAvatar
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeaderRow(currentStep: OtpLoginStep) {
    val stepIndex = when (currentStep) {
        OtpLoginStep.PHONE_ENTRY -> 1
        OtpLoginStep.OTP_VERIFICATION -> 2
        OtpLoginStep.PROFILE_SETUP -> 3
    }
    val stepTitle = when (currentStep) {
        OtpLoginStep.PHONE_ENTRY -> "Phone Verification"
        OtpLoginStep.OTP_VERIFICATION -> "Enter OTP Code"
        OtpLoginStep.PROFILE_SETUP -> "Complete Profile"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stepTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = "Step $stepIndex of 3",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PhoneEntrySection(
    phone: String,
    deviceId: String,
    isLoading: Boolean,
    errorMessage: String?,
    onPhoneChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column {
        Text(
            text = "Sign in with your phone number. Your session locks exclusively to this hardware device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text("Phone Number") },
            placeholder = { Text("+91 9876543210") },
            leadingIcon = { Icon(Icons.Rounded.Phone, contentDescription = "Phone Number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (!isLoading && phone.trim().length >= 6) onSubmit() }
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_phone_input")
        )

        if (deviceId.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Device Lock: ${deviceId.take(16)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = onSubmit,
            enabled = !isLoading && phone.trim().length >= 6,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("send_otp_button")
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Sending OTP…")
            } else {
                Text("Send Verification Code", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun OtpVerificationSection(
    phone: String,
    otp: String,
    resendSeconds: Int,
    telegramBotUrl: String?,
    isLoading: Boolean,
    infoMessage: String?,
    errorMessage: String?,
    onOtpChange: (String) -> Unit,
    onOpenTelegramBot: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onChangePhone: () -> Unit
) {
    Column {
        Text(
            text = infoMessage ?: "OTP sent to your WhatsApp number ($phone).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!telegramBotUrl.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { onOpenTelegramBot(telegramBotUrl) },
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_telegram_otp_bot_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = "Open Telegram OTP Bot",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Telegram पर OTP पाएँ (Open Bot)",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Opens your private OTP bot — only your own verification code is sent.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = otp,
            onValueChange = onOtpChange,
            label = { Text("OTP Code") },
            placeholder = { Text("6-digit code") },
            leadingIcon = { Icon(Icons.Rounded.Pin, contentDescription = "OTP") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (!isLoading && otp.trim().length >= 4) onVerify() }
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_otp_input")
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onChangePhone, enabled = !isLoading) {
                Text("Change Number")
            }
            TextButton(
                onClick = onResend,
                enabled = !isLoading && resendSeconds == 0
            ) {
                Text(if (resendSeconds > 0) "Resend (${resendSeconds}s)" else "Resend OTP")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onVerify,
            enabled = !isLoading && otp.trim().length >= 4,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("verify_otp_button")
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Verifying…")
            } else {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verify OTP", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ProfileSetupSection(
    phone: String,
    deviceId: String,
    userId: String,
    displayName: String,
    avatarPreview: String?,
    isAvatarUploading: Boolean,
    isSkipped: Boolean,
    isLoading: Boolean,
    infoMessage: String?,
    errorMessage: String?,
    onUserIdChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onPickPhotoClick: () -> Unit,
    onSkipPhotoClick: () -> Unit,
    onComplete: () -> Unit
) {
    var localNameError by remember { mutableStateOf<String?>(null) }

    val validateAndComplete = {
        when {
            displayName.trim().isEmpty() -> {
                localNameError = "Please enter your display name to continue."
            }
            userId.trim().isEmpty() -> {
                localNameError = "User ID cannot be empty."
            }
            else -> {
                localNameError = null
                onComplete()
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Verified: $phone",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        if (!infoMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = infoMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(14.dp))

        // Optional Profile Photo with Skip / Later option
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(enabled = !isLoading && !isAvatarUploading, onClick = onPickPhotoClick)
                .testTag("profile_photo_picker"),
            contentAlignment = Alignment.Center
        ) {
            if (avatarPreview != null) {
                AsyncImage(
                    model = avatarPreview,
                    contentDescription = "Selected Profile Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.AddAPhoto,
                    contentDescription = "Add Profile Photo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            if (isAvatarUploading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPickPhotoClick,
                enabled = !isLoading && !isAvatarUploading,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (avatarPreview != null) "Change Photo" else "Add Photo")
            }
            TextButton(
                onClick = onSkipPhotoClick,
                enabled = !isLoading,
                modifier = Modifier.testTag("skip_avatar_button")
            ) {
                Text(if (isSkipped) "Photo Skipped" else "Skip / Later")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = {
                localNameError = null
                onDisplayNameChange(it)
            },
            label = { Text("Display Name (Required)") },
            placeholder = { Text("e.g. Avinash Kumar") },
            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = "Display Name") },
            isError = localNameError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_name_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = userId,
            onValueChange = {
                localNameError = null
                onUserIdChange(it)
            },
            label = { Text("User ID") },
            placeholder = { Text("e.g. avinash_01") },
            leadingIcon = { Icon(Icons.Rounded.Badge, contentDescription = "User ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!isLoading && !isAvatarUploading) {
                        validateAndComplete()
                    }
                }
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_userid_input")
        )

        if (deviceId.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Hardware Lock ID: ${deviceId.take(18)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val combinedError = localNameError ?: errorMessage
        if (combinedError != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = combinedError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = validateAndComplete,
            enabled = !isLoading && !isAvatarUploading,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("login_submit_button")
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Activating Device Lock…")
            } else {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Activate & Lock Device", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
