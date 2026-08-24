import { useState } from 'react';
import { Text, View, StyleSheet, Button, ScrollView } from 'react-native';
import GertecPrinter, {
  Alignment,
  BarcodeType,
} from '@gander-ltd/react-native-gertec-printer';

export default function App() {
  const [log, setLog] = useState<string[]>([]);

  const append = (line: string) => setLog((prev) => [...prev, line]);

  const runCheck = async () => {
    const present = await GertecPrinter.hasPrinter();
    append(`hasPrinter: ${present}`);
    const status = await GertecPrinter.getStatus();
    append(`status: ${status.code} ${status.message}`);
  };

  const printSample = async () => {
    try {
      await GertecPrinter.printText('TOO GOOD TO WASTE', {
        bold: true,
        fontSize: 30,
        alignment: Alignment.CENTER,
      });
      await GertecPrinter.printBarcode('900015950000221553595', {
        type: BarcodeType.CODE_128,
      });
      await GertecPrinter.printText('WAS £4.95  NOW £2.50', {
        fontSize: 24,
        alignment: Alignment.CENTER,
      });
      await GertecPrinter.scrollPaper(3);
      append('print: ok');
    } catch (e) {
      append(`print: error ${String(e)}`);
    }
  };

  return (
    <View style={styles.container}>
      <Button title="Check printer" onPress={runCheck} />
      <Button title="Print sample label" onPress={printSample} />
      <ScrollView style={styles.log}>
        {log.map((line, index) => (
          <Text key={index}>{line}</Text>
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
});
