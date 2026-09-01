import { useState } from 'react';
import { Text, View, StyleSheet, Button, ScrollView } from 'react-native';
import GertecPrinter, {
  Alignment,
  BarcodeType,
  BarcodeSize,
} from '@gander-ltd/react-native-gertec-printer';

// Mirrors bruce-in-a-box's PrinterService.printReducedLabelGertec (fontSize 20,
// sequential awaits, HALF_PAPER barcode size) so a pass/fail here is representative of
// the real app -- this exists so a barcode/label change can be tested by building and
// sideloading just this small app (gradlew assembleDebug), without an EAS cloud build
// and a full bruce-in-a-box install for every iteration.
const GERTEC_FONT_SIZE = 20;

// scrollPaper's own line height is hardcoded to the minimum internally (decompiled --
// see GertecPrinterModule.kt), so it takes far more "lines" than a normal text line to
// cover real physical distance, and the right number can only be found by testing on
// the actual label stock. These adjustable controls let that be tried live, in one app
// session, instead of an OTA push + relaunch cycle per guess.
const SCROLL_STEP = 5;
const DELAY_STEP_MS = 100;

function NumberControl(props: {
  label: string;
  value: number;
  onChange: (updater: (n: number) => number) => void;
  step: number;
  bigStep?: number;
  disabled: boolean;
}) {
  const { label, value, onChange, step, bigStep, disabled } = props;
  return (
    <View style={styles.row}>
      <Text style={styles.label}>{label}: {value}</Text>
      {bigStep != null && (
        <Button
          title={`-${bigStep}`}
          onPress={() => onChange((n) => Math.max(0, n - bigStep))}
          disabled={disabled}
        />
      )}
      <Button title={`-${step}`} onPress={() => onChange((n) => Math.max(0, n - step))} disabled={disabled} />
      <Button title={`+${step}`} onPress={() => onChange((n) => n + step)} disabled={disabled} />
      {bigStep != null && (
        <Button title={`+${bigStep}`} onPress={() => onChange((n) => n + bigStep)} disabled={disabled} />
      )}
    </View>
  );
}

