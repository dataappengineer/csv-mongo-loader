package com.example;

import java.util.regex.Pattern;

/**
 * Tipo I (Integer). Output: Long (BSON Int64).
 *
 * Default: trim + validazione. Accetta solo cifre con segno opzionale, senza
 * separatore decimale ne' delle migliaia (es. "12.50" e "2.500" sono respinti).
 * Il tipo Long copre gli identificativi a 64 bit. Flag: solo PK (metadato, non
 * modifica il valore).
 */
public class IntegerTransformer implements FieldTransformer {

    private static final Pattern INTEGER = Pattern.compile("^-?\\d+$");

    @Override
    public boolean validate(String rawValue) {
        return rawValue != null && INTEGER.matcher(rawValue.trim()).matches();
    }

    @Override
    public Object transform(String rawValue, ColumnSchema schema) throws TransformException {
        String t = rawValue == null ? "" : rawValue.trim();
        if (!INTEGER.matcher(t).matches()) {
            throw new TransformException("intero non valido (valore: " + rawValue + ")");
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new TransformException("intero fuori range Long (valore: " + rawValue + ")", e);
        }
    }
}
