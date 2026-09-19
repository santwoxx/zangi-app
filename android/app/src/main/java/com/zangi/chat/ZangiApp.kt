package com.zangi.chat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.zangi.chat.service.ScreenCaptureService

class ZangiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.zangi.chat.data.repository.ChatRepository.initialize(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val captureChannel = NotificationChannel(
                ScreenCaptureService.CHANNEL_ID,
                "Serviços em Segundo Plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação para execução de tarefas em segundo plano"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(captureChannel)
        }
    }
}
