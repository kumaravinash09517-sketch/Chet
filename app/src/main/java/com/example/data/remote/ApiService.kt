package com.example.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("api/health")
    suspend fun pingHealth(): Response<BasicSuccessResponse>

    @POST("api/users/register")
    suspend fun registerUser(@Body request: RegisterUserRequest): Response<RegisterUserResponse>

    @GET("api/users")
    suspend fun getUserDirectory(): Response<UserDirectoryResponse>

    @POST("api/webhooks/admin-event")
    suspend fun sendAdminWebhook(@Body request: AdminWebhookEventRequest): Response<BasicSuccessResponse>

    @GET("api/messages/{peerId}")
    suspend fun getMessages(
        @Path("peerId") peerId: String,
        @Query("userId") currentUserId: String
    ): Response<MessagesListResponse>

    @POST("api/messages")
    suspend fun sendMessage(@Body request: SendMessageRequest): Response<RemoteMessageDto>

    @DELETE("api/messages/{messageId}")
    suspend fun deleteMessage(
        @Path("messageId") messageId: String,
        @Query("userId") currentUserId: String
    ): Response<BasicSuccessResponse>

    @POST("api/messages/{peerId}/read")
    suspend fun markConversationRead(
        @Path("peerId") peerId: String,
        @Query("userId") currentUserId: String
    ): Response<BasicSuccessResponse>

    @Multipart
    @POST("api/media/upload")
    suspend fun uploadMedia(
        @Part file: MultipartBody.Part,
        @Part("type") type: RequestBody,
        @Part("senderId") senderId: RequestBody
    ): Response<MediaUploadResponse>

    @GET("api/webrtc/ice-servers")
    suspend fun getIceServers(): Response<IceServersResponse>
}
