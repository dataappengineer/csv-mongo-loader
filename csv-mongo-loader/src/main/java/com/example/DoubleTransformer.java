package com.example;

import java.util.regex.Pattern;

/**
 * Tipo DD (Double). Output: Double (BSON double).
 *
 * Default: trim, validazione, normalizzazione decimale (virgola -> punto).
 * Accetta un solo separatore decimale: i formati con separatore delle migliaia
 * (es. "2.500,00") non sono supportati e vengono respinti dal pattern.
 * Flag: solo PK (metadato, non modifica il valore).
 */
public class DoubleTransformer implements FieldTransformer {

    private static final Pattern DOUBLE = Pattern.compile("^-?\\d+([.,]\\d+)?$");

    @Override
    public boolean validate(String rawValue) {
        return rawValue != null && DOUBLE.matcher(rawValue.trim()).matches();
    }

    @Override
    public Object transform(String rawValue, ColumnSchema schema) throws TransformException {
        String t = rawValue == null ? "" : rawValue.trim();
        if (!DOUBLE.matcher(t).matches()) {
            throw new TransformException("double non valido (valore: " + rawValue + ")");
        }
        try {
            return Double.parseDouble(t.replace(",", "."));
        } catch (NumberFormatException e) {
            throw new TransformException("double non valido (valore: " + rawValue + ")", e);
        }
    }
}
