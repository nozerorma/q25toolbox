package com.kgr.q25toolbox.modules

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.kgr.q25toolbox.R
import com.kgr.q25toolbox.core.RootShell
import java.io.File
import java.util.concurrent.Executors

/**
 * QR / Aztec Code Enlarger:
 * Captures screen, detects 2D barcodes (QR Code & Aztec), and displays them
 * maximized fullscreen on a pure white canvas at 100% screen brightness,
 * temporarily turning off Extra Dimming while displayed. Tapping or pressing
 * any key dismisses the overlay and restores the previous display settings.
 */
object QrEnlargerController {

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeView: View? = null
    private var activeWindowManager: WindowManager? = null

    private data class DisplayState(
        val wasExtraDimActive: Boolean,
        val brightnessMode: Int,
        val brightnessVal: Int
    )

    private val stateLock = Any()
    @Volatile private var savedState: DisplayState? = null
    @Volatile private var isProcessing: Boolean = false

    fun isShowing(): Boolean = activeView != null

    fun trigger(service: AccessibilityService) {
        Log.d("Q25Toolbox", "QrEnlarger.trigger called (isShowing=${isShowing()})")
        if (isShowing()) {
            hide("toggle")
            return
        }

        synchronized(stateLock) {
            if (isProcessing) {
                Log.d("Q25Toolbox", "QrEnlarger.trigger: capture/decode already in progress, ignoring")
                return
            }
            isProcessing = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (t: Throwable) {
                                Log.w("Q25Toolbox", "wrapHardwareBuffer failed", t)
                                null
                            } finally {
                                hwBuffer.close()
                            }

                            if (bitmap != null) {
                                Log.d("Q25Toolbox", "QrEnlarger: takeScreenshot bitmap = ${bitmap.width}x${bitmap.height}")
                                worker.execute { processBitmap(service, bitmap) }
                            } else {
                                Log.w("Q25Toolbox", "QrEnlarger: bitmap was null, falling back to root screencap")
                                processViaRoot(service)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w("Q25Toolbox", "takeScreenshot onFailure: errorCode=$errorCode")
                            processViaRoot(service)
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w("Q25Toolbox", "takeScreenshot exception", t)
                processViaRoot(service)
            }
        } else {
            processViaRoot(service)
        }
    }

