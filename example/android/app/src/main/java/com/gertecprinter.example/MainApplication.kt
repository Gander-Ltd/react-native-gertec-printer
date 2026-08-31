package gertecprinter.example

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.load
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.defaults.DefaultReactNativeHost
import com.facebook.soloader.SoLoader
import io.sentry.android.core.SentryAndroid

class MainApplication : Application(), ReactApplication {

  override val reactNativeHost: ReactNativeHost =
      object : DefaultReactNativeHost(this) {
        override fun getPackages(): List<ReactPackage> =
            PackageList(this).packages.apply {
              // Packages that cannot be autolinked yet can be added manually here, for example:
              // add(MyReactNativePackage())
            }

        override fun getJSMainModuleName(): String = "index"

        override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

        override val isNewArchEnabled: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
        override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED
      }

  override val reactHost: ReactHost
    get() = getDefaultReactHost(applicationContext, reactNativeHost)

  override fun onCreate() {
    super.onCreate()
    // Native-only Sentry init (no @sentry/react-native JS package) so
    // GertecPrinterModule's existing reportToSentry/logDiagnostic calls -- which no-op
    // silently without Sentry present -- actually produce visible events here. Same
    // DSN/project as bruce-in-a-box, distinguished by environment, so this doesn't need
    // its own dashboard.
    //
    // Application.onCreate() failing is fatal to the whole app, and this call is
    // unlike GertecPrinterModule's own Sentry calls (which are all defensively wrapped)
    // -- wrapped here too so a Sentry-side problem degrades to "no diagnostics" instead
    // of "app won't launch at all".
    try {
      SentryAndroid.init(this) { options ->
        options.dsn = "https://c797e7092237510f69db887d0163870b@o4510868688470016.ingest.de.sentry.io/4511976254734416"
        options.environment = "gertec-printer-example"
        // Only Sentry.captureMessage/captureException (plain JVM calls from Kotlin) are
        // actually needed here -- the NDK native crash handler is extra surface this
        // proprietary embedded firmware doesn't need to exercise.
        options.isEnableNdk = false
      }
    } catch (e: Throwable) {
      // Best-effort diagnostics only; never let this be the thing that crashes.
    }
    SoLoader.init(this, false)
    if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
      // If you opted-in for the New Architecture, we load the native entry point for this app.
      load()
    }
  }
}
