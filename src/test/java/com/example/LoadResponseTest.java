package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultConstructor_errorsNotNull() {
        LoadResponse r = new LoadResponse();
        assertNotNull(r.getErrors());
        assertEquals(0, r.getRecords());
        assertEquals(0, r.getRecordsRead());
    }

    @Test
    void threeArgConstructor_backwardCompatible() {
        LoadResponse r = new LoadResponse("SUCCESS", 100, null);
        assertEquals("SUCCESS", r.getStatus());
        assertEquals(100, r.getRecords());
        assertNull(r.getJobId());
    }

    @Test
    void fourArgConstructor_async() {
        LoadResponse r = new LoadResponse("id57", "ACCEPTED", 0, "avviata");
        assertEquals("id57", r.getJobId());
        assertEquals("ACCEPTED", r.getStatus());
    }

    @Test
    void setters_populateReport() {
        LoadResponse r = new LoadResponse("SUCCESS", 0, null);
        r.setRecordsRead(100);
        r.setRecordsInserted(93);
        r.setRecordsUpdated(0);
        r.setRecordsSkipped(5);
        r.setRecordsDuplicati(2);
        r.setRecords(93);
        assertEquals(100, r.getRecordsRead());
        assertEquals(93, r.getRecordsInserted());
        assertEquals(2, r.getRecordsDuplicati());
    }

    @Test
    void jsonSerialization_containsNewFields() throws Exception {
        LoadResponse r = new LoadResponse("SUCCESS", 93, null);
        r.setRecordsRead(100);
        r.setRecordsSkipped(5);
        r.setRecordsDuplicati(2);
        String json = mapper.writeValueAsString(r);
        assertTrue(json.contains("\"records\":93"));
        assertTrue(json.contains("\"recordsRead\":100"));
        assertTrue(json.contains("\"recordsInserted\":0"));
        assertTrue(json.contains("\"recordsUpdated\":0"));
        assertTrue(json.contains("\"recordsSkipped\":5"));
        assertTrue(json.contains("\"recordsDuplicati\":2"));
        assertTrue(json.contains("\"errors\":[]"));
    }
}
