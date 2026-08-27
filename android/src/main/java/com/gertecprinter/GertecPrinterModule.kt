package com.gertecprinter

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import br.com.gertec.easylayer.printer.Alignment
import br.com.gertec.easylayer.printer.Printer
import br.com.gertec.easylayer.printer.PrinterError
import br.com.gertec.easylayer.printer.TextFormat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.google.zxing.BarcodeFormat as ZXBarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import io.sentry.Sentry
import java.util.concurrent.ConcurrentHashMap

// Wraps br.com.gertec.easylayer.printer.Printer (Gertec EasyLayer SDK, TSG820/TSG810
// built-in printer). The SDK is callback-based: each print call returns a request id
// synchronously, and Printer.Listener.onPrinterSuccessful/onPrinterError fires later for
// that id. We keep a table of pending Promises keyed by request id to bridge that into
// the Promise-based JS API.
class GertecPrinterModule(reactContext: ReactApplicationContext) :
  NativeGertecPrinterSpec(reactContext),
  Printer.Listener {

  private val pendingPromises = ConcurrentHashMap<Int, Promise>()

  // Gertec's own sample app (Impressora.java) only ever calls the SDK from a
  // View.OnClickListener, which Android always runs on the main thread. React Native
  // TurboModule methods run on a background bridge thread by default. Printer's internal
  // request queue/worker (processRequest, checkPendingRequests, decompiled from the SDK)
  // looks like it depends on a Handler/Looper the SDK sets up around its own thread
  // model -- calling from a background thread risks requests being queued but never
  // drained: no exception, no callback, nothing physically prints. Dispatching every SDK
  // call through the main Looper matches how the SDK was actually designed to be driven.
  private val mainHandler = Handler(Looper.getMainLooper())

  // Gertec's own sample app calls Printer.getInstance(activity, listener) -- passing the
  // Application context instead (as this used to) makes getInstance() return null on real
  // TSG820 hardware, which crashes here with "getValue(...) must not be null" since Kotlin
  // null-checks non-nullable `by lazy` results. A failed lazy evaluation isn't cached, so
  // this retries on the next call if currentActivity wasn't available yet.
  private val printer: Printer by lazy {
    val context = reactApplicationContext.currentActivity ?: reactApplicationContext.applicationContext
    Printer.getInstance(context, this)
  }

  override fun hasPrinter(promise: Promise) {
    mainHandler.post {
      try {
        printer.status
        promise.resolve(true)
      } catch (e: Throwable) {
        // Catches Throwable, not just Exception: the vendored SDK bundles a large EMV/NFC
        // native library set that's almost entirely armeabi-v7a only (one arm64-v8a .so
        // out of ~19). On an arm64-preferring install those libraries never get extracted,
        // so if the SDK eagerly touches any of them during init, the JVM throws
        // UnsatisfiedLinkError/LinkageError -- an Error, not an Exception -- which a plain
        // `catch (Exception)` would miss entirely and crash the whole app.
        //
        // We still resolve(false) rather than reject, since the JS side treats this as a
        // normal "no printer" signal -- but that makes a genuine SDK failure on real Gertec
        // hardware indistinguishable from legitimately no printer present. Report it to
        // Sentry so the real reason isn't silently lost.
        reportToSentry(e)
        promise.resolve(false)
      }
    }
  }

  override fun getStatus(promise: Promise) {
    mainHandler.post {
      try {
        val status = printer.status
        val result = Arguments.createMap()
        result.putInt("code", status.code)
        result.putString("message", status.toString())
        promise.resolve(result)
      } catch (e: Throwable) {
        reportToSentry(e)
        promise.reject("GERTEC_STATUS_ERROR", e.message, e)
      }
    }
  }

  override fun printText(text: String, options: ReadableMap?, promise: Promise) {
    mainHandler.post {
      try {
        val requestId = printer.printText(buildTextFormat(options), text)
        pendingPromises[requestId] = promise
      } catch (e: Throwable) {
        reportToSentry(e)
        promise.reject("GERTEC_PRINT_TEXT_ERROR", e.message, e)
      }
    }
  }

  // Gertec's own printBarcode/BarcodeFormat (decompiled: BarCodeConfig.calculateBarcodeSize)
  // forces width == height for every size preset, including 1D types -- there's no way
  // to get a normal wide-and-short barcode through that API, and a square big enough to
  // encode a long code reliably eats most of a short label's height. Instead we render
  // the barcode ourselves with a real ZXing dependency (unrelated to Gertec's own
  // internally-shaded, differently-packaged copy at br.com.gertec.easylayer.zxing.*) and
  // print the resulting bitmap via printImageAutoResize, so width and height are
  // independent.
  override fun printBarcode(data: String, options: ReadableMap?, promise: Promise) {
    mainHandler.post {
      try {
        val requestId = printer.printImageAutoResize(buildBarcodeBitmap(data, options))
        pendingPromises[requestId] = promise
      } catch (e: Throwable) {
        reportToSentry(e)
        promise.reject("GERTEC_PRINT_BARCODE_ERROR", e.message, e)
      }
    }
  }

  override fun scrollPaper(lines: Double, promise: Promise) {
    mainHandler.post {
      try {
        val requestId = printer.scrollPaper(lines.toInt())
        pendingPromises[requestId] = promise
      } catch (e: Throwable) {
        reportToSentry(e)
        promise.reject("GERTEC_SCROLL_ERROR", e.message, e)
      }
    }
  }

  override fun onPrinterSuccessful(requestId: Int) {
    pendingPromises.remove(requestId)?.resolve(true)
  }

  override fun onPrinterError(error: PrinterError) {
    Sentry.captureMessage(
      "GertecPrinter onPrinterError: code=${error.code} cause=${error.cause}"
    )
    pendingPromises.remove(error.requestId)?.reject(error.code.toString(), error.cause)
  }

  // Sentry is a compileOnly dependency here -- present at runtime via the consuming
  // app's @sentry/react-native install, not guaranteed for every future consumer of this
  // package -- so this is wrapped defensively rather than assumed to always work.
  private fun reportToSentry(e: Throwable) {
    try {
      Sentry.captureException(e)
    } catch (sentryError: Throwable) {
      // Best-effort diagnostics only; never let this be the thing that crashes.
    }
  }

  private fun buildTextFormat(options: ReadableMap?): TextFormat {
    val format = TextFormat()
    if (options == null) return format
    if (options.hasKey("bold")) format.bold = options.getBoolean("bold")
    if (options.hasKey("italic")) format.italic = options.getBoolean("italic")
    if (options.hasKey("underscore")) format.underscore = options.getBoolean("underscore")
    if (options.hasKey("fontSize")) format.fontSize = options.getInt("fontSize")
    if (options.hasKey("lineSpacing")) format.lineSpacing = options.getInt("lineSpacing")
    if (options.hasKey("alignment")) {
      format.alignment = parseAlignment(options.getString("alignment"))
    }
    return format
  }

  private fun buildBarcodeBitmap(data: String, options: ReadableMap?): Bitmap {
    val format = parseZXBarcodeFormat(options?.getString("type"))
    val (width, height) = parseBarcodeDimensions(options?.getString("size"))
    // ZXing's own default quiet-zone margin is already scan-safe; only override it if
    // the caller explicitly asked for a different one via the existing whiteSpace option.
    val hints: Map<EncodeHintType, Any> =
      if (options != null && options.hasKey("whiteSpace")) {
        mapOf(EncodeHintType.MARGIN to options.getInt("whiteSpace"))
      } else {
        emptyMap()
      }
    val matrix = MultiFormatWriter().encode(data, format, width, height, hints)
    return bitMatrixToBitmap(matrix)
  }

  // ZXing's 1D writers (OneDimensionalCodeWriter.renderResult, decompiled) only ever
  // grow the requested width up to whatever the data actually needs -- outputWidth =
  // max(width, naturalWidth) -- they never truncate or corrupt a barcode that needs
  // more room than requested, they just render wider than asked. So these are
  // targets/minimums, not hard caps: safe to keep comfortably under MAX_PRINT_WIDTH
  // (384 dots, per Gertec's BarCodeConfig) while still giving reduction.newBarCode's
  // actual (unknown) length room to encode at a legible module width. Heights are
  // fixed and short regardless of size -- unlike Gertec's own square-forced
  // BarcodeFormat.Size presets -- so both text lines always have room left on the
  // 240-dot (30mm) label.
  private fun parseBarcodeDimensions(value: String?): Pair<Int, Int> = when (value) {
    "SMALL" -> 220 to 50
    "FULL_PAPER" -> 350 to 70
    else -> 290 to 60 // HALF_PAPER and default
  }

  private fun bitMatrixToBitmap(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      val offset = y * width
      for (x in 0 until width) {
        pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
      }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
  }

  private fun parseAlignment(value: String?): Alignment = when (value) {
    "CENTER" -> Alignment.CENTER
    "RIGHT" -> Alignment.RIGHT
    else -> Alignment.LEFT
  }

  // Every br.com.gertec.easylayer.printer.BarcodeType name matches a
  // com.google.zxing.BarcodeFormat name exactly, so this is a straight parse rather
  // than a lookup table.
  private fun parseZXBarcodeFormat(value: String?): ZXBarcodeFormat = try {
    ZXBarcodeFormat.valueOf(value ?: "CODE_128")
  } catch (e: IllegalArgumentException) {
    ZXBarcodeFormat.CODE_128
  }

  companion object {
    const val NAME = NativeGertecPrinterSpec.NAME
  }
}
