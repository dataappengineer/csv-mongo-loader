package com.example;

/**
 * Tipo B (Boolean). Output: Boolean.
 *
 * Default: trim + confronto case-insensitive con i valori noti (CsvTypeConfig).
 * Nessun flag supportato (PK incluso: un booleano non è una buona chiave).
 */
public class BooleanTransformer implements FieldTransformer {

    @Override
    public boolean validate(String rawValue) {
        if (rawValue == null) {
            return false;
        }
        String t = rawValue.trim().toUpperCase(CsvTypeConfig.LOCALE);
        return CsvTypeConfig.getBooleanTrue().contains(t) || CsvTypeConfig.getBooleanFalse().contains(t);
    }

    @Override
    public Object transform(String rawValue, ColumnSchema schema) throws TransformException {
        String t = rawValue == null ? "" : rawValue.trim().toUpperCase(CsvTypeConfig.LOCALE);
        if (CsvTypeConfig.getBooleanTrue().contains(t)) {
            return Boolean.TRUE;
        }
        if (CsvTypeConfig.getBooleanFalse().contains(t)) {
            return Boolean.FALSE;
        }
        throw new TransformException("valore booleano non riconosciuto (valore: " + rawValue + ")");
    }
}
