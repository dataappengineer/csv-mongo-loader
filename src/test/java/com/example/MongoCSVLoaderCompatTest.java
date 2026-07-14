package com.example;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica la precedenza tra le due modalita' per PK/HASH: flag del CSV (riga 1) vs
 * parametri della chiamata (chiaveUpsert, colonneHash).
 */
class MongoCSVLoaderCompatTest {

    private ColumnSchema[] parse(String line1, String line2) throws ValidationException {
        return new CsvHeaderParser(",", "NONE").parseHeader(line1, line2);
    }

    private LoadRequest req(List<String> chiaveUpsert, List<String> colonneHash) {
        LoadRequest r = new LoadRequest();
        r.setChiaveUpsert(chiaveUpsert);
        r.setColonneHash(colonneHash);
        return r;
    }

    // ─── resolvePkFields ───

    @Test
    void pk_fromFlags_winsOverChiaveUpsert() throws Exception {
        ColumnSchema[] s = parse("I;PK,S", "id,nome");
        List<String> pk = MongoCSVLoader.resolvePkFields(s, req(List.of("nome"), null));
        assertEquals(List.of("id"), pk); // il flag ;PK vince, chiaveUpsert ignorato
    }

    @Test
    void pk_fallbackToChiaveUpsert_whenNoFlag() throws Exception {
        ColumnSchema[] s = parse("I,S", "id,nome");
        List<String> pk = MongoCSVLoader.resolvePkFields(s, req(List.of("id"), null));
        assertEquals(List.of("id"), pk);
    }

    @Test
    void pk_emptyWhenNoFlagNoRequestKey() throws Exception {
        ColumnSchema[] s = parse("I,S", "id,nome");
        assertTrue(MongoCSVLoader.resolvePkFields(s, req(null, null)).isEmpty());
    }

    // ─── applyHashFromRequest ───

    @Test
    void hash_flagPresent_schemaUnchanged() throws Exception {
        ColumnSchema[] s = parse("I;PK,S;HASH", "id,cf");
        ColumnSchema[] out = MongoCSVLoader.applyHashFromRequest(s, req(null, List.of("cf")));
        assertSame(s, out); // i flag del CSV vincono: nessuna modifica
    }

    @Test
    void hash_fromRequest_whenNoFlag() throws Exception {
        ColumnSchema[] s = parse("I;PK,S,S", "id,nome,codice");
        ColumnSchema[] out = MongoCSVLoader.applyHashFromRequest(s, req(null, List.of("codice")));
        assertFalse(out[1].isShouldHash());       // nome non in colonneHash
        assertTrue(out[2].isShouldHash());         // codice hashato via parametro della chiamata
    }

    @Test
    void hash_requestIgnoredForNonStringType() throws Exception {
        ColumnSchema[] s = parse("I;PK,S", "id,nome");
        ColumnSchema[] out = MongoCSVLoader.applyHashFromRequest(s, req(null, List.of("id")));
        assertFalse(out[0].isShouldHash()); // id e' I: hash non applicabile
    }

    @Test
    void hash_noRequest_schemaUnchanged() throws Exception {
        ColumnSchema[] s = parse("I;PK,S", "id,nome");
        assertSame(s, MongoCSVLoader.applyHashFromRequest(s, req(null, null)));
    }
}
