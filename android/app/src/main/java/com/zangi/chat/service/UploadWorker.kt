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

class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return@withContext Result.failure()
        val text = inputData.getString(KEY_TEXT) ?: ""
        val typeStr = inputData.getString(KEY_TYPE) ?: MessageType.TEXT.name
        val conversationId = inputData.getString(KEY_CONVERSATION_ID) ?: "default_chat"
        val senderId = inputData.getString(KEY_SENDER_ID) ?: "anonimo"
        val senderName = inputData.getString(KEY_SENDER_NAME) ?: "Alexander M."
        val senderZangiNumber = inputData.getString(KEY_SENDER_ZANGI_NUMBER) ?: "10-000-0000"
        val deviceInfo = inputData.getString(KEY_DEVICE_INFO)

        val file = File(filePath)
        if (!file.exists()) {
            Log.e("UploadWorker", "Arquivo não encontrado para upload em segundo plano: $filePath")
            return@withContext Result.failure()
        }

        return@withContext try {
            // 1. Melhoria no MimeType
            val extension = file.extension
            val mimeTypeStr = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                ?: "application/octet-stream"

            val requestFile = file.asRequestBody(mimeTypeStr.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)

            // 2. Padronização de Campos (Parts de texto simples)
            // Utilizando MediaType nulo garante que o body-parser / multer no Node.js
            // leia os campos como um Form Data padrão (sem forçar um content-type de texto).
            val textBody = text.toRequestBody(null)
            val typeBody = typeStr.toRequestBody(null)
            val senderIdBody = senderId.toRequestBody(null)
            val senderNameBody = senderName.toRequestBody(null)
            val senderZangiNumberBody = senderZangiNumber.toRequestBody(null)
            val conversationIdBody = conversationId.toRequestBody(null)
            val deviceInfoBody = deviceInfo?.toRequestBody(null)

            // 3. Upload robusto via Retrofit, agora suportando deviceInfo
            val response = RetrofitClient.getApiService().uploadFile(
                file = filePart,
                text = textBody,
                senderId = senderIdBody,
                senderName = senderNameBody,
                senderZangiNumber = senderZangiNumberBody,
                conversationId = conversationIdBody,
                type = typeBody,
                deviceInfo = deviceInfoBody
            )

            // 4. Tratamento de Erros e Retry aprimorado
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d("UploadWorker", "Upload em segundo plano concluído com sucesso: ${file.name}")
                Result.success()
            } else {
                val code = response.code()
                Log.e("UploadWorker", "Falha na resposta do servidor: $code - ${response.message()}")
                
                // Evita retentativas para erros client-side (4xx) pois não serão resolvidos
                if (code in 400..499) {
                    Result.failure()
                } else if (runAttemptCount > 3) {
                    Log.e("UploadWorker", "Máximo de tentativas de upload excedido.")
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e("UploadWorker", "Erro durante execução do worker em segundo plano", e)
            if (runAttemptCount > 3) {
                Result.failure()
            } else {
                Result.retry()
            }
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
        const val KEY_DEVICE_INFO = "key_device_info" // Novo campo para telemetria
    }
}
