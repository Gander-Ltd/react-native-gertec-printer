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

export default function App() {
  const [log, setLog] = useState<string[]>([]);
  const [scrollLines, setScrollLines] = useState(20);
  const [settleDelayMs, setSettleDelayMs] = useState(700);

  const append = (line: string) =>
    setLog((prev) => [...prev, `${new Date().toLocaleTimeString()}  ${line}`]);

  const runCheck = async () => {
    try {
      const present = await GertecPrinter.hasPrinter();
      append(`hasPrinter: ${present}`);
      const status = await GertecPrinter.getStatus();
      append(`status: ${status.code} ${status.message}`);
    } catch (e) {
      append(`check: error ${String(e)}`);
    }
  };

  // Mirrors PrinterService.printReducedLabelGertec exactly, including the 1-line gaps
  // between text/barcode/price and the settle delay after scrollPaper -- but with
  // scrollLines/settleDelayMs as live variables instead of hardcoded, so both can be
  // tuned from the buttons below without a rebuild.
  const printOneLabel = async (label: string, price: string) => {
    await GertecPrinter.printText(label, {
      fontSize: GERTEC_FONT_SIZE,
      alignment: Alignment.CENTER,
    });
    await GertecPrinter.scrollPaper(1);

    await GertecPrinter.printBarcode('900015950000221553595', {
      type: BarcodeType.CODE_128,
      size: BarcodeSize.FULL_PAPER,
    });
    await GertecPrinter.scrollPaper(1);

    await GertecPrinter.printText(price, {
      fontSize: GERTEC_FONT_SIZE,
      alignment: Alignment.CENTER,
    });

    await GertecPrinter.scrollPaper(scrollLines);
    await new Promise((resolve) => setTimeout(resolve, settleDelayMs));
  };

  const printSample = async () => {
    try {
      append(`printing 1 label (scrollPaper=${scrollLines}, delay=${settleDelayMs}ms)...`);
      await printOneLabel('PROXIMO AO VENCIMENTO', 'WAS £4.95  NOW £2.50');
      append('print: ok');
    } catch (e) {
      append(`print: error ${String(e)}`);
    }
  };

  // The bug this whole screen exists to chase only shows up at the BOUNDARY between
  // two copies -- a single label alone can look perfect and still overlap the next one.
  const printTwoLabels = async () => {
    try {
      append(`printing 2 labels back-to-back (scrollPaper=${scrollLines}, delay=${settleDelayMs}ms)...`);
      await printOneLabel('PROXIMO AO VENCIMENTO', 'WAS £4.95  NOW £2.50');
      await printOneLabel('PROXIMO AO VENCIMENTO', 'WAS £9.00  NOW £5.00');
      append('print: ok -- check the gap between the two labels');
    } catch (e) {
      append(`print: error ${String(e)}`);
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.row}>
        <Text style={styles.label}>scrollPaper: {scrollLines}</Text>
        <Button title="-5" onPress={() => setScrollLines((n) => Math.max(0, n - SCROLL_STEP))} />
        <Button title="-1" onPress={() => setScrollLines((n) => Math.max(0, n - 1))} />
        <Button title="+1" onPress={() => setScrollLines((n) => n + 1)} />
        <Button title="+5" onPress={() => setScrollLines((n) => n + SCROLL_STEP)} />
      </View>
      <View style={styles.row}>
        <Text style={styles.label}>delay: {settleDelayMs}ms</Text>
        <Button
          title="-100"
          onPress={() => setSettleDelayMs((n) => Math.max(0, n - DELAY_STEP_MS))}
        />
        <Button title="+100" onPress={() => setSettleDelayMs((n) => n + DELAY_STEP_MS)} />
      </View>

      <Button title="Check printer" onPress={runCheck} />
      <Button title="Print 1 label" onPress={printSample} />
      <Button title="Print 2 labels (test gap)" onPress={printTwoLabels} />
      <Button title="Clear log" onPress={() => setLog([])} />
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
    paddingTop: 60,
    paddingHorizontal: 16,
    gap: 12,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  label: {
    fontFamily: 'monospace',
    fontSize: 14,
    minWidth: 140,
  },
  log: {
    marginTop: 12,
  },
  logLine: {
    fontFamily: 'monospace',
    fontSize: 12,
  },
});
