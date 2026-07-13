package com.example;

import org.junit.jupiter.api.Test;

import static com.example.TransformerTestSupport.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BooleanTransformerTest {

    private final BooleanTransformer t = new BooleanTransformer();
    private final ColumnSchema schema = plain("B");

    @Test
    void validate_knownValues() {
        assertTrue(t.validate("SI"));
        assertTrue(t.validate("si"));
        assertTrue(t.validate("TRUE"));
        assertTrue(t.validate("1"));
        assertTrue(t.validate("NO"));
        assertTrue(t.validate("0"));
    }

    @Test
    void validate_unknown() {
        assertFalse(t.validate("maybe"));
        assertFalse(t.validate(""));
        assertFalse(t.validate(null));
    }

    @Test
    void transform_trueValues() throws TransformException {
        assertEquals(Boolean.TRUE, t.transform("SI", schema));
        assertEquals(Boolean.TRUE, t.transform("si", schema));
        assertEquals(Boolean.TRUE, t.transform("1", schema));
        assertEquals(Boolean.TRUE, t.transform("YES", schema));
    }

    @Test
    void transform_falseValues() throws TransformException {
        assertEquals(Boolean.FALSE, t.transform("NO", schema));
        assertEquals(Boolean.FALSE, t.transform("false", schema));
        assertEquals(Boolean.FALSE, t.transform("0", schema));
    }

    @Test
    void transform_unknown_throws() {
        assertThrows(TransformException.class, () -> t.transform("maybe", schema));
    }
}