export default function App() {
  const [log, setLog] = useState<string[]>([]);
  const [scrollLines, setScrollLines] = useState(20);
  // Text/barcode prints and paper feeds are physically different operations and likely
  // need different settle times -- one shared delay forces a compromise (too long for
  // fast ops, too short for slow ones). Independently tunable so that can be tested
  // directly instead of guessed.
  const [textDelayMs, setTextDelayMs] = useState(300);
  const [barcodeDelayMs, setBarcodeDelayMs] = useState(300);
  const [smallScrollDelayMs, setSmallScrollDelayMs] = useState(300);
  const [bigScrollDelayMs, setBigScrollDelayMs] = useState(700);
  // Gertec's SDK is one stateful printer instance handling one request at a time --
  // tapping a print button again before the previous call finishes fires a second,
  // independent async call whose printText/printBarcode/scrollPaper requests then race
  // the first call's, producing exactly the kind of jumbled/missing-content output this
  // screen exists to prevent (same class of bug LabelPrinterProvider's sequential loop
  // guards against in bruce-in-a-box). Buttons below disable themselves while true.
  const [isPrinting, setIsPrinting] = useState(false);

  const append = (line: string) =>
    setLog((prev) => [...prev, `${new Date().toLocaleTimeString()}  ${line}`]);

  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

  const runCheck = async () => {
    if (isPrinting) return;
    setIsPrinting(true);
    try {
      const present = await GertecPrinter.hasPrinter();
      append(`hasPrinter: ${present}`);
      const status = await GertecPrinter.getStatus();
      append(`status: ${status.code} ${status.message}`);
    } catch (e) {
      append(`check: error ${String(e)}`);
    } finally {
      setIsPrinting(false);
    }
  };

  // Mirrors PrinterService.printReducedLabelGertec, but settles after every SDK call
  // with a delay specific to that operation's kind, not one shared value.
  const printOneLabel = async (label: string, price: string) => {
    await GertecPrinter.printText(label, {
      fontSize: GERTEC_FONT_SIZE,
      alignment: Alignment.CENTER,
    });
    await sleep(textDelayMs);

    await GertecPrinter.scrollPaper(1);
    await sleep(smallScrollDelayMs);

    await GertecPrinter.printBarcode('900015950000221553595', {
      type: BarcodeType.CODE_128,
      size: BarcodeSize.FULL_PAPER,
    });
    await sleep(barcodeDelayMs);

    await GertecPrinter.scrollPaper(1);
    await sleep(smallScrollDelayMs);

    await GertecPrinter.printText(price, {
      fontSize: GERTEC_FONT_SIZE,
      alignment: Alignment.CENTER,
    });
    await sleep(textDelayMs);

    await GertecPrinter.scrollPaper(scrollLines);
    await sleep(bigScrollDelayMs);
  };

  const summary = () =>
    `scrollPaper=${scrollLines} text=${textDelayMs}ms barcode=${barcodeDelayMs}ms ` +
    `smallScroll=${smallScrollDelayMs}ms bigScroll=${bigScrollDelayMs}ms`;

  const printSample = async () => {
    if (isPrinting) return;
    setIsPrinting(true);
    try {
      append(`printing 1 label (${summary()})...`);
      await printOneLabel('PROXIMO AO VENCIMENTO', 'WAS £4.95  NOW £2.50');
      append('print: ok');
    } catch (e) {
      append(`print: error ${String(e)}`);
    } finally {
      setIsPrinting(false);
    }
  };

  // The bug this whole screen exists to chase only shows up at the BOUNDARY between
  // two copies -- a single label alone can look perfect and still overlap the next one.
  const printTwoLabels = async () => {
    if (isPrinting) return;
    setIsPrinting(true);
    try {
      append(`printing 2 labels back-to-back (${summary()})...`);
      await printOneLabel('PROXIMO AO VENCIMENTO', 'WAS £4.95  NOW £2.50');
      await printOneLabel('PROXIMO AO VENCIMENTO', 'WAS £9.00  NOW £5.00');
      append('print: ok -- check the gap between the two labels');
    } catch (e) {
      append(`print: error ${String(e)}`);
    } finally {
      setIsPrinting(false);
    }
  };

  return (
    <View style={styles.container}>
      <NumberControl
        label="scrollPaper"
        value={scrollLines}
        onChange={setScrollLines}
        step={1}
        bigStep={SCROLL_STEP}
        disabled={isPrinting}
      />
      <NumberControl
        label="text delay"
        value={textDelayMs}
        onChange={setTextDelayMs}
        step={DELAY_STEP_MS}
        disabled={isPrinting}
      />
      <NumberControl
        label="barcode delay"
        value={barcodeDelayMs}
        onChange={setBarcodeDelayMs}
        step={DELAY_STEP_MS}
        disabled={isPrinting}
      />
      <NumberControl
        label="small scroll delay"
        value={smallScrollDelayMs}
        onChange={setSmallScrollDelayMs}
        step={DELAY_STEP_MS}
        disabled={isPrinting}
      />
      <NumberControl
        label="big scroll delay"
        value={bigScrollDelayMs}
        onChange={setBigScrollDelayMs}
        step={DELAY_STEP_MS}
        disabled={isPrinting}
      />

      <Button title="Check printer" onPress={runCheck} disabled={isPrinting} />
      <Button title="Print 1 label" onPress={printSample} disabled={isPrinting} />
      <Button
        title="Print 2 labels (test gap)"
        onPress={printTwoLabels}
        disabled={isPrinting}
      />
      <Button title="Clear log" onPress={() => setLog([])} disabled={isPrinting} />
      <ScrollView style={styles.log}>
        {log.map((line, index) => (
          <Text key={index} style={styles.logLine}>
            {line}
          </Text>
        ))}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingTop: 40,
    paddingHorizontal: 16,
    gap: 8,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  label: {
    fontFamily: 'monospace',
    fontSize: 13,
    minWidth: 140,
  },
  log: {
    marginTop: 8,
  },
  logLine: {
    fontFamily: 'monospace',
    fontSize: 12,
  },
});
