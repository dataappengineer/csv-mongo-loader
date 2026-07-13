package com.example;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Date;

import static com.example.TransformerTestSupport.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTransformerTest {

    private final DateTransformer t = new DateTransformer();
    private final ColumnSchema schema = plain("D");

    private static Date utcDate(int y, int m, int d) {
        return Date.from(LocalDate.of(y, m, d).atStartOfDay(CsvTypeConfig.getTimezone()).toInstant());
    }

    @Test
    void validate_formats() {
        assertTrue(t.validate("01/01/2026"));
        assertTrue(t.validate("2026-01-01"));
        assertTrue(t.validate("01-01-2026"));
    }

    @Test
    void validate_invalid() {
        assertFalse(t.validate(""));
        assertFalse(t.validate("99/99/2026"));
        assertFalse(t.validate("29/02/2023")); // 2023 non bisestile
        assertFalse(t.validate("pippo"));
    }

    @Test
    void validate_leapYearOk() {
        assertTrue(t.validate("29/02/2024")); // 2024 bisestile
    }

    @Test
    void transform_ddMMyyyy() throws TransformException {
        assertEquals(utcDate(2025, 12, 25), t.transform("25/12/2025", schema));
    }

    @Test
    void transform_isoFormat() throws TransformException {
        assertEquals(utcDate(2025, 12, 25), t.transform("2025-12-25", schema));
    }

    @Test
    void transform_singleDigitDayMonth() throws TransformException {
        // formato con cifra singola (come nell'esempio Excel: 9/7/2026)
        assertEquals(utcDate(2026, 7, 9), t.transform("9/7/2026", schema));
    }

    @Test
    void transform_ddMMyyyy_default() throws TransformException {
        // formato primario di default: dd/MM/yyyy
        assertEquals(utcDate(2026, 7, 9), t.transform("09/07/2026", schema));
    }

    @Test
    void transform_twoDigitYear_pivot2000() throws TransformException {
        // anno a 2 cifre (dd/MM/yy): pivot 2000-2099 -> 24 = 2024
        assertEquals(utcDate(2024, 6, 5), t.transform("05/06/24", schema));
    }

    @Test
    void transform_invalid_throws() {
        assertThrows(TransformException.class, () -> t.transform("99/99/2026", schema));
        assertThrows(TransformException.class, () -> t.transform("29/02/2023", schema));
    }
}
