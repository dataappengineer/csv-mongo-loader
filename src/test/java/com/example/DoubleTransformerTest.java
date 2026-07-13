package com.example;

import org.junit.jupiter.api.Test;

import static com.example.TransformerTestSupport.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleTransformerTest {

    private final DoubleTransformer t = new DoubleTransformer();
    private final ColumnSchema schema = plain("DD");

    @Test
    void validate_valid() {
        assertTrue(t.validate("123"));
        assertTrue(t.validate("-456"));
        assertTrue(t.validate("12.50"));
        assertTrue(t.validate("12,50"));
        assertTrue(t.validate("  789  "));
    }

    @Test
    void validate_invalid() {
        assertFalse(t.validate(""));
        assertFalse(t.validate(null));
        assertFalse(t.validate("abc"));
        assertFalse(t.validate("12.34.56"));
        assertFalse(t.validate("2.500,00")); // separatore migliaia non supportato
    }

    @Test
    void transform_integer() throws TransformException {
        assertEquals(123.0, t.transform("123", schema));
    }

    @Test
    void transform_commaDecimalNormalized() throws TransformException {
        assertEquals(12.50, t.transform("12,50", schema));
    }

    @Test
    void transform_negativeDecimal() throws TransformException {
        assertEquals(-456.78, t.transform("-456.78", schema));
    }

    @Test
    void transform_invalid_throws() {
        assertThrows(TransformException.class, () -> t.transform("abc", schema));
        assertThrows(TransformException.class, () -> t.transform("2.500,00", schema));
    }
}
