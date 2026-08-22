package com.tapcreator.app.backend.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tapcreator.app.MainActivity
import com.tapcreator.app.R

/**
 * 生成任务前台服务：在长时间视频分段生成期间保持进程存活，
 * 通过常驻通知告知用户进度，避免后台被系统回收导致续写中断。
 */
class GenerationForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "generation_service"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, title: String) {
            val intent = Intent(context, GenerationForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "生成任务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "保持长时间生成任务在前台运行" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "生成中"
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText("视频生成进行中，请保持应用运行")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }
}