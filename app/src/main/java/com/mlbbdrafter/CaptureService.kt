package com.mlbbdrafter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Screen capture is read-only. It never injects taps or touches into MLBB. */
class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_RESULT = "com.mlbbdrafter.DETECTION_RESULT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_KIND = "kind"
        const val EXTRA_INDEX = "index"
        const val EXTRA_HERO_ID = "hero_id"
        const val EXTRA_CONF = "confidence"
        const val EXTRA_TEMPLATE_COUNT = "template_count"
        private const val CHANNEL = "draft_detection"
        private const val NOTIFICATION_ID = 4401
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var lastRun = 0L
    private lateinit var matcher: HeroTemplateMatcher

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, android.content.Intent::class.java)
                   else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code <= 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "MLBB Draft Detection", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MLBB Draft Assistant")
            .setContentText("Screen detection is active — read-only")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, n)
    }

    private fun startCapture(code: Int, data: Intent) {
        if (running.getAndSet(true)) return
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val density = dm.densityDpi
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ ir ->
            val now = System.currentTimeMillis()
            if (now - lastRun < 350L) {
                ir.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastRun = now
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                detect(bmp, w, h)
                bmp.recycle()
            } finally { image.close() }
        }, handler)
        display = projection!!.createVirtualDisplay(
            "MLBB-Draft-ReadOnly", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
    }

    private fun detect(frame: Bitmap, w: Int, h: Int) {
        val ds = Store.load(this)
        matcher = HeroTemplateMatcher(java.io.File(filesDir, "hero_templates"), ds.heroes)
        matcher.reload()
        sendStatus("DETECTOR ACTIVE • ${matcher.size()} TEMPLATES")
        if (matcher.size() == 0) return
        for (slot in DraftSlotLayout.slots(w, h)) {
            val r = Rect(
                (slot.rect.left * w).toInt(), (slot.rect.top * h).toInt(),
                (slot.rect.right * w).toInt(), (slot.rect.bottom * h).toInt()
            )
            val crop = frame.cropSafe(r) ?: continue
            val result = matcher.match(crop)
            crop.recycle()
            if (result != null && result.second >= 0.84) {
                val heroId = result.first
                val hero = ds.heroes[heroId]
                val text = "✓ DETECTED: ${hero?.name ?: heroId}  ${"%.0f".format(result.second * 100)}%"
                sendResult(slot, heroId, result.second, text)
            }
        }
    }

    private fun sendStatus(text: String) {
        sendBroadcast(Intent(ACTION_RESULT).apply { putExtra(EXTRA_TEXT, text) })
    }

    private fun sendResult(slot: DraftSlotLayout.Slot, id: String, confidence: Double, text: String) {
        sendBroadcast(Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_KIND, slot.kind.name)
            putExtra(EXTRA_INDEX, slot.index)
            putExtra(EXTRA_HERO_ID, id)
            putExtra(EXTRA_CONF, confidence)
            putExtra(EXTRA_TEMPLATE_COUNT, matcher.size())
        })
    }

    override fun onDestroy() {
        running.set(false)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        super.onDestroy()
    }
}
