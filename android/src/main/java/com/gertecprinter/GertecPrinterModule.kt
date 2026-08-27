package com.gertecprinter

import android.os.Handler
import android.os.Looper
import br.com.gertec.easylayer.printer.Alignment
import br.com.gertec.easylayer.printer.BarcodeFormat
import br.com.gertec.easylayer.printer.BarcodeType
import br.com.gertec.easylayer.printer.Printer
import br.com.gertec.easylayer.printer.PrinterError
import br.com.gertec.easylayer.printer.TextFormat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
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

  override fun printBarcode(data: String, options: ReadableMap?, promise: Promise) {
    mainHandler.post {
      try {
        val requestId = printer.printBarcode(buildBarcodeFormat(options), data)
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

  private fun buildBarcodeFormat(options: ReadableMap?): BarcodeFormat {
    val type = parseBarcodeType(options?.getString("type"))
    val format = BarcodeFormat(type)
    if (options != null && options.hasKey("size")) {
      format.size = parseBarcodeSize(options.getString("size"))
    }
    if (options != null && options.hasKey("whiteSpace")) {
      format.whiteSpace = options.getInt("whiteSpace")
    }
    return format
  }

  private fun parseAlignment(value: String?): Alignment = when (value) {
    "CENTER" -> Alignment.CENTER
    "RIGHT" -> Alignment.RIGHT
    else -> Alignment.LEFT
  }

  private fun parseBarcodeType(value: String?): BarcodeType = try {
    BarcodeType.valueOf(value ?: "CODE_128")
  } catch (e: IllegalArgumentException) {
    BarcodeType.CODE_128
  }

  private fun parseBarcodeSize(value: String?): BarcodeFormat.Size = try {
    BarcodeFormat.Size.valueOf(value ?: "FULL_PAPER")
  } catch (e: IllegalArgumentException) {
    BarcodeFormat.Size.FULL_PAPER
  }

  companion object {
    const val NAME = NativeGertecPrinterSpec.NAME
  }
}
