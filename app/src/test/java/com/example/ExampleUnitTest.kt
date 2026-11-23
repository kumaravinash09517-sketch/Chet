package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun notificationHelperFormatsSenderTitleWithNameAndUserId() {
    assertEquals(
      "Sandeep (u_919250903007)",
      com.example.platform.NotificationHelper.formatSenderTitle("Sandeep", "u_919250903007")
    )
    assertEquals(
      "u_919250903007",
      com.example.platform.NotificationHelper.formatSenderTitle("  ", "u_919250903007")
    )
    assertEquals(
      "avinash",
      com.example.platform.NotificationHelper.formatSenderTitle("avinash", "Avinash")
    )
  }

  @Test
  fun shayariCollectionAndRotationWorkCleanly() {
    val list = com.example.platform.ShayariManager.shayariCollection
    assertTrue(list.size >= 20)
    assertTrue(list.all { it.mood.isNotBlank() && it.lines.isNotBlank() })
    val next = com.example.platform.ShayariManager.getShayariAfterIndex(list.first().id)
    assertEquals(list[1], next)
    val wrap = com.example.platform.ShayariManager.getShayariAfterIndex(list.last().id)
    assertEquals(list.first(), wrap)
    val daily = com.example.platform.ShayariManager.shayariForCalendarDay(1_763_700_000_000L)
    assertTrue(daily.lines.isNotBlank())
  }

  @Test
  fun callSignalDeduplicatorTracksAnsweredAndDismissedCalls() {
    com.example.data.repository.CallSignalDeduplicator.clear()
    assertFalse(com.example.data.repository.CallSignalDeduplicator.isHandled("call_123"))
    com.example.data.repository.CallSignalDeduplicator.markAnswered("call_123")
    assertTrue(com.example.data.repository.CallSignalDeduplicator.isHandled("call_123"))
    com.example.data.repository.CallSignalDeduplicator.clear()
  }

  @Test
  fun authCredentialsHoldsDirectAndGoogleSignInPayloads() {
    val direct = com.example.core.security.AuthCredentials.DirectUserId(
      username = "Avinash",
      userId = "u_919455877902",
      deviceId = "dev_1"
    )
    val google = com.example.core.security.AuthCredentials.GoogleIdToken(
      idToken = "tok",
      email = "avinash@example.com",
      displayName = "Avinash"
    )
    assertEquals("u_919455877902", direct.userId)
    assertEquals("tok", google.idToken)
  }

  @Test
  fun mediaTransferTrackerComputesProgressPercentageAndEta() {
    assertEquals("~12s left", com.example.data.repository.MediaTransferTracker.formatEtaRemaining(12L))
    assertEquals("~1 min left", com.example.data.repository.MediaTransferTracker.formatEtaRemaining(62L))
    assertEquals("~1m 15s left", com.example.data.repository.MediaTransferTracker.formatEtaRemaining(75L))

    val uploadProgress = com.example.data.repository.MediaTransferTracker.computeProgress(
      messageId = "msg_vid_1",
      isUpload = true,
      bytesTransferred = 5_000_000L,
      totalBytes = 10_000_000L,
      elapsedMs = 5_000L
    )
    assertEquals(0.5f, uploadProgress.fraction, 0.01f)
    assertTrue(uploadProgress.statusLabel.startsWith("Uploading 50%"))
    assertTrue(uploadProgress.statusLabel.contains("left"))

    val downloadProgress = com.example.data.repository.MediaTransferTracker.computeProgress(
      messageId = "msg_vid_2",
      isUpload = false,
      bytesTransferred = 2_500_000L,
      totalBytes = 10_000_000L,
      elapsedMs = 1_000L
    )
    assertEquals(0.25f, downloadProgress.fraction, 0.01f)
    assertTrue(downloadProgress.statusLabel.startsWith("Downloading 25%"))
    assertEquals(3L, downloadProgress.etaSeconds)
  }

  @Test
  fun notificationHelperGeneratesDistinctMessageNotificationIdsPerSender() {
    val id1 = com.example.platform.NotificationHelper.messageNotificationIdFor("u_avinash")
    val id2 = com.example.platform.NotificationHelper.messageNotificationIdFor("u_sandeep")
    assertTrue(id1 >= 50_000)
    assertTrue(id2 >= 50_000)
    assertNotEquals(id1, id2)
  }
}
