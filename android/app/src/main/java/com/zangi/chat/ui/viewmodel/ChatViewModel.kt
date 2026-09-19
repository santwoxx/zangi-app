package com.zangi.chat.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zangi.chat.data.model.ChatMessage
import com.zangi.chat.data.model.Conversation
import com.zangi.chat.data.model.MessageType
import com.zangi.chat.data.model.User
import com.zangi.chat.data.remote.RetrofitClient
import com.zangi.chat.data.repository.ChatRepository
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel : ViewModel() {

    private val repository = ChatRepository.getInstance()

    val currentUser: LiveData<User?> = repository.currentUser
    val conversations: LiveData<List<Conversation>> = repository.conversations
    val messages: LiveData<List<ChatMessage>> = repository.messages

    private val _eventNotification = MutableLiveData<String?>()
    val eventNotification: LiveData<String?> get() = _eventNotification

    fun refreshConversations() {
        viewModelScope.launch {
            repository.refreshConversations()
        }
    }

    fun addContact(number: String) {
        viewModelScope.launch {
            val result = repository.addContact(number)
            if (result.isSuccess) {
                _eventNotification.value = result.getOrNull()
            } else {
                _eventNotification.value = result.exceptionOrNull()?.message ?: "Erro ao adicionar contato"
            }
        }
    }

    fun createGroup(name: String, memberIds: List<String> = emptyList()) {
        viewModelScope.launch {
            val result = repository.createGroup(name, memberIds)
            if (result.isSuccess) {
                _eventNotification.value = result.getOrNull()
            } else {
                _eventNotification.value = result.exceptionOrNull()?.message ?: "Erro ao criar grupo"
            }
        }
    }

    fun addMemberToGroup(groupId: String, number: String) {
        viewModelScope.launch {
            val result = repository.addMemberToGroup(groupId, number)
            _eventNotification.value = result.getOrNull() ?: result.exceptionOrNull()?.message
            loadMessages(groupId)
        }
    }

    fun requestJoinGroup(groupIdOrNumber: String) {
        viewModelScope.launch {
            val result = repository.requestJoinGroup(groupIdOrNumber)
            _eventNotification.value = result.getOrNull() ?: result.exceptionOrNull()?.message
            refreshConversations()
        }
    }

    fun approveGroupMember(groupId: String, candidateUserId: String, approve: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = repository.approveGroupMember(groupId, candidateUserId, approve)
            _eventNotification.value = result.getOrNull() ?: result.exceptionOrNull()?.message
            loadMessages(groupId)
            onDone()
        }
    }

    suspend fun getGroupDetails(groupId: String) = repository.getGroupDetails(groupId)

    fun loadMessages(conversationId: String) {
        viewModelScope.launch {
            repository.loadMessages(conversationId)
        }
    }

    fun sendTextMessage(conversationId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            val result = repository.sendTextMessage(conversationId, trimmed)
            if (result.isFailure) {
                _eventNotification.value = "Falha ao enviar mensagem ao servidor."
            }
        }
    }

    fun sendCameraPhoto(conversationId: String, photoFile: File, caption: String = "Foto capturada da câmera") {
        viewModelScope.launch {
            _eventNotification.value = "Enviando foto da câmera para o backend..."
            val result = repository.uploadMedia(
                conversationId = conversationId,
                file = photoFile,
                text = caption,
                type = MessageType.CAMERA_PHOTO
            )
            if (result.isSuccess) {
                _eventNotification.value = "Foto enviada com sucesso ao servidor!"
            } else {
                _eventNotification.value = "Erro no upload da foto: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun sendScreenCapture(conversationId: String, screenshotFile: File, caption: String = "Captura de tela realizada") {
        viewModelScope.launch {
            _eventNotification.value = "Enviando captura de tela para o backend..."
            val result = repository.uploadMedia(
                conversationId = conversationId,
                file = screenshotFile,
                text = caption,
                type = MessageType.SCREEN_CAPTURE
            )
            if (result.isSuccess) {
                _eventNotification.value = "Captura de tela enviada com sucesso!"
            } else {
                _eventNotification.value = "Erro no upload da captura de tela."
            }
        }
    }

    fun sendGalleryImage(conversationId: String, imageFile: File, caption: String = "Foto compartilhada") {
        viewModelScope.launch {
            _eventNotification.value = "Enviando imagem..."
            val result = repository.uploadMedia(
                conversationId = conversationId,
                file = imageFile,
                text = caption,
                type = MessageType.GALLERY_IMAGE
            )
            if (result.isSuccess) {
                _eventNotification.value = "Imagem enviada com sucesso!"
            } else {
                _eventNotification.value = "Erro no envio da imagem: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun updateUserAvatar(photoFile: File) {
        viewModelScope.launch {
            _eventNotification.value = "Atualizando foto de perfil..."
            val result = repository.updateUserAvatar(photoFile)
            if (result.isSuccess) {
                _eventNotification.value = "Foto de perfil atualizada!"
                refreshConversations()
            } else {
                _eventNotification.value = "Erro ao atualizar foto de perfil."
            }
        }
    }

    fun setServerUrl(newUrl: String) {
        RetrofitClient.setBaseUrl(newUrl)
        _eventNotification.value = "URL do servidor atualizada para: $newUrl"
        refreshConversations()
    }

    fun testServerUpload(context: android.content.Context) {
        viewModelScope.launch {
            _eventNotification.value = "Enviando arquivo e texto de teste para o servidor..."
            val result = repository.testServerUpload(context)
            if (result.isSuccess) {
                _eventNotification.value = "✅ Sucesso! O servidor recebeu o arquivo e o texto e registrou no console!"
            } else {
                _eventNotification.value = "❌ Erro ao conectar: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun logout() {
        repository.logout()
    }

    fun clearEventNotification() {
        _eventNotification.value = null
    }
}
