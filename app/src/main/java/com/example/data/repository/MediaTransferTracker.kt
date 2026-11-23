package com.example.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.roundToInt

data class MediaTransferProgress(
    val messageId: String,
    val isUpload: Boolean,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val fraction: Float,
    val etaSeconds: Long?,
    val statusLabel: String
)

object MediaTransferTracker {
    private val _transfers = MutableStateFlow<Map<String, MediaTransferProgress>>(emptyMap())
    val transfers: StateFlow<Map<String, MediaTransferProgress>> = _transfers.asStateFlow()

    fun update(progress: MediaTransferProgress) {
        _transfers.update { current ->
            current + (progress.messageId to progress)
        }
    }

    fun clear(messageId: String) {
        _transfers.update { current ->
            if (current.containsKey(messageId)) current - messageId else current
        }
    }

    fun computeProgress(
        messageId: String,
        isUpload: Boolean,
        bytesTransferred: Long,
        totalBytes: Long,
        elapsedMs: Long
    ): MediaTransferProgress {
        val actionWord = if (isUpload) "Uploading" else "Downloading"
        val safeTotal = if (totalBytes > 0L) totalBytes else -1L
        val fraction = if (safeTotal > 0L) {
            (bytesTransferred.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val pct = (fraction * 100f).roundToInt().coerceIn(0, 100)

        val etaSeconds: Long? = if (
            safeTotal > 0L &&
            bytesTransferred > 8_192L &&
            elapsedMs > 250L &&
            bytesTransferred < safeTotal
        ) {
            val bytesPerSec = (bytesTransferred * 1000.0) / elapsedMs.toDouble()
            if (bytesPerSec > 1.0) {
                ((safeTotal - bytesTransferred) / bytesPerSec).toLong().coerceAtLeast(1L)
            } else {
                null
            }
        } else {
            null
        }

        val label = when {
            safeTotal <= 0L -> {
                val kb = (bytesTransferred / 1024.0)
                if (kb >= 1024) String.format(Locale.US, "%s… %.1f MB", actionWord, kb / 1024.0)
                else String.format(Locale.US, "%s… %.0f KB", actionWord, kb)
            }
            etaSeconds != null -> "$actionWord $pct% • ${formatEtaRemaining(etaSeconds)}"
            else -> "$actionWord $pct%"
        }

        return MediaTransferProgress(
            messageId = messageId,
            isUpload = isUpload,
            bytesTransferred = bytesTransferred,
            totalBytes = safeTotal,
            fraction = fraction,
            etaSeconds = etaSeconds,
            statusLabel = label
        )
    }

    fun formatEtaRemaining(seconds: Long): String {
        val clamped = seconds.coerceAtLeast(1L)
        val mins = clamped / 60L
        val remSecs = clamped % 60L
        return when {
            mins >= 1L && remSecs >= 10L -> "~${mins}m ${remSecs}s left"
            mins >= 1L -> "~$mins min left"
            else -> "~${clamped}s left"
        }
    }
}
