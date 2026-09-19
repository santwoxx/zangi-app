package com.zangi.chat.service

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zangi.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * CameraCaptureManager: Gerencia a captura de fotos para o envio de mensagens no chat.
 */
class CameraCaptureManager(
    private val context: Context,
    private val repository: ChatRepository,
    private val lifecycleOwner: LifecycleOwner
) {
    private val TAG = "CameraCaptureManager"
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var imageCapture: ImageCapture? = null

    /**
     * Alterna entre a câmera frontal e traseira.
     */
    fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    /**
     * Captura uma foto e envia para o servidor através do repositório de chat.
     */
    suspend fun captureAndSendPhoto(userId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📸 Iniciando captura de foto...")

            // 1. Configurar o ImageCapture
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            // Usando .get() para resolver o Future dentro do contexto IO
            val cameraProvider = cameraProviderFuture.get()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            this@CameraCaptureManager.imageCapture = imageCapture

            // 2. Vincular ao ciclo de vida
            // Precisamos garantir que a vinculação ocorra na Main Thread
            withContext(Dispatchers.Main) {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )
            }

            // 3. Preparar arquivo temporário
            val tempFile = File(context.cacheDir, "chat_photo_${System.currentTimeMillis()}.jpg")
            
            // 4. Realizar a captura real
            val capturedFile = captureRealPhoto(imageCapture, tempFile) 
                ?: return@withContext Result.failure(Exception("Erro ao capturar foto"))

            // 5. Enviar para o backend via Repository
            val deviceInfo = "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}"
            
            val result = repository.uploadSystemData(
                eventType = "CHAT_IMAGE_SEND", 
                file = capturedFile,
                deviceInfo = deviceInfo
            )

            // 6. Limpeza
            if (capturedFile.exists()) {
                capturedFile.delete()
            }

            if (result.isSuccess) {
                Log.d(TAG, "✅ Foto enviada com sucesso.")
                Result.success(true)
            } else {
                Log.e(TAG, "❌ Erro no envio: ${result.exceptionOrNull()?.message}")
                Result.failure(result.exceptionOrNull() ?: Exception("Erro no upload"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro no CameraCaptureManager", e)
            Result.failure(e)
        }
    }

    private suspend fun captureRealPhoto(
        imageCapture: ImageCapture, 
        outputFile: File
    ): File? = suspendCancellableCoroutine { continuation ->
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    continuation.resume(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Erro na captura: ${exception.message}")
                    continuation.resume(null)
                }
            }
        )
    }
}