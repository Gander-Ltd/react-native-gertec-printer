import NativeGertecPrinter from './NativeGertecPrinter';
import type { PrinterStatus } from './NativeGertecPrinter';

export type { PrinterStatus };

export enum Alignment {
  LEFT = 'LEFT',
  CENTER = 'CENTER',
  RIGHT = 'RIGHT',
}

// Mirrors br.com.gertec.easylayer.printer.BarcodeType
export enum BarcodeType {
  AZTEC = 'AZTEC',
  CODABAR = 'CODABAR',
  CODE_39 = 'CODE_39',
  CODE_93 = 'CODE_93',
  CODE_128 = 'CODE_128',
  DATA_MATRIX = 'DATA_MATRIX',
  EAN_8 = 'EAN_8',
  EAN_13 = 'EAN_13',
  ITF = 'ITF',
  MAXICODE = 'MAXICODE',
  PDF_417 = 'PDF_417',
  QR_CODE = 'QR_CODE',
  RSS_14 = 'RSS_14',
  RSS_EXPANDED = 'RSS_EXPANDED',
  UPC_A = 'UPC_A',
  UPC_E = 'UPC_E',
  UPC_EAN_EXTENSION = 'UPC_EAN_EXTENSION',
}

// Mirrors br.com.gertec.easylayer.printer.BarcodeFormat.Size
export enum BarcodeSize {
  SMALL = 'SMALL',
  HALF_PAPER = 'HALF_PAPER',
  FULL_PAPER = 'FULL_PAPER',
}

export type TextOptions = {
  bold?: boolean;
  italic?: boolean;
  underscore?: boolean;
  fontSize?: number;
  lineSpacing?: number;
  alignment?: Alignment;
};

export type BarcodeOptions = {
  type?: BarcodeType;
  size?: BarcodeSize;
  whiteSpace?: number;
};

/**
 * Resolves true if the Gertec printer service responds, false otherwise.
 */
export function hasPrinter(): Promise<boolean> {
  return NativeGertecPrinter.hasPrinter();
}

/**
 * Current printer status (paper out, overheating, busy, etc).
 */
export function getStatus(): Promise<PrinterStatus> {
  return NativeGertecPrinter.getStatus();
}

/**
 * Prints a line of text with the given format.
 */
export function printText(
  text: string,
  options: TextOptions = {}
): Promise<boolean> {
  return NativeGertecPrinter.printText(text, options);
}

/**
 * Prints a barcode/QR code with the given format.
 */
export function printBarcode(
  data: string,
  options: BarcodeOptions = {}
): Promise<boolean> {
  return NativeGertecPrinter.printBarcode(data, options);
}

/**
 * Feeds the paper forward by the given number of lines.
 */
export function scrollPaper(lines: number): Promise<boolean> {
  return NativeGertecPrinter.scrollPaper(lines);
}

const GertecPrinter = {
  hasPrinter,
  getStatus,
  printText,
  printBarcode,
  scrollPaper,
};

export default GertecPrinter;
