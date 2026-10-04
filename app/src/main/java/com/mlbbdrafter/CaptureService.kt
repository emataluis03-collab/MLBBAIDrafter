package com.mlbbdrafter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File

/**
 * READ-ONLY screen observer. It captures frames, compares draft-slot crops with hero templates and
 * broadcasts the result to our own overlay. It never injects taps, picks, bans or any input into MLBB.
 */
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
        private const val CHANNEL = "draft_detection"
        private const val NOTIFICATION_ID = 4401
        private const val MIN_SCORE = 0.60      // correlation needed to count a frame
        private const val MIN_MARGIN = 0.05     // best must beat the 2nd best hero by this much
        private const val STABLE_FRAMES = 3     // consecutive agreeing frames before the draft changes
        private const val FRAME_GAP_MS = 400L   // at most ~2.5 analysed frames per second
        private const val MAX_SIDE = 1280       // capture is downscaled: no need for full resolution
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var matcher: HeroTemplateMatcher? = null
    private var lastRun = 0L
    private var capW = 0
    private var capH = 0
    private var started = false
    private var frames = 0
    private var lastStatus = 0L
    private var names: Map<String, String> = emptyMap()
    private val streak = HashMap<String, Pair<String, Int>>()
    private val confirmed = HashMap<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        // startForegroundService() requires startForeground() quickly, even if we then give up.
        runCatching { startForegroundCompat() }.onFailure { Log.e("Drafter", "foreground failed", it) }
        if (started) return START_NOT_STICKY
        if (code == -1 || data == null) {
            // Only a result from the system consent screen is valid. Do nothing else.
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCapture(code, data)
            started = true
        } catch (e: Exception) {
            Log.e("Drafter", "capture start failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "MLBB Draft Detection", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MLBB Draft Assistant")
            .setContentText("Screen detection is active (read-only)")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** Capture size: real screen, scaled so the long side is at most MAX_SIDE. */
    private fun captureSize(): Pair<Int, Int> {
        val (w, h) = realSize()
        val longSide = maxOf(w, h)
        val f = if (longSide > MAX_SIDE) MAX_SIDE.toFloat() / longSide else 1f
        return maxOf(2, (w * f).toInt()) to maxOf(2, (h * f).toInt())
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> onFrame(ir, w, h) }, handler)
        return r
    }

    private fun startCapture(code: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(code, data)
        if (proj == null) {
            stopSelf()
            return
        }
        projection = proj

        val t = HandlerThread("draft-capture")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h

        // Android 14+ requires a callback to be registered BEFORE creating the virtual display.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)

        // Templates are loaded ONCE here, not on every frame.
        val ds = Store.load(this)
        names = ds.heroes.mapValues { it.value.name }
        val m = HeroTemplateMatcher(File(filesDir, "hero_templates"), ds.heroes)
        m.reload()
        matcher = m

        val (cw, ch) = captureSize()
        capW = cw
        capH = ch
        val r = newReader(cw, ch)
        reader = r
        display = proj.createVirtualDisplay(
            "MLBB-Draft-ReadOnly", cw, ch, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
        )
        val n = m.size()
        h.postDelayed({
            sendStatus(if (n == 0) "NO TEMPLATES - DOWNLOAD THEM FIRST" else "DETECTOR ACTIVE - $n TEMPLATES")
        }, 800)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val disp = display ?: return
        val (cw, ch) = captureSize()
        if (cw == capW && ch == capH) return
        try {
            capW = cw
            capH = ch
            val old = reader
            val r = newReader(cw, ch)
            reader = r
            disp.resize(cw, ch, resources.displayMetrics.densityDpi)
            disp.surface = r.surface
            old?.close()
        } catch (e: Exception) {
            Log.e("Drafter", "resize failed", e)
        }
    }

    private fun onFrame(ir: ImageReader, w: Int, h: Int) {
        val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < FRAME_GAP_MS) return
            lastRun = now
            val plane = image.planes[0]
            val padded = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            analyse(full, w, h)
            full.recycle()
        } catch (e: Exception) {
            Log.e("Drafter", "frame failed", e)
        } finally {
            image.close()
        }
    }

    private fun tag(s: DraftSlotLayout.SlotRect): String {
        val k = when (s.kind) {
            Slot.ALLY_BAN -> "AB"
            Slot.ENEMY_BAN -> "EB"
            Slot.ALLY_PICK -> "AP"
            Slot.ENEMY_PICK -> "EP"
        }
        return k + (s.index + 1)
    }

    /** Rough screen brightness (0..100) from a sparse grid; ~0 means the frame is black. */
    private fun brightness(f: Bitmap, w: Int, h: Int): Int {
        var sum = 0.0
        var n = 0
        for (gy in 1..8) for (gx in 1..14) {
            val c = f.getPixel(gx * (w - 1) / 15, gy * (h - 1) / 9)
            sum += 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)
            n++
        }
        return (sum / n / 255.0 * 100.0).toInt()
    }

    /** Looks only at the 20 draft-slot crops, never the whole frame. */
    private fun analyse(frame: Bitmap, w: Int, h: Int) {
        val m = matcher ?: return
        frames++
        var bestScore = -1.0
        var bestId: String? = null
        var bestWhere = ""
        if (m.size() > 0) {
            for (s in DraftSlotLayout.slots(this)) {
                val key = s.kind.name + ":" + s.index
                val rect = Rect(
                    (s.rect.left * w).toInt(), (s.rect.top * h).toInt(),
                    (s.rect.right * w).toInt(), (s.rect.bottom * h).toInt()
                )
                val crop = frame.cropSafe(rect) ?: continue
                val res = m.match(crop)
                crop.recycle()
                if (res != null && res.score > bestScore) {
                    bestScore = res.score
                    bestId = res.id
                    bestWhere = tag(s)
                }
                if (res == null || res.score < MIN_SCORE || res.margin < MIN_MARGIN) {
                    streak.remove(key)           // uncertain frame: never changes the draft
                    continue
                }
                val prev = streak[key]
                val count = if (prev != null && prev.first == res.id) prev.second + 1 else 1
                streak[key] = Pair(res.id, count)
                if (count >= STABLE_FRAMES && confirmed[key] != res.id) {
                    confirmed[key] = res.id
                    sendResult(s, res.id, res.score)
                }
            }
        }
        val t = SystemClock.elapsedRealtime()
        if (t - lastStatus >= 1000L) {
            lastStatus = t
            val bright = brightness(frame, w, h)
            val best = when {
                m.size() == 0 -> "NO TEMPLATES"
                bestId == null -> "no match"
                else -> (names[bestId] ?: bestId) + " " + (bestScore * 100).toInt() + "% @" + bestWhere
            }
            val verdict = if (m.size() > 0 && bestScore < MIN_SCORE) "UNKNOWN HERO - " else ""
            sendStatus(verdict + "best: " + best + "\nframes " + frames + " | " + w + "x" + h + " | bright " + bright + "%" +
                (if (bright < 3) " | BLACK FRAME?" else ""))
        }
    }

    private fun sendStatus(text: String) {
        sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName).putExtra(EXTRA_TEXT, text))
    }

    private fun sendResult(slot: DraftSlotLayout.SlotRect, id: String, confidence: Double) {
        sendBroadcast(
            Intent(ACTION_RESULT).setPackage(packageName)
                .putExtra(EXTRA_KIND, slot.kind.name)
                .putExtra(EXTRA_INDEX, slot.index)
                .putExtra(EXTRA_HERO_ID, id)
                .putExtra(EXTRA_CONF, confidence)
        )
    }

    override fun onDestroy() {
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { thread?.quitSafely() }
        thread = null
        super.onDestroy()
    }
}
