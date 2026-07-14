package com.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.Set;

import static com.example.TransformerTestSupport.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica che l'override della configurazione (come farebbe il binder dalle
 * properties) sia effettivamente usato dai transformer. Reset dei default dopo
 * ogni test per non contaminare gli altri (CsvTypeConfig è statica globale).
 */
class CsvTypeConfigTest {

    @AfterEach
    void reset() {
        CsvTypeConfig.resetDefaults();
    }

    @Test
    void overrideBooleanValues_usedByTransformer() throws Exception {
        CsvTypeConfig.configure(null, null, null, Set.of("OUI"), Set.of("NON"));
        BooleanTransformer t = new BooleanTransformer();

        assertEquals(Boolean.TRUE, t.transform("OUI", plain("B")));
        assertEquals(Boolean.FALSE, t.transform("NON", plain("B")));
        assertFalse(t.validate("SI")); // il default non è più attivo
    }

    @Test
    void overrideDateFormats_usedByTransformer() throws Exception {
        CsvTypeConfig.configure(null, new String[]{"MM-dd-yyyy"}, null, null, null);
        DateTransformer t = new DateTransformer();

        assertNotNull(t.transform("12-25-2025", plain("D"))); // nuovo formato accettato
        assertFalse(t.validate("25/12/2025"));                 // formato default non più attivo
    }

    @Test
    void overrideTimezone_usedByTransformer() throws Exception {
        CsvTypeConfig.configure(ZoneOffset.ofHours(2), null, null, null, null);
        assertEquals(ZoneOffset.ofHours(2), CsvTypeConfig.getTimezone());
    }

    @Test
    void resetDefaults_restoresOriginal() {
        CsvTypeConfig.configure(null, null, null, Set.of("X"), null);
        CsvTypeConfig.resetDefaults();
        assertTrue(new BooleanTransformer().validate("SI"));
    }
}
