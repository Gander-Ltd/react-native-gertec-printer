# @gander-ltd/react-native-gertec-printer

React Native (TurboModule) bridge for the built-in thermal printer on Gertec's
"Terminal Smart" devices (TSG810, TSG820), wrapping Gertec's proprietary
**EasyLayer** Android SDK.

Android only — Gertec does not publish an EasyLayer SDK for iOS, so this
package has no iOS implementation (same as `@gander-ltd/react-native-sunmi-printer`).

## A note on the vendored SDK

`android/libs/EasyLayer-TSG820-v2.1.8-release.aar` is Gertec's own binary SDK,
obtained from their gated developer portal (`developer.gertec.com.br`), not
from Maven. It is vendored directly in this repo because that's how Gertec
distributes it (see their own sample project, which does the same).

Before making this repo public or publishing it to the public npm registry,
**confirm Gertec's terms allow redistributing the AAR** — the developer
portal is login-gated, which suggests it isn't meant to be freely
redistributed. `publishConfig.access` is set to `restricted` and
`release-it`'s `npm.publish` is `false` in `package.json` for this reason —
both need a deliberate opt-in before anything gets published.

Also worth validating early, on real TSG820 hardware: the AAR's own
`AndroidManifest.xml` declares `android:sharedUserId="android.uid.system"`.
If that survives Android's manifest merger into a normally-signed (non
platform-signed) APK, it can break the build. Do a throwaway build + install
on device before investing further.

## Installation

This is a private package, installed from git rather than a registry:

```sh
yarn add "@gander-ltd/react-native-gertec-printer@git+https://github.com/Gander-Ltd/react-native-gertec-printer.git"
```

## Usage

```ts
import GertecPrinter, {
  Alignment,
  BarcodeType,
} from '@gander-ltd/react-native-gertec-printer';

const present = await GertecPrinter.hasPrinter();

await GertecPrinter.printText('TOO GOOD TO WASTE', {
  bold: true,
  fontSize: 30,
  alignment: Alignment.CENTER,
});

await GertecPrinter.printBarcode('900015950000221553595', {
  type: BarcodeType.CODE_128,
});

await GertecPrinter.scrollPaper(3);
```

## API

All methods return Promises and reject on underlying SDK errors (`hasPrinter`
is the one exception — it resolves `false` instead of rejecting).

### `hasPrinter(): Promise<boolean>`

Resolves `true` if the Gertec printer service responds.

### `getStatus(): Promise<{ code: number; message: string }>`

Wraps `Printer.getStatus()` — reports `OK`, `OUT_OF_PAPER`, `OVERHEAT`,
`BUSY`, or `UNKNOWN_ERROR`.

### `printText(text: string, options?: TextOptions): Promise<boolean>`

```ts
type TextOptions = {
  bold?: boolean;
  italic?: boolean;
  underscore?: boolean;
  fontSize?: number;
  lineSpacing?: number;
  alignment?: Alignment; // LEFT | CENTER | RIGHT
};
```

### `printBarcode(data: string, options?: BarcodeOptions): Promise<boolean>`

```ts
type BarcodeOptions = {
  type?: BarcodeType; // CODE_128 | EAN_13 | QR_CODE | ... (see src/index.tsx)
  size?: BarcodeSize; // SMALL | HALF_PAPER | FULL_PAPER
  whiteSpace?: number;
};
```

### `scrollPaper(lines: number): Promise<boolean>`

Feeds the paper forward by `lines` lines.

## What this deliberately doesn't cover

The underlying `Printer` class also exposes `printImage`, `printHtml`,
`printTable`, and `printXml` (receipt-style layouts, image printing via
`PrinterUtils`, which itself pulls in an OpenCV native dependency for
monochrome conversion). None of that is wired up here because the only
current consumer (bruce-in-a-box's reduced-price label print) only needs
text and barcodes. Add it the same way as the existing methods —
`GertecPrinterModule.kt` calls straight into
`br.com.gertec.easylayer.printer.Printer` — if a future use case needs it.

There's also no label-locate/label-output concept here, unlike
`react-native-sunmi-printer`'s `labelLocate()`/`labelOutput()`. The TSG820
prints to a continuous roll, not pre-cut label stock, so that step doesn't
exist in Gertec's SDK — each `printText`/`printBarcode` call executes (or
queues) directly.

## How the native module works

Gertec's SDK is callback-based: `Printer.printText()` /
`.printBarcode()` / `.scrollPaper()` each return an `Int` request id
synchronously, and the result arrives later via
`Printer.Listener.onPrinterSuccessful(requestId)` /
`.onPrinterError(PrinterError)`. `GertecPrinterModule` keeps a
`ConcurrentHashMap<Int, Promise>` keyed by that request id to bridge the
callback into the Promise each JS call returns.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT (for this bridge code — the vendored `EasyLayer-TSG820-*.aar` remains
Gertec's proprietary SDK, see above)

---

Scaffolded with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
