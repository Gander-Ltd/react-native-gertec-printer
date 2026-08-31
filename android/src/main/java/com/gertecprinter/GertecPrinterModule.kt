package com.gertecprinter

import android.os.Handler
import android.os.Looper
import br.com.gertec.easylayer.printer.Alignment
import br.com.gertec.easylayer.printer.BarcodeFormat
import br.com.gertec.easylayer.printer.BarcodeType
import br.com.gertec.easylayer.printer.Printer
import br.com.gertec.easylayer.printer.PrinterError
import br.com.gertec.easylayer.printer.TextFormat
import br.com.gertec.easylayer.utils.BarCodeConfig
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import io.sentry.Sentry
import java.lang.reflect.InvocationTargetException
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

  // Gertec's own public printBarcode(BarcodeFormat, String) hardcodes one of 3 SQUARE
  // size presets (decompiled: Printer.calculateBarcodeSize) before building a
  // BarCodeConfig -- there's no public API to get a wide-and-short 1D barcode. We first
  // tried rendering the barcode ourselves (real ZXing) and printing it as an image via
  // printImageAutoResize instead. That reliably hung on real TSG820 hardware: Sentry
  // checkpoints around the call showed it entering printImageAutoResize and never
  // returning -- no callback, no exception, no crash. Gertec's own square barcode (same
  // request position in the sequence, same eventual native printBmp call) has always
  // completed normally, so the hang is specific to PrintRequest.Type.IMAGE handling, not
  // to bitmap content.
  //
  // Decompiling br.com.gertec.easylayer.utils.BarCodeConfig found that its own square
  // forcing lives entirely in Printer.calculateBarcodeSize -- BarCodeConfig itself
  // (public class, public constructor) accepts arbitrary width/height directly for
  // CODE_128 and several other 1D types. So a properly wide/short barcode going through
  // the *working* BARCODE request type is possible -- but Printer's own addPrintRequest
  // and the PrintRequest constructor are both private/package-private, unreachable from
  // outside br.com.gertec.easylayer.printer without reflection. queueBarcodeRequest below
  // does exactly what Printer.printBarcode() does internally, minus the square-forcing.
  override fun printBarcode(data: String, options: ReadableMap?, promise: Promise) {
    mainHandler.post {
      try {
        val (width, height) = parseBarcodeDimensions(options?.getString("size"))
        val type = parseGertecBarcodeType(options?.getString("type"))
        val config = BarCodeConfig(data, BarcodeFormat(type), width, height)
        // Diagnostic breadcrumb: no adb/log access to the real device this runs on, so
        // this is how we'll know, from the next test, whether the reflective call itself
        // is the thing that fails, versus the request being queued but never completing.
        logDiagnostic("GertecPrinter.printBarcode: BarCodeConfig ${width}x${height} built, queuing via BARCODE request type")
        val requestId = queueBarcodeRequest(config, data)
        logDiagnostic("GertecPrinter.printBarcode: queued requestId=$requestId")
        pendingPromises[requestId] = promise
      } catch (e: InvocationTargetException) {
        val cause = e.targetException ?: e
        reportToSentry(cause)
        promise.reject("GERTEC_PRINT_BARCODE_ERROR", cause.message, cause)
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

  // Mirrors what Printer.printBarcode(BarcodeFormat, String) does internally (decompiled):
  // new PrintRequest(PrintRequest.Type.BARCODE, config, data, true) followed by
  // addPrintRequest(request) -- both otherwise inaccessible outside
  // br.com.gertec.easylayer.printer. Looked up once and cached, since Class.forName/
  // getDeclaredConstructor/getDeclaredMethod are comparatively expensive and this runs
  // per barcode print, not in a hot loop.
  private val printRequestTypeClass: Class<*> by lazy {
    Class.forName("br.com.gertec.easylayer.printer.PrintRequest\$Type")
  }

  private val printRequestClass: Class<*> by lazy {
    Class.forName("br.com.gertec.easylayer.printer.PrintRequest")
  }

  private val barcodeRequestType: Any by lazy {
    printRequestTypeClass.getField("BARCODE").get(null)
  }

  private val printRequestConstructor by lazy {
    printRequestClass.getDeclaredConstructor(
      printRequestTypeClass,
      Any::class.java,
      Any::class.java,
      Boolean::class.javaPrimitiveType
    ).apply { isAccessible = true }
  }

  private val addPrintRequestMethod by lazy {
    Printer::class.java.getDeclaredMethod("addPrintRequest", printRequestClass)
      .apply { isAccessible = true }
  }

  private fun queueBarcodeRequest(config: BarCodeConfig, data: String): Int {
    val request = printRequestConstructor.newInstance(barcodeRequestType, config, data, true)
    return addPrintRequestMethod.invoke(printer, request) as Int
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

  private fun logDiagnostic(message: String) {
    try {
      Sentry.captureMessage(message)
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

  // BarCodeConfig/CodeUtil.createBarcode (decompiled) clamps up to a 200x50 minimum
  // internally and, like public ZXing's OneDimensionalCodeWriter, grows the output width
  // to fit whatever the data actually needs if the requested width is too small -- it
  // never truncates or corrupts a barcode that needs more room than requested. So these
  // are targets/minimums, not hard caps: safe to keep comfortably under MAX_PRINT_WIDTH
  // (384 dots, per Gertec's Printer.calculateBarcodeSize) while still giving
  // reduction.newBarCode's actual (unknown) length room to encode at a legible module
  // width. Heights are fixed and short regardless of size -- unlike Gertec's own
  // square-forced BarcodeFormat.Size presets -- so both text lines always have room left
  // on the 240-dot (30mm) label.
  private fun parseBarcodeDimensions(value: String?): Pair<Int, Int> = when (value) {
    "SMALL" -> 220 to 50
    "FULL_PAPER" -> 350 to 70
    else -> 290 to 60 // HALF_PAPER and default
  }

  private fun parseAlignment(value: String?): Alignment = when (value) {
    "CENTER" -> Alignment.CENTER
    "RIGHT" -> Alignment.RIGHT
    else -> Alignment.LEFT
  }

  private fun parseGertecBarcodeType(value: String?): BarcodeType = try {
    BarcodeType.valueOf(value ?: "CODE_128")
  } catch (e: IllegalArgumentException) {
    BarcodeType.CODE_128
  }

  companion object {
    const val NAME = NativeGertecPrinterSpec.NAME
  }
}
