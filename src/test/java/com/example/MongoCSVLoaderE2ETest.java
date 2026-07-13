package com.example;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke E2E contro MongoDB reale (docker compose up -d).
 * Verifica il write reale: codec (Long->Int64, Double->double, Date->ISODate),
 * il campo tecnico timestamp, conteggi inserted/updated da BulkWriteResult,
 * TI/IA/IU, scarti/duplicati, vista e rename file.
 *
 * Si auto-salta (Assumption) se MongoDB non e' raggiungibile.
 */
class MongoCSVLoaderE2ETest {

    private static final String URI = "mongodb://localhost:27017/?serverSelectionTimeoutMS=2000";
    private static final String DB = "csv_e2e";
    private static final String COLL = "clienti";
    private static final String LOG = "e2e_log";

    private static MongoClient client;
    private final MongoCSVLoader loader = new MongoCSVLoader();

    @BeforeAll
    static void checkMongo() {
        boolean up;
        try {
            client = MongoClients.create(URI);
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            up = true;
        } catch (Exception e) {
            up = false;
        }
        Assumptions.assumeTrue(up, "MongoDB non disponibile su " + URI + " (avviare: docker compose up -d)");
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void cleanDb() {
        client.getDatabase(DB).drop();
    }

    private MongoCollection<Document> coll() {
        return client.getDatabase(DB).getCollection(COLL);
    }

    private LoadRequest request(String csvPath, String modo) {
        LoadRequest r = new LoadRequest();
        r.setMongoUri(URI);
        r.setDatabase(DB);
        r.setCollezione(COLL);
        r.setCsvPath(csvPath);
        r.setSeparatore(",");
        r.setEnclosure("NONE");
        r.setModo(modo);
        r.setLogCollezione(LOG);
        return r;
    }

    private String writeCsv(Path dir, String name, String content) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f.getAbsolutePath();
    }

    private List<Document> allDocs() {
        List<Document> out = new ArrayList<>();
        coll().find().into(out);
        return out;
    }

    private Document byNome(String nome) {
        return coll().find(new Document("nome", nome)).first();
    }

    // ─── TI + tipi/trasformazioni + codec ───

    @Test
    void ti_loadsAndTransforms_withRealCodecs(@TempDir Path dir) throws Exception {
        // documento preesistente: TI deve svuotarlo
        coll().insertOne(new Document("nome", "VECCHIO"));

        String csv =
                "I;PK,S;HASH,S,D,B,DD\n" +
                "id,cf,nome,data,attivo,importo\n" +
                "1,RSSMRA80A01H501U,mario rossi,01/01/2026,SI,2500.50\n" +
                "2,VRDLNN85B15L736K,anna verdi,15/02/2026,NO,1500.00\n";
        String path = writeCsv(dir, "clienti.csv", csv);

        LoadResponse resp = loader.load(request(path, "TI"));

        assertEquals("SUCCESS", resp.getStatus());
        assertEquals(2, resp.getRecordsRead());
        assertEquals(2, resp.getRecordsInserted());
        assertEquals(2, resp.getRecords());
        assertEquals(0, resp.getRecordsSkipped());
        assertEquals(2, allDocs().size()); // il doc VECCHIO e' stato rimosso da TI

        Document mario = byNome("MARIO ROSSI"); // stringa: trim + maiuscolo
        assertNotNull(mario);
        // id intero -> Long (BSON Int64)
        assertEquals(1L, mario.get("id"));
        // importo double -> Double (BSON double)
        assertEquals(2500.50, mario.get("importo"));
        // data -> ISODate (java.util.Date)
        assertTrue(mario.get("data") instanceof Date);
        // boolean
        assertEquals(Boolean.TRUE, mario.get("attivo"));
        // campo tecnico timestamp -> epoch millis (Long, default), presente su ogni record
        assertTrue(mario.get("T") instanceof Long);
        // cf hashato (128 hex, diverso dall'originale)
        String cf = mario.getString("cf");
        assertTrue(cf.matches("[a-f0-9]{128}"));
        assertNotEquals("RSSMRA80A01H501U", cf);
    }

    // ─── Campo tecnico timestamp: stesso valore su tutti i record ───

