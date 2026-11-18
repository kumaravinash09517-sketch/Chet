package com.example.data.remote

import com.example.core.security.GlobalSecurityEventBus
import com.example.core.security.ServerStatus
import com.example.core.security.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 1. Injects encrypted single-device lock and session headers.
 * 2. Intercepts HTTP 403 responses globally and triggers the administrator block alert.
 */
class SecurityAuthInterceptor(
    private val sessionManager: SessionManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val session = runBlocking { sessionManager.getCurrentSession() }
        val deviceLock = runBlocking { sessionManager.getOrCreateDeviceLockId() }

        val requestBuilder = chain.request().newBuilder()
            .header("X-Device-Lock", deviceLock)
            .header("Accept", "application/json")

        if (session != null) {
            requestBuilder
                .header("Authorization", "Bearer ${session.sessionToken}")
                .header("X-User-Id", session.userId)
        }

        val response = chain.proceed(requestBuilder.build())

        if (response.code == 403) {
            GlobalSecurityEventBus.emitBlocked("You are blocked by the administrator.")
        }

        return response
    }
}

/**
 * Handles Render cold-start wakeups (502/503/504 or initial timeout) with bounded
 * exponential backoff and updates ServerStatus for subtle UI feedback.
 */
class RenderColdStartInterceptor : Interceptor {
    companion object {
        private const val MAX_RETRIES = 2
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var lastException: IOException? = null

        while (attempt <= MAX_RETRIES) {
            try {
                val startMs = System.currentTimeMillis()
                val response = chain.proceed(request)
                val elapsedMs = System.currentTimeMillis() - startMs

                if (response.code in listOf(502, 503, 504) && attempt < MAX_RETRIES) {
                    response.close()
                    GlobalSecurityEventBus.updateServerStatus(ServerStatus.WAKING_UP)
                    attempt++
                    Thread.sleep(1500L * attempt)
                    continue
                }

                if (elapsedMs > 2500L) {
                    GlobalSecurityEventBus.updateServerStatus(ServerStatus.READY)
                } else {
                    GlobalSecurityEventBus.updateServerStatus(ServerStatus.READY)
                }
                return response
            } catch (ioe: IOException) {
                lastException = ioe
                if (attempt < MAX_RETRIES) {
                    GlobalSecurityEventBus.updateServerStatus(ServerStatus.WAKING_UP)
                    attempt++
                    try {
                        Thread.sleep(1500L * attempt)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    GlobalSecurityEventBus.updateServerStatus(ServerStatus.OFFLINE)
                    throw ioe
                }
            }
        }
        throw lastException ?: IOException("Unable to reach Render server.")
    }
}
