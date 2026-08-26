package com.bncam.core.output

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CaptureProcessingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private val isForegroundActive = AtomicBoolean(false)
    private var idleStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CaptureProcessingService created")
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BnCam:ProcessingWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        observeQueueEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "CaptureProcessingService onStartCommand action=${intent?.action}")
        val nonIdle = CaptureProcessingQueue.nonIdleCount() + CaptureSaveQueue.inFlightCount()
        promoteToForeground(nonIdle)
        evaluateWorkAndWakeLock()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "CaptureProcessingService onDestroy")
        idleStopJob?.cancel()
        releaseWakeLockSafely()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(activeCount: Int = 1) {
        val text = if (activeCount > 1) "Processing $activeCount photos..." else "Processing photo..."
        val notification = buildNotification(text)
        if (isForegroundActive.compareAndSet(false, true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val mayPostNotification =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (mayPostNotification) {
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun observeQueueEvents() {
        serviceScope.launch {
            CaptureProcessingQueue.snapshotsFlow.collectLatest {
                evaluateWorkAndWakeLock()
            }
        }
    }

    private fun evaluateWorkAndWakeLock() {
        val activeJobs = CaptureProcessingQueue.nonIdleCount() + CaptureSaveQueue.inFlightCount()
        Log.d(TAG, "evaluateWorkAndWakeLock activeJobs=$activeJobs")

        if (activeJobs > 0) {
            idleStopJob?.cancel()
            idleStopJob = null
            promoteToForeground(activeJobs)
            acquireWakeLockSafely()
        } else {
            releaseWakeLockSafely()
            idleStopJob?.cancel()
            idleStopJob = serviceScope.launch {
                delay(300)
                val rechecked = CaptureProcessingQueue.nonIdleCount() + CaptureSaveQueue.inFlightCount()
                if (rechecked == 0 && isForegroundActive.get()) {
                    Log.i(TAG, "All queues confirmed idle. Stopping service.")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForegroundActive.set(false)
                    stopSelf()
                }
            }
        }
    }

    private fun acquireWakeLockSafely() {
        try {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(10 * 60 * 1000L /* 10 minutes timeout limit */)
                    Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK for background processing")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire PARTIAL_WAKE_LOCK: ${e.message}")
        }
    }

    private fun releaseWakeLockSafely() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "Released PARTIAL_WAKE_LOCK")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release PARTIAL_WAKE_LOCK: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BnCam Processing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BnCam RAW processing active in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BnCam")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "CaptureProcessingService"
        private const val CHANNEL_ID = "bncam_processing_channel"
        private const val NOTIFICATION_ID = 9981

        fun ensureRunning(context: Context) {
            try {
                val appContext = context.applicationContext
                val intent = Intent(appContext, CaptureProcessingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CaptureProcessingService: ${e.message}")
            }
        }
    }
}
