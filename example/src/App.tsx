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

export default function App() {
  const [log, setLog] = useState<string[]>([]);

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

  const printSample = async () => {
    try {
      append('printText (line 1): calling...');
      await GertecPrinter.printText('PROXIMO AO VENCIMENTO', {
        fontSize: GERTEC_FONT_SIZE,
        alignment: Alignment.CENTER,
      });
      append('printText (line 1): done');

      append('printBarcode: calling...');
      await GertecPrinter.printBarcode('900015950000221553595', {
        type: BarcodeType.CODE_128,
        size: BarcodeSize.HALF_PAPER,
      });
      append('printBarcode: done');

      append('printText (line 2): calling...');
      await GertecPrinter.printText('WAS £4.95  NOW £2.50', {
        fontSize: GERTEC_FONT_SIZE,
        alignment: Alignment.CENTER,
      });
      append('printText (line 2): done');

      await GertecPrinter.scrollPaper(1);
      append('print: ok');
    } catch (e) {
      append(`print: error ${String(e)}`);
    }
  };

  return (
    <View style={styles.container}>
      <Button title="Check printer" onPress={runCheck} />
      <Button title="Print sample label" onPress={printSample} />
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
  log: {
    marginTop: 12,
  },
  logLine: {
    fontFamily: 'monospace',
    fontSize: 12,
  },
});
