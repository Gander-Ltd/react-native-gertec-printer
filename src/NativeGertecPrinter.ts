import { TurboModuleRegistry, type TurboModule } from 'react-native';

export type TextOptions = {
  bold?: boolean;
  italic?: boolean;
  underscore?: boolean;
  fontSize?: number;
  lineSpacing?: number;
  // One of 'LEFT' | 'CENTER' | 'RIGHT'
  alignment?: string;
};

export type BarcodeOptions = {
  // BarcodeType name from br.com.gertec.easylayer.printer.BarcodeType,
  // e.g. 'CODE_128' | 'EAN_13' | 'QR_CODE' | 'CODABAR' | 'CODE_39' | 'CODE_93' |
  // 'EAN_8' | 'ITF' | 'UPC_A' | 'UPC_E' | 'UPC_EAN_EXTENSION' | 'PDF_417' |
  // 'DATA_MATRIX' | 'AZTEC' | 'MAXICODE' | 'RSS_14' | 'RSS_EXPANDED'
  type?: string;
  // One of 'SMALL' | 'HALF_PAPER' | 'FULL_PAPER'
  size?: string;
  whiteSpace?: number;
};

export type PrinterStatus = {
  code: number;
  message: string;
};

export interface Spec extends TurboModule {
  /**
   * Resolves true if the Gertec printer service responds, false otherwise.
   * Never rejects.
   */
  hasPrinter(): Promise<boolean>;

  /**
   * Resolves with the printer's current status (OK, OUT_OF_PAPER, OVERHEAT,
   * BUSY, UNKNOWN_ERROR). Rejects if the underlying SDK call throws.
   */
  getStatus(): Promise<PrinterStatus>;

  /**
   * Prints a line of text with the given format. Resolves true once the
   * Gertec SDK reports the print request completed successfully.
   */
  printText(text: string, options: TextOptions): Promise<boolean>;

  /**
   * Prints a barcode/QR code with the given format.
   */
  printBarcode(data: string, options: BarcodeOptions): Promise<boolean>;

  /**
   * Feeds the paper forward by the given number of lines.
   */
  scrollPaper(lines: number): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('GertecPrinter');
