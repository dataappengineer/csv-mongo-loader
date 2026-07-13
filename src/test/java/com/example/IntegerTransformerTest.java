package com.example;

import org.junit.jupiter.api.Test;

import static com.example.TransformerTestSupport.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegerTransformerTest {

    private final IntegerTransformer t = new IntegerTransformer();
    private final ColumnSchema schema = plain("I");

    @Test
    void validate_valid() {
        assertTrue(t.validate("123"));
        assertTrue(t.validate("-456"));
        assertTrue(t.validate("  789  "));
        assertTrue(t.validate("0"));
    }

    @Test
    void validate_invalid() {
        assertFalse(t.validate(""));
        assertFalse(t.validate(null));
        assertFalse(t.validate("abc"));
        assertFalse(t.validate("12.50"));   // niente decimali
        assertFalse(t.validate("12,50"));   // niente decimali
        assertFalse(t.validate("2.500"));   // niente separatore migliaia
    }

    @Test
    void transform_toLong() throws TransformException {
        assertEquals(123L, t.transform("123", schema));
        assertEquals(-456L, t.transform("-456", schema));
    }

    @Test
    void transform_largeValue_fitsLong() throws TransformException {
        assertEquals(9000000000L, t.transform("9000000000", schema)); // oltre Integer.MAX_VALUE
    }

    @Test
    void transform_invalid_throws() {
        assertThrows(TransformException.class, () -> t.transform("abc", schema));
        assertThrows(TransformException.class, () -> t.transform("12.50", schema));
    }
}
