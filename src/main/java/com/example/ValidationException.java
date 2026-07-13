package com.example;

/**
 * Eccezione di validazione con contesto (riga fisica, colonna, valore grezzo)
 * per produrre messaggi tracciabili nel report.
 *
 * Usata sia dal parsing dell'header (riga 1/2) sia, in fase di caricamento,
 * per gli errori riga-per-riga.
 */
public class ValidationException extends Exception {

    private final int csvRow;
    private final String fieldName;
    private final String rawValue;

    /** Messaggio libero, senza contesto di riga/colonna. */
    public ValidationException(String message) {
        super(message);
        this.csvRow = -1;
        this.fieldName = null;
        this.rawValue = null;
    }

    /** Messaggio con contesto: "Riga {row}, colonna {field}: {reason} (valore: {raw})". */
    public ValidationException(int csvRow, String fieldName, String rawValue, String reason) {
        super(String.format("Riga %d, colonna %s: %s (valore: %s)",
                csvRow, fieldName, reason, rawValue));
        this.csvRow = csvRow;
        this.fieldName = fieldName;
        this.rawValue = rawValue;
    }

    public int getCsvRow() { return csvRow; }
    public String getFieldName() { return fieldName; }
    public String getRawValue() { return rawValue; }
}
