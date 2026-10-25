package it.telecast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import it.telecast.core.CastStatus
import it.telecast.core.Log
import it.telecast.core.PlayState

/**
 * Servizio in primo piano: tiene vivo il proxy (e il Wi-Fi) quando lo schermo è spento,
 * e mostra la notifica con Pausa/Stop.
 */
class CastService : Service() {
    private val listener: (CastStatus) -> Unit = { onStatus(it) }
    private var lastKey = ""
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val stopIfIdle = Runnable { if (!App.instance.cast.status.active) stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground(App.instance.cast.status)
        acquireLocks()
        App.instance.addStatusListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(App.instance.cast.status)
        when (intent?.action) {
            ACTION_TOGGLE -> App.instance.cast.togglePause()
            ACTION_STOP -> {
                App.instance.cast.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        App.instance.removeStatusListener(listener)
        App.instance.main.removeCallbacks(stopIfIdle)
        releaseLocks()
        super.onDestroy()
    }

    private fun onStatus(st: CastStatus) {
        val key = "${st.state}|${st.title}|${st.tvName}"
        if (key != lastKey) {
            lastKey = key
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(st))
        }
        App.instance.main.removeCallbacks(stopIfIdle)
        if (!st.active) App.instance.main.postDelayed(stopIfIdle, 90_000)
    }

    private fun goForeground(st: CastStatus) {
        val n = buildNotification(st)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Trasmissione alla TV", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Controlli del video in onda sulla TV"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(st: CastStatus): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, RemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags,
        )
        val toggle = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction(ACTION_TOGGLE), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags)
        val playing = st.state == PlayState.PLAYING || st.state == PlayState.BUFFERING || st.state == PlayState.CONNECTING
        val text = listOf(shortState(st), st.tvName).filter { it.isNotBlank() }.joinToString(" · ")
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(st.title.ifBlank { "TeleCast" })
            .setContentText(text.ifBlank { "Pronto a trasmettere" })
            .setContentIntent(open)
            .setOngoing(st.active)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(C.ACCENT)
        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                if (playing) "Pausa" else "Riprendi",
                toggle,
            ).build(),
        )
        b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TeleCast:stream").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TeleCast:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("Servizio", "lock non disponibili: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wake = null
        wifi = null
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val NOTIF_ID = 42
        const val ACTION_TOGGLE = "it.telecast.action.TOGGLE"
        const val ACTION_STOP = "it.telecast.action.STOP"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, CastService::class.java))
            } catch (e: Exception) {
                Log.w("Servizio", "avvio non riuscito: ${e.message}")
            }
        }
    }
}
