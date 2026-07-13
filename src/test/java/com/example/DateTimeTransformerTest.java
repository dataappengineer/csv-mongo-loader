package com.example;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Date;

import static com.example.TransformerTestSupport.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTimeTransformerTest {

    private final DateTimeTransformer t = new DateTimeTransformer();
    private final ColumnSchema schema = plain("DT");

    private static Date utc(int y, int mo, int d, int h, int mi, int s) {
        return Date.from(LocalDateTime.of(y, mo, d, h, mi, s).atZone(CsvTypeConfig.getTimezone()).toInstant());
    }

    @Test
    void validate_formats() {
        assertTrue(t.validate("01/01/2026 14:30:45"));
        assertTrue(t.validate("2026-01-01 14:30:45"));
        assertTrue(t.validate("2026-01-01T14:30:45"));
        assertTrue(t.validate("2026-01-01T14:30:45Z"));
    }

    @Test
    void validate_invalid() {
        assertFalse(t.validate(""));
        assertFalse(t.validate("2026-01-01"));          // manca l'ora
        assertFalse(t.validate("01/01/2026 25:00:00")); // ora invalida
    }

    @Test
    void transform_slashFormat() throws TransformException {
        assertEquals(utc(2026, 1, 1, 14, 30, 45), t.transform("01/01/2026 14:30:45", schema));
    }

    @Test
    void transform_isoFormat() throws TransformException {
        assertEquals(utc(2026, 1, 1, 14, 30, 45), t.transform("2026-01-01T14:30:45", schema));
    }

    @Test
    void transform_singleDigitAndDoubleSpace() throws TransformException {
        // come nell'esempio Excel: cifra singola + doppio spazio tra data e ora
        assertEquals(utc(2026, 7, 9, 12, 38, 0), t.transform("9/7/2026  12:38:00", schema));
    }

    @Test
    void transform_invalid_throws() {
        assertThrows(TransformException.class, () -> t.transform("01/01/2026 25:00:00", schema));
    }
}
