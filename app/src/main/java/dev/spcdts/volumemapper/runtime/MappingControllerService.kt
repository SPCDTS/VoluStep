package dev.spcdts.volumemapper.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.spcdts.volumemapper.MainActivity
import dev.spcdts.volumemapper.R
import dev.spcdts.volumemapper.VolumeMapperApplication

class MappingControllerService : Service() {
    private val coordinator: MappingCoordinator
        get() = (application as VolumeMapperApplication).graph.mappingCoordinator

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            coordinator.disarm()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val foregroundType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        val foregroundStarted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                foregroundType,
            )
        }.isSuccess
        if (!foregroundStarted) {
            coordinator.onForegroundServiceStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        coordinator.onForegroundServiceStarted()
        coordinator.arm()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        coordinator.onForegroundServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.controller_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.controller_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_volume)
        .setContentTitle(getString(R.string.controller_notification_title))
        .setContentText(getString(R.string.controller_notification_text))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            R.drawable.ic_notification_volume,
            getString(R.string.controller_stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, MappingControllerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "volume_mapping_controller"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_STOP = "dev.spcdts.volumemapper.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MappingControllerService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MappingControllerService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
