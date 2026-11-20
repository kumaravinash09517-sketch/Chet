package com.example.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
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

    @POST("api/auth/login")
    suspend fun loginDirect(@Body request: DirectLoginRequest): Response<DirectLoginResponse>

    @POST("api/auth/send-otp")
    suspend fun sendOtp(@Body request: SendOtpRequest): Response<SendOtpResponse>

    @POST("api/auth/verify-otp")
    suspend fun verifyOtp(@Body request: VerifyOtpRequest): Response<VerifyOtpResponse>

    @POST("api/users/register")
    suspend fun registerUser(@Body request: RegisterUserRequest): Response<RegisterUserResponse>

    @GET("api/users")
    suspend fun getUserDirectory(): Response<ResponseBody>

    @GET("api/users/find")
    suspend fun findUsers(
        @Query("q") q: String
    ): Response<ResponseBody>

    @GET("api/users/find")
    suspend fun findUsersByQuery(
        @Query("query") query: String
    ): Response<ResponseBody>

    @GET("api/users/find")
    suspend fun findUsersByPhone(
        @Query("phone") phone: String
    ): Response<ResponseBody>

    @GET("api/users/find")
    suspend fun findUsersByUserId(
        @Query("userId") userId: String
    ): Response<ResponseBody>

    @GET("api/users/search")
    suspend fun searchUsers(@Query("q") query: String): Response<ResponseBody>

    @POST("api/webhooks/admin-event")
    suspend fun sendAdminWebhook(@Body request: AdminWebhookEventRequest): Response<BasicSuccessResponse>

    @GET("api/messages/{peerId}")
    suspend fun getMessages(
        @Path("peerId") peerId: String,
        @Query("userId") currentUserId: String
    ): Response<ResponseBody>

    @POST("api/messages")
    suspend fun sendMessage(@Body request: SendMessageRequest): Response<ResponseBody>

    @POST("api/messages")
    suspend fun sendMessageSnakeCase(@Body request: SendMessageSnakeCaseRequest): Response<ResponseBody>

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
