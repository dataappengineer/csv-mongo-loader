package com.example;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Tipo DT (DateTime = data + ora). Output: java.util.Date in UTC (BSON ISODate).
 *
 * Formati e timezone provengono da CsvTypeConfig (configurabili a startup),
 * parsing STRICT. Flag: solo PK.
 */
public class DateTimeTransformer implements FieldTransformer {

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
        LocalDateTime dt = parse(rawValue);
        return Date.from(dt.atZone(CsvTypeConfig.getTimezone()).toInstant());
    }

    private LocalDateTime parse(String rawValue) throws TransformException {
        // trim + collassa spazi multipli: accetta anche "9/7/2026  12:38:00" (doppio spazio)
        String t = rawValue == null ? "" : rawValue.trim().replaceAll("\\s+", " ");
        for (DateTimeFormatter f : CsvTypeConfig.getDateTimeFormatters()) {
            try {
                return LocalDateTime.parse(t, f);
            } catch (DateTimeException ignored) {
                // prova il formato successivo
            }
        }
        throw new TransformException("datetime non valido (valore: " + rawValue + ")");
    }
}