    private fun processViaRoot(service: AccessibilityService) {
        worker.execute {
            val tmpPath = "/data/local/tmp/qr_screencap_${System.currentTimeMillis()}.png"
            val res = RootShell.run("screencap -p $tmpPath")
            if (res.success && File(tmpPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(tmpPath)
                RootShell.run("rm -f $tmpPath")
                if (bitmap != null) {
                    Log.d("Q25Toolbox", "QrEnlarger: root screencap bitmap = ${bitmap.width}x${bitmap.height}")
                    decodeAndShow(service, bitmap, allowRootRetry = false)
                    return@execute
                }
            }
            Log.e("Q25Toolbox", "QrEnlarger: screencap failed")
            isProcessing = false
            mainHandler.post {
                Toast.makeText(service, service.getString(R.string.qr_enlarger_capture_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processBitmap(service: AccessibilityService, bitmap: Bitmap) =
        decodeAndShow(service, bitmap, allowRootRetry = true)

    /**
     * Decodes [bitmap] and shows the enlarged overlay. On a decode failure the screenshot from
     * AccessibilityService.takeScreenshot gets one retry through root screencap: takeScreenshot can
     * come back without the foreground app's content (FLAG_SECURE and similar filtering), while root
     * screencap sees the real screen - e.g. the Renfe ticket QR decodes from screencap but not from
     * takeScreenshot. The root path passes [allowRootRetry] = false so it can never recurse.
     */
    private fun decodeAndShow(service: AccessibilityService, bitmap: Bitmap, allowRootRetry: Boolean) {
        try {
            val result = decodeBarcode(bitmap)
            Log.d("Q25Toolbox", "QrEnlarger: decode result = ${result?.text} (format=${result?.barcodeFormat})")
            if (result == null) {
                if (allowRootRetry) {
                    Log.w("Q25Toolbox", "QrEnlarger: no barcode in takeScreenshot; retrying via root screencap")
                    processViaRoot(service)
                } else {
                    isProcessing = false
                    mainHandler.post {
                        Toast.makeText(service, service.getString(R.string.qr_enlarger_none_found), Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }

            val enlargedBitmap = renderBarcode(service, result)
            if (enlargedBitmap == null) {
                Log.e("Q25Toolbox", "QrEnlarger: renderBarcode failed")
                isProcessing = false
                mainHandler.post {
                    Toast.makeText(service, service.getString(R.string.qr_enlarger_render_failed), Toast.LENGTH_SHORT).show()
                }
                return
            }

            isProcessing = false
            mainHandler.post {
                showOverlay(service, enlargedBitmap)
            }
        } catch (t: Throwable) {
            isProcessing = false
            Log.e("Q25Toolbox", "QrEnlarger: decode error", t)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBarcode(bitmap: Bitmap): Result? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC),
            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE
        )

        val reader = MultiFormatReader()

        // 1. Standard HybridBinarizer
        try {
            return reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
        } catch (_: Throwable) {}

        // 2. GlobalHistogramBinarizer
        try {
            return reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints)
        } catch (_: Throwable) {}

        // 3. Inverted luminance (for dark mode / inverted QR codes)
        try {
            return reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints)
        } catch (_: Throwable) {}

        return null
    }

    private fun renderBarcode(context: Context, result: Result): Bitmap? {
        val format = result.barcodeFormat
        val text = result.text ?: return null

        val displayMetrics = context.resources.displayMetrics
        val minDim = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels)
        val targetSize = (minDim * 0.92f).toInt().coerceAtLeast(300)

        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(text, format, targetSize, targetSize, hints)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    out.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    private var shownAtMs: Long = 0L

    private fun removeActiveView() {
        val v = activeView
        val wm = activeWindowManager
        activeView = null
        activeWindowManager = null
        if (v != null && wm != null) {
            try {
                wm.removeView(v)
            } catch (_: Throwable) {}
        }
    }

    private fun showOverlay(service: AccessibilityService, barcodeBitmap: Bitmap) {
        removeActiveView()

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        shownAtMs = System.currentTimeMillis()
        Log.d("Q25Toolbox", "QrEnlarger: showing overlay with max brightness")

        // 1. Temporarily save display state, disable Extra Dim, and max out brightness
        worker.execute {
            synchronized(stateLock) {
                if (savedState == null) {
                    val extraDim = ExtraDimController.isActivated()
                    val bMode = RootShell.run("settings get system screen_brightness_mode").outString.trim().toIntOrNull() ?: 0
                    val bVal = RootShell.run("settings get system screen_brightness").outString.trim().toIntOrNull() ?: 128
                    savedState = DisplayState(
                        wasExtraDimActive = extraDim,
                        brightnessMode = bMode,
                        brightnessVal = bVal
                    )
                    Log.d("Q25Toolbox", "QrEnlarger: saved display state = $savedState")
                }
            }

            if (ExtraDimController.isActivated()) {
                ExtraDimController.setActivated(false)
            }
            RootShell.run("settings put system screen_brightness_mode 0 && settings put system screen_brightness 255")
        }

        // 2. Build full-screen white canvas with centered barcode
        val dismissAction = {
            if (System.currentTimeMillis() - shownAtMs > 250) {
                hide("click")
            }
        }

        val container = FrameLayout(service).apply {
            setBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = false
            setOnClickListener { dismissAction() }
        }

        val imageView = ImageView(service).apply {
            setImageBitmap(barcodeBitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (service.resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { dismissAction() }
        }

        val ivLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        container.addView(imageView, ivLp)

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "QrEnlarger"
            screenBrightness = 1.0f // Maximum brightness
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }

        try {
            wm.addView(container, windowParams)
            activeView = container
            activeWindowManager = wm
        } catch (t: Throwable) {
            Log.e("Q25Toolbox", "QrEnlarger: addView failed", t)
            hide("addView_error")
        }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isShowing()) return false
        if (System.currentTimeMillis() - shownAtMs < 300) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            hide("key_${event.keyCode}")
            return true
        }
        return false
    }

    fun hide(reason: String = "manual") {
        Log.d("Q25Toolbox", "QrEnlarger.hide: reason=$reason")
        val doHide = {
            removeActiveView()

            // Restore display states
            worker.execute {
                val stateToRestore = synchronized(stateLock) {
                    val s = savedState
                    savedState = null
                    s
                }

                if (stateToRestore != null) {
                    Log.d("Q25Toolbox", "QrEnlarger: restoring display state = $stateToRestore")
                    if (stateToRestore.wasExtraDimActive) {
                        ExtraDimController.setActivated(true)
                    }
                    RootShell.run(
                        "settings put system screen_brightness ${stateToRestore.brightnessVal} && " +
                        "settings put system screen_brightness_mode ${stateToRestore.brightnessMode}"
                    )
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doHide()
        } else {
            mainHandler.post(doHide)
        }
    }
}