    @Test
    void loadTimestamp_sameValueAcrossAllRecords(@TempDir Path dir) throws Exception {
        String csv = "I;PK,S\nid,nome\n1,mario\n2,anna\n3,luigi\n";
        String path = writeCsv(dir, "ts.csv", csv);

        LoadResponse resp = loader.load(request(path, "TI"));
        assertEquals("SUCCESS", resp.getStatus());

        List<Document> docs = allDocs();
        assertEquals(3, docs.size());
        Object first = docs.get(0).get("T");
        assertNotNull(first);
        assertTrue(first instanceof Long); // epoch millis (default)
        for (Document d : docs) {
            assertEquals(first, d.get("T")); // identico per tutti
        }
    }

    // ─── IU upsert: conteggi inserted vs updated reali ───

    @Test
    void iu_upsert_countsInsertedAndUpdated(@TempDir Path dir) throws Exception {
        // preinserisce id=1 con nome diverso -> l'upsert deve MODIFICARLO
        coll().insertOne(new Document("id", 1L).append("nome", "OLD"));

        String csv =
                "I;PK,S\n" +
                "id,nome\n" +
                "1,mario\n" +   // update
                "3,luigi\n";    // insert
        String path = writeCsv(dir, "iu.csv", csv);

        LoadResponse resp = loader.load(request(path, "IU"));

        assertEquals("SUCCESS", resp.getStatus());
        assertEquals(2, resp.getRecordsRead());
        assertEquals(1, resp.getRecordsInserted()); // id=3
        assertEquals(1, resp.getRecordsUpdated());   // id=1
        assertEquals(2, resp.getRecords());
        assertEquals("MARIO", byNome("MARIO").getString("nome")); // aggiornato
    }

    // ─── Scarti + duplicati ───

    @Test
    void ti_skipsInvalidAndDuplicates(@TempDir Path dir) throws Exception {
        String csv =
                "I;PK,S,D\n" +
                "id,nome,data\n" +
                "1,mario,01/01/2026\n" +   // ok
                "abc,anna,02/02/2026\n" +  // id non numerico -> scartato
                "1,luigi,03/03/2026\n" +   // PK duplicata -> scartato
                "4,,04/04/2026\n";         // nome vuoto -> scartato
        String path = writeCsv(dir, "errori.csv", csv);

        LoadResponse resp = loader.load(request(path, "TI"));

        assertEquals("SUCCESS", resp.getStatus());
        assertEquals(4, resp.getRecordsRead());
        assertEquals(1, resp.getRecordsInserted());
        assertEquals(2, resp.getRecordsSkipped());   // "abc" e nome vuoto
        assertEquals(1, resp.getRecordsDuplicati());  // id=1 ripetuto
        assertEquals(1, allDocs().size());
        assertEquals(3, resp.getErrors().size());
    }

    // ─── Vista _RAW + rename file ───

    @Test
    void createsViewAndRenamesFile(@TempDir Path dir) throws Exception {
        String csv = "I;PK,S\nid,nome\n1,mario\n";
        String path = writeCsv(dir, "vista.csv", csv);

        LoadResponse resp = loader.load(request(path, "TI"));
        assertEquals("SUCCESS", resp.getStatus());

        // vista <coll>_RAW creata
        List<String> names = new ArrayList<>();
        client.getDatabase(DB).listCollectionNames().into(names);
        assertTrue(names.contains(COLL + "_RAW"), "vista _RAW mancante: " + names);

        // file rinominato: l'originale non esiste piu', esiste un *_loaded_*.csv
        assertFalse(new File(path).exists());
        File[] renamed = dir.toFile().listFiles((d, n) -> n.startsWith("vista_loaded_") && n.endsWith(".csv"));
        assertNotNull(renamed);
        assertEquals(1, renamed.length);
    }

    // ─── PK obbligatoria per tutti i modi ───

    @Test
    void noPk_returnsError_anyMode(@TempDir Path dir) throws Exception {
        String csv = "I,S\nid,nome\n1,mario\n"; // nessun |PK dichiarato
        String path = writeCsv(dir, "nopk.csv", csv);

        LoadResponse resp = loader.load(request(path, "TI"));

        assertEquals("ERROR", resp.getStatus());
        assertTrue(resp.getMessage().contains("PK"));
        assertEquals(0, allDocs().size());
    }

    private static void assertNotNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNotNull(o);
    }
}
