package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformerRegistryTest {

    @Test
    void get_returnsCorrectType() {
        assertTrue(TransformerRegistry.get("I") instanceof IntegerTransformer);
        assertTrue(TransformerRegistry.get("S") instanceof StringTransformer);
        assertTrue(TransformerRegistry.get("D") instanceof DateTransformer);
        assertTrue(TransformerRegistry.get("DT") instanceof DateTimeTransformer);
        assertTrue(TransformerRegistry.get("DD") instanceof DoubleTransformer);
        assertTrue(TransformerRegistry.get("B") instanceof BooleanTransformer);
    }

    @Test
    void get_caseInsensitive() {
        assertTrue(TransformerRegistry.get("s") instanceof StringTransformer);
        assertTrue(TransformerRegistry.get("dt") instanceof DateTimeTransformer);
    }

    @Test
    void get_sharedInstance() {
        // stateless -> la stessa istanza viene riusata
        assertSame(TransformerRegistry.get("I"), TransformerRegistry.get("I"));
    }

    @Test
    void get_unknownType_throws() {
        assertThrows(IllegalArgumentException.class, () -> TransformerRegistry.get("X"));
        assertThrows(IllegalArgumentException.class, () -> TransformerRegistry.get(null));
    }

    @Test
    void supports() {
        assertTrue(TransformerRegistry.supports("I"));
        assertTrue(TransformerRegistry.supports("dt"));
        assertTrue(TransformerRegistry.supports("DD"));
        assertFalse(TransformerRegistry.supports("X"));
        assertFalse(TransformerRegistry.supports(null));
    }
}
