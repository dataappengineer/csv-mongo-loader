package com.example;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Tipo D (Data). Output: java.util.Date a mezzanotte UTC (BSON ISODate).
 *
 * Formati e timezone provengono da CsvTypeConfig (configurabili a startup). Il
 * parsing e' STRICT: le date invalide (giorno/mese fuori range, 29/02 in anno non
 * bisestile) vengono respinte. Flag: solo PK.
 */
public class DateTransformer implements FieldTransformer {

    @Override
    public boolean validate(String rawValue) {
        try {
            parse(rawValue);
            return true;
        } catch (TransformException e) {
            return false;
        }
    }

    @Override
    public Object transform(String rawValue, ColumnSchema schema) throws TransformException {
        LocalDate d = parse(rawValue);
        return Date.from(d.atStartOfDay(CsvTypeConfig.getTimezone()).toInstant());
    }

    private LocalDate parse(String rawValue) throws TransformException {
        String t = rawValue == null ? "" : rawValue.trim();
        for (DateTimeFormatter f : CsvTypeConfig.getDateFormatters()) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeException ignored) {
                // prova il formato successivo
            }
        }
        throw new TransformException("data non valida (valore: " + rawValue + ")");
    }
}
