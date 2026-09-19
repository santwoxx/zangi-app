package com.zangi.chat.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zangi.chat.data.model.MessageType
import com.zangi.chat.data.remote.RetrofitClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val text = inputData.getString(KEY_TEXT) ?: ""
        val typeStr = inputData.getString(KEY_TYPE) ?: MessageType.TEXT.name

        val file = File(filePath)
        if (!file.exists()) {
            Log.e("UploadWorker", "Arquivo não encontrado para upload em segundo plano: $filePath")
            return Result.failure()
        }
        val conversationId = inputData.getString(KEY_CONVERSATION_ID) ?: "default_chat"
        val senderId = inputData.getString(KEY_SENDER_ID) ?: "anonimo"
        val senderName = inputData.getString(KEY_SENDER_NAME) ?: "Alexander M."
        val senderZangiNumber = inputData.getString(KEY_SENDER_ZANGI_NUMBER) ?: "10-000-0000"

        return try {
            val mimeType = if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"
            val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
            val textBody = text.toRequestBody("text/plain".toMediaTypeOrNull())
            val typeBody = typeStr.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = RetrofitClient.getApiService().uploadFile(
                file = filePart,
                text = textBody,
                senderId = senderId.toRequestBody("text/plain".toMediaTypeOrNull()),
                senderName = senderName.toRequestBody("text/plain".toMediaTypeOrNull()),
                senderZangiNumber = senderZangiNumber.toRequestBody("text/plain".toMediaTypeOrNull()),
                conversationId = conversationId.toRequestBody("text/plain".toMediaTypeOrNull()),
                type = typeBody
            )

            if (response.isSuccessful && response.body()?.success == true) {
                Log.d("UploadWorker", "Upload em segundo plano concluído com sucesso: ${file.name}")
                Result.success()
            } else {
                Log.e("UploadWorker", "Falha na resposta do servidor: ${response.code()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("UploadWorker", "Erro durante execução do worker em segundo plano", e)
            Result.retry()
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
    }
}
