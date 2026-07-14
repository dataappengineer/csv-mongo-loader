package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadReportTest {

    @Test
    void recordsLoaded_sumsInsertedAndUpdated() {
        LoadReport r = new LoadReport();
        r.recordsInserted = 60;
        r.recordsUpdated = 40;
        assertEquals(100, r.recordsLoaded());
    }

    @Test
    void addError_capsAtMaxPlusMarker() {
        LoadReport r = new LoadReport();
        for (int i = 0; i < 150; i++) {
            r.addError("errore " + i);
        }
        // MAX_ERRORS reali + 1 marcatore di troncamento
        assertEquals(LoadReport.MAX_ERRORS + 1, r.errors.size());
        assertTrue(r.errors.get(r.errors.size() - 1).contains("ulteriori errori omessi"));
    }
}
