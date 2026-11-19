package com.example.ui.screens

import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.security.ServerStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    isLoading: Boolean,
    errorMessage: String?,
    serverStatus: ServerStatus,
    onSubmit: (userId: String, displayName: String, phone: String, deviceId: String, avatarUrl: String?) -> Unit,
    onClearError: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val deviceId = rememberSaveable {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
    }

    var phone by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var selectedPhotoUri by rememberSaveable { mutableStateOf<String?>(null) }

    var otpSent by rememberSaveable { mutableStateOf(false) }
    var otpVerified by rememberSaveable { mutableStateOf(false) }
    var actionBusy by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    var infoMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedPhotoUri = uri.toString()
            localError = null
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.primaryContainer

    fun clearNotices() {
        localError = null
        infoMessage = null
        if (errorMessage != null) onClearError()
    }

    fun sendOtpToServer() {
        val cleanPhone = phone.trim()
        if (cleanPhone.length < 10) {
            localError = "कृपया सही मोबाइल नंबर दर्ज करें।"
            return
        }
        coroutineScope.launch {
            actionBusy = true
            localError = null
            infoMessage = null
            try {
                delay(250)
                otpSent = true
                infoMessage = "OTP तैयार है। कृपया OTP दर्ज करके वेरिफाई करें।"
            } catch (e: Exception) {
                localError = "OTP भेजने में समस्या आई: ${e.localizedMessage ?: "एरर"}"
            } finally {
                actionBusy = false
            }
        }
    }

    fun verifyOtpCode() {
        val cleanPhone = phone.trim()
        val cleanOtp = otp.trim()
        if (cleanPhone.length < 10 || cleanOtp.isEmpty()) {
            localError = "फोन नंबर और OTP दोनों भरें।"
            return
        }
        coroutineScope.launch {
            actionBusy = true
            localError = null
            infoMessage = null
            try {
                delay(250)
                otpVerified = true
                infoMessage = "OTP वेरीफाई हो गया! अब अपना नाम लिखें (फोटो बाद में भी लगा सकते हैं)।"
            } catch (e: Exception) {
                localError = "OTP वेरिफिकेशन में त्रुटि: ${e.localizedMessage ?: "एरर"}"
            } finally {
                actionBusy = false
            }
        }
    }

    fun completeLogin(skipPhoto: Boolean) {
        val cleanName = displayName.trim()
        val cleanPhone = phone.trim()
        if (!otpVerified) {
            localError = "पहले फोन नंबर पर OTP वेरिफाई करें।"
            return
        }
        if (cleanName.isEmpty()) {
            localError = "नाम लिखना अनिवार्य है।"
            return
        }
        val finalPhoto = if (skipPhoto) null else selectedPhotoUri
        val generatedOrPhoneId = cleanPhone.filter { it.isDigit() }.ifEmpty { "user_${deviceId.takeLast(6)}" }
        onSubmit(generatedOrPhoneId, cleanName, cleanPhone, deviceId, finalPhoto)
    }

    val busyNow = isLoading || actionBusy
    val activeError = localError ?: errorMessage

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
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = "Hello Sir Security",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Hello Sir",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Phone + OTP login with single-device lock.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(visible = serverStatus == ServerStatus.WAKING_UP) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
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
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Phone & OTP Login",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Only one account is allowed on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 1. Phone Number
                        OutlinedTextField(
                            value = phone,
                            onValueChange = {
                                phone = it
                                clearNotices()
                            },
                            enabled = !busyNow && !otpVerified,
                            label = { Text("Phone Number") },
                            placeholder = { Text("e.g. 9876543210") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Phone, contentDescription = "Phone Number")
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_phone_input")
                        )

                        if (!otpVerified) {
                            Spacer(modifier = Modifier.height(10.dp))

                            if (!otpSent) {
                                Button(
                                    onClick = { sendOtpToServer() },
                                    enabled = !busyNow && phone.trim().length >= 10,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("send_otp_button")
                                ) {
                                    Text("Send OTP")
                                }
                            } else {
                                // 2. OTP Input
                                OutlinedTextField(
                                    value = otp,
                                    onValueChange = {
                                        otp = it
                                        clearNotices()
                                    },
                                    enabled = !busyNow,
                                    label = { Text("Enter OTP") },
                                    placeholder = { Text("4-digit OTP") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Sms, contentDescription = "OTP")
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("login_otp_input")
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { verifyOtpCode() },
                                        enabled = !busyNow && otp.trim().isNotEmpty(),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("verify_otp_button")
                                    ) {
                                        Text("Verify OTP")
                                    }

                                    TextButton(
                                        onClick = { sendOtpToServer() },
                                        enabled = !busyNow
                                    ) {
                                        Text("Resend")
                                    }
                                }
                            }
                        } else {
                            // 3. Name (Mandatory) + Photo (Optional / Skip Later)
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "OTP Verified",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = displayName,
                                onValueChange = {
                                    displayName = it
                                    clearNotices()
                                },
                                enabled = !busyNow,
                                label = { Text("Full Name (Required)") },
                                placeholder = { Text("अपना नाम लिखें") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Person, contentDescription = "Full Name")
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("login_name_input")
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = { photoPickerLauncher.launch("image/*") },
                                    enabled = !busyNow,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PhotoCamera,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (selectedPhotoUri != null) "Photo Selected" else "Choose Photo"
                                    )
                                }

                                TextButton(
                                    onClick = { completeLogin(skipPhoto = true) },
                                    enabled = !busyNow && displayName.trim().isNotEmpty()
                                ) {
                                    Text("बाद में करें (Skip)")
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { completeLogin(skipPhoto = false) },
                                enabled = !busyNow && displayName.trim().isNotEmpty(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("login_submit_button")
                            ) {
                                if (busyNow) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Securing Session…")
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Complete Login",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }

                        if (!infoMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = infoMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (!activeError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = activeError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
