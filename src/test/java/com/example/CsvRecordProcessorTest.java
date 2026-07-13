package com.example;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvRecordProcessorTest {

    /** Processor con schema I|PK,S,D -> id,nome,data (pk = id), senza campo tecnico. */
    private CsvRecordProcessor processor() throws ValidationException {
        ColumnSchema[] schema = new CsvHeaderParser(",", "NONE").parseHeader("I;PK,S,D", "id,nome,data");
        return new CsvRecordProcessor(schema, List.of("id"), ",", "", null, null);
    }

    private LoadReport run(CsvRecordProcessor p, String data, List<Document> out) throws IOException {
        LoadReport report = new LoadReport();
        try (BufferedReader br = new BufferedReader(new StringReader(data))) {
            p.processData(br, 2, report, out::add);
        }
        return report;
    }

    @Test
    void allValid() throws Exception {
        List<Document> out = new ArrayList<>();
        LoadReport r = run(processor(), "1,mario,01/01/2026\n2,anna,02/02/2026\n", out);

        assertEquals(2, r.recordsRead);
        assertEquals(0, r.recordsSkipped);
        assertEquals(0, r.recordsDuplicati);
        assertEquals(2, out.size());
        assertEquals(1L, out.get(0).get("id"));
        assertEquals("MARIO", out.get(0).get("nome"));
    }

    @Test
    void loadTimestamp_addedToEveryRecord_sameValue() throws Exception {
        ColumnSchema[] schema = new CsvHeaderParser(",", "NONE").parseHeader("I;PK,S,D", "id,nome,data");
        Date ts = new Date(1_700_000_000_000L);
        CsvRecordProcessor p = new CsvRecordProcessor(schema, List.of("id"), ",", "", "T", ts);

        List<Document> out = new ArrayList<>();
        run(p, "1,mario,01/01/2026\n2,anna,02/02/2026\n", out);

        assertEquals(2, out.size());
        // stesso identico valore su tutti i record
        assertSame(ts, out.get(0).get("T"));
        assertSame(ts, out.get(1).get("T"));
    }

    @Test
    void loadTimestamp_absentWhenFieldNull() throws Exception {
        List<Document> out = new ArrayList<>();
        run(processor(), "1,mario,01/01/2026\n", out);
        assertEquals(1, out.size());
        assertNull(out.get(0).get("T"));
    }

    @Test
    void typeError_skipsRecord() throws Exception {
        List<Document> out = new ArrayList<>();
        LoadReport r = run(processor(), "1,mario,01/01/2026\nabc,anna,02/02/2026\n", out);

        assertEquals(2, r.recordsRead);
        assertEquals(1, r.recordsSkipped);
        assertEquals(1, out.size());
        assertTrue(r.errors.get(0).contains("Riga 4"));
        assertTrue(r.errors.get(0).contains("colonna id"));
    }

    @Test
    void emptyField_skipsRecord() throws Exception {
        List<Document> out = new ArrayList<>();
        LoadReport r = run(processor(), "1,,01/01/2026\n", out);

        assertEquals(1, r.recordsRead);
        assertEquals(1, r.recordsSkipped);
        assertEquals(0, out.size());
        assertTrue(r.errors.get(0).contains("campo obbligatorio vuoto"));
    }

    @Test
    void duplicatePk_skippedAndCounted() throws Exception {
        List<Document> out = new ArrayList<>();
        LoadReport r = run(processor(), "1,mario,01/01/2026\n1,luigi,03/03/2026\n", out);

        assertEquals(2, r.recordsRead);
        assertEquals(1, r.recordsDuplicati);
        assertEquals(0, r.recordsSkipped);
        assertEquals(1, out.size()); // vince la prima occorrenza
        assertEquals("MARIO", out.get(0).get("nome"));
        assertTrue(r.errors.get(0).contains("PK duplicata"));
    }

    @Test
    void blankLine_doesNotBreakRowNumbering() throws Exception {
        List<Document> out = new ArrayList<>();
        // riga 3 valida, riga 4 vuota, riga 5 con errore di tipo
        LoadReport r = run(processor(), "1,mario,01/01/2026\n\nabc,anna,02/02/2026\n", out);

        assertEquals(2, r.recordsRead); // la riga vuota non conta
        assertEquals(1, r.recordsSkipped);
        assertEquals(1, out.size());
        assertTrue(r.errors.get(0).contains("Riga 5")); // riga fisica corretta
    }
}
