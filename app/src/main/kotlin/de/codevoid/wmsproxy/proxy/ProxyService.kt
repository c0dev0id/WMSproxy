package de.codevoid.wmsproxy.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import de.codevoid.wmsproxy.MainActivity
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.core.RequestLog
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the HTTP server alive while another app is using it.
 *
 * A foreground service of type `specialUse`, not `dataSync`: Android 15 caps dataSync
 * services at six cumulative hours, which would stop the proxy mid-journey.
 */
class ProxyService : Service() {

    // Single thread, so repeated starts queue rather than spawn, and onDestroy can stop it.
    private val certRefresh = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Only a deliberate stop clears the flag, and it is cleared here rather than
            // in the companion's stop() so the notification's own Stop action counts too.
            // Being killed by the system does not mean the user changed their mind.
            Sources.setStartOnBoot(false)
            stopSelf()
            return START_NOT_STICKY
        }

        Sources.setStartOnBoot(true)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        runCatching { server.start(Tls.serverSocketFactory(this)) }
            .onSuccess {
                _running.value = true
                // The certificate is fetched, not bundled, so the first start may have
                // come up without HTTPS, and a long-lived service must renew before the
                // cached certificate expires. Both run off the service thread on an
                // executor that onDestroy shuts down, so the work cannot outlive the
                // service or pile up across repeated starts. A failure leaves the plain
                // listener serving.
                certRefresh.execute {
                    Tls.refreshIfNeeded(this@ProxyService)
                    if (_running.value && !server.secureAvailable) {
                        runCatching { server.start(Tls.serverSocketFactory(this@ProxyService)) }
                    }
                }
            }
            .onFailure {
                // Most likely the port is taken. Surface it rather than sitting in the
                // notification tray pretending to serve.
                log.note("server", "failed to start on port $PORT: ${it.message}")
                _running.value = false
                stopSelf()
            }

        // Restarting without the original intent is correct here: the service carries no
        // per-start state, it just needs to be up.
        return START_STICKY
    }

    override fun onDestroy() {
        certRefresh.shutdownNow()
        server.stop()
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_proxy),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running, PORT))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.stop), stop).build(),
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8088
        const val SECURE_PORT = 8443
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "de.codevoid.wmsproxy.STOP"

        /**
         * Server and log live with the process rather than the service instance, so the
         * log survives a stop/start and the UI can read it either way.
         */
        val log = RequestLog()
        val server = ProxyServer(PORT, SECURE_PORT, log)

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) =
            context.startForegroundService(Intent(context, ProxyService::class.java))

        fun stop(context: Context) =
            context.startService(
                Intent(context, ProxyService::class.java).setAction(ACTION_STOP),
            )
    }
}
