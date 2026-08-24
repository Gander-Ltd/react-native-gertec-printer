package com.gertecprinter

import com.facebook.react.bridge.ReactApplicationContext

class GertecPrinterModule(reactContext: ReactApplicationContext) :
  NativeGertecPrinterSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeGertecPrinterSpec.NAME
  }
}
