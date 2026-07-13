package com.example;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Servizio di caricamento CSV su MongoDB.
 *
 * Formato CSV: riga 1 = tipi + flag (delimitatore flag '|'), riga 2 = nomi campi,
 * righe 3+ = dati. Modalita': TI (truncate+insert), IA (append), IU (upsert per PK).
 *
 * Il parsing dell'header e la trasformazione dei dati sono delegati a
 * CsvHeaderParser / CsvRecordProcessor / TransformerRegistry; qui restano
 * l'orchestrazione (connessione, streaming a batch, scrittura, log, vista) e i
 * conteggi inserted/updated ricavati da BulkWriteResult.
 */
@Service
public class MongoCSVLoader {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int HEADER_LINES = 2;

    public LoadResponse load(LoadRequest req) {
        String enclosure = "NONE".equalsIgnoreCase(req.getEnclosure()) ? "" : req.getEnclosure();
        String separator = req.getSeparatore();
        String mode = req.getModo().toUpperCase();
        int batchSize = (req.getBatchSize() != null && req.getBatchSize() > 0)
                ? req.getBatchSize() : DEFAULT_BATCH_SIZE;

        try (MongoClient mongoClient = MongoClients.create(req.getMongoUri())) {
            MongoDatabase db = mongoClient.getDatabase(req.getDatabase());
            MongoCollection<Document> coll = db.getCollection(req.getCollezione());
            MongoCollection<Document> logColl = db.getCollection(req.getLogCollezione());

            File csvFile = new File(req.getCsvPath());
            if (!csvFile.exists()) {
                logColl.insertOne(buildLog(req.getCsvPath(), mode, "FILE_NOT_FOUND", 0,
                        "File non trovato nel percorso indicato."));
                return new LoadResponse("FILE_NOT_FOUND", 0, "File non trovato: " + req.getCsvPath());
            }

            LoadReport report = new LoadReport();
            String status = "SUCCESS";
            String errorMsg = null;

            try (BufferedReader br = CsvSafeReader.newUtf8Reader(csvFile)) {

                String line1 = CsvSafeReader.sanitize(br.readLine(), true);
                String line2 = CsvSafeReader.sanitize(br.readLine(), false);
                if (line1 == null || line2 == null) {
                    logColl.insertOne(buildLog(req.getCsvPath(), mode, "EMPTY_FILE", 0, null));
                    return new LoadResponse("EMPTY_FILE", 0, null);
                }

                ColumnSchema[] schema;
                try {
                    schema = new CsvHeaderParser(separator, enclosure).parseHeader(line1, line2);
                } catch (ValidationException e) {
                    logColl.insertOne(buildLog(req.getCsvPath(), mode, "ERROR", 0, e.getMessage()));
                    return new LoadResponse("ERROR", 0, "Header non valido: " + e.getMessage());
                }

                // Due modalita' per dichiarare PK/HASH: metadati nel CSV (flag ;PK/;HASH) oppure
                // nella chiamata (chiaveUpsert/colonneHash). Se presenti entrambe, vince il CSV.
                schema = applyHashFromRequest(schema, req);
                List<String> pkFields = resolvePkFields(schema, req);
                for (String pk : pkFields) {
                    if (!columnExists(schema, pk)) {
                        logColl.insertOne(buildLog(req.getCsvPath(), mode, "ERROR", 0,
                                "chiaveUpsert riferisce colonna inesistente: " + pk));
                        return new LoadResponse("ERROR", 0,
                                "chiaveUpsert riferisce una colonna non presente nel file: " + pk);
                    }
                }
                // La PK e' obbligatoria per TUTTI i modi (TI, IA, IU): identifica il record
                // e abilita la verifica duplicati. Puo' venire dai flag ;PK di riga 1
                // oppure dal parametro chiaveUpsert della chiamata.
                if (pkFields.isEmpty()) {
                    logColl.insertOne(buildLog(req.getCsvPath(), mode, "ERROR", 0, "Nessuna PK definita"));
                    return new LoadResponse("ERROR", 0,
                            "E' richiesto almeno un campo PK: flag ;PK nella riga 1 (oppure chiaveUpsert nel body)");
                }

                // Campo tecnico timestamp: calcolato UNA volta, uguale per tutti i record del
                // caricamento (per il controllo dei delta). Non deve collidere con una colonna del file.
                String tsField = CsvTypeConfig.getLoadTimestampField();
                Object tsValue = CsvTypeConfig.buildLoadTimestamp(Instant.now());
                if (columnExists(schema, tsField)) {
                    logColl.insertOne(buildLog(req.getCsvPath(), mode, "ERROR", 0,
                            "Il campo tecnico timestamp '" + tsField + "' collide con una colonna del file"));
                    return new LoadResponse("ERROR", 0,
                            "Il nome del campo tecnico timestamp ('" + tsField + "') coincide con una colonna del CSV: "
                            + "rinominare la colonna o cambiare csv.load-timestamp-field");
                }

                if ("TI".equals(mode)) {
                    coll.deleteMany(new Document());
                }

                CsvRecordProcessor processor =
                        new CsvRecordProcessor(schema, pkFields, separator, enclosure, tsField, tsValue);
                List<Document> batch = new ArrayList<>(batchSize);
                try {
                    processor.processData(br, HEADER_LINES, report, doc -> {
                        batch.add(doc);
                        if (batch.size() >= batchSize) {
                            flush(batch, mode, pkFields, coll, report);
                            batch.clear();
                        }
                    });
                    if (!batch.isEmpty()) {
                        flush(batch, mode, pkFields, coll, report);
                        batch.clear();
                    }
                } catch (RuntimeException e) {
                    status = "ERROR";
                    errorMsg = e.getMessage();
                }
            } catch (IOException e) {
                status = "ERROR";
                errorMsg = e.getMessage();
            }

            // File con header ma senza righe dati -> EMPTY_FILE
            if ("SUCCESS".equals(status) && report.recordsRead == 0) {
                status = "EMPTY_FILE";
            }

            if ("SUCCESS".equals(status)) {
                renameFile(csvFile);
                String viewName = (req.getNomeVista() != null && !req.getNomeVista().isBlank())
                        ? req.getNomeVista()
                        : req.getCollezione() + "_RAW";
                createRawView(db, req.getCollezione(), viewName);
            }

            int loaded = report.recordsLoaded();
            logColl.insertOne(buildLog(req.getCsvPath(), mode, status, loaded, errorMsg));

            LoadResponse resp = new LoadResponse(status, loaded, errorMsg);
            resp.setRecordsRead(report.recordsRead);
            resp.setRecordsInserted(report.recordsInserted);
            resp.setRecordsUpdated(report.recordsUpdated);
            resp.setRecordsSkipped(report.recordsSkipped);
            resp.setRecordsDuplicati(report.recordsDuplicati);
            resp.setErrors(report.errors);
            return resp;
        }
    }

    /** Scrive un batch su MongoDB e aggiorna i conteggi inserted/updated nel report. */
    private void flush(List<Document> batch, String mode, List<String> pkFields,
                       MongoCollection<Document> coll, LoadReport report) {
        if (batch.isEmpty()) {
            return;
        }
        if ("IU".equals(mode)) {
            List<WriteModel<Document>> ops = new ArrayList<>(batch.size());
            UpdateOptions opt = new UpdateOptions().upsert(true);
            for (Document d : batch) {
                Document filter = new Document();
                for (String pk : pkFields) {
                    filter.append(pk, d.get(pk));
                }
                ops.add(new UpdateOneModel<>(filter, new Document("$set", d), opt));
            }
            BulkWriteResult r = coll.bulkWrite(ops);
            report.recordsInserted += r.getUpserts().size();   // upsert che hanno inserito
            report.recordsUpdated += (int) r.getModifiedCount(); // documenti effettivamente modificati
        } else { // TI, IA
            coll.insertMany(batch);
            report.recordsInserted += batch.size();
        }
    }

    /**
     * Risolve i campi PK dalle due modalita' possibili: i flag ;PK della riga 1 del CSV
     * oppure il parametro chiaveUpsert della chiamata. Se presenti i flag ;PK vincono;
     * altrimenti si usa chiaveUpsert.
     */
    static List<String> resolvePkFields(ColumnSchema[] schema, LoadRequest req) {
        List<String> pk = new ArrayList<>();
        for (ColumnSchema c : schema) {
            if (c.isPK()) {
                pk.add(c.getName());
            }
        }
        if (pk.isEmpty() && req.getChiaveUpsert() != null && !req.getChiaveUpsert().isEmpty()) {
            return new ArrayList<>(req.getChiaveUpsert());
        }
        return pk;
    }

    /**
     * Applica l'hashing indicato nella chiamata (parametro colonneHash) solo se in riga 1
     * non e' presente alcun flag ;HASH. Vale unicamente per le colonne di tipo S con nome
     * corrispondente. Ritorna lo schema originale se non c'e' nulla da applicare.
     */
    static ColumnSchema[] applyHashFromRequest(ColumnSchema[] schema, LoadRequest req) {
        for (ColumnSchema c : schema) {
            if (c.isShouldHash()) {
                return schema; // i flag ;HASH del CSV vincono
            }
        }
        List<String> hashCols = req.getColonneHash();
        if (hashCols == null || hashCols.isEmpty()) {
            return schema;
        }
        ColumnSchema[] out = new ColumnSchema[schema.length];
        for (int i = 0; i < schema.length; i++) {
            ColumnSchema c = schema[i];
            if ("S".equals(c.getType()) && hashCols.contains(c.getName())) {
                out[i] = new ColumnSchema(c.getName(), c.getType(), c.getFlags(), c.isPK(),
                        true, c.isKeepCase(), c.isNoCleanup(), c.getMaskMode(), c.getTruncateLength());
            } else {
                out[i] = c;
            }
        }
        return out;
    }

    private static boolean columnExists(ColumnSchema[] schema, String name) {
        for (ColumnSchema c : schema) {
            if (c.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void renameFile(File file) {
        String ts = LocalDateTime.now().format(TS_FMT);
        String newName = file.getName().replace(".csv", "_loaded_" + ts + ".csv");
        File renamed = new File(file.getParent(), newName);
        if (!file.renameTo(renamed)) {
            System.err.println("Attenzione: impossibile rinominare " + file.getPath());
        }
    }

    private void createRawView(MongoDatabase db, String collName, String viewName) {
        try {
            db.getCollection(viewName).drop();
        } catch (Exception ignored) {
        }
        db.createView(viewName, collName, java.util.Collections.emptyList());
    }

    private Document buildLog(String fileName, String type, String status, int records, String message) {
        Document log = new Document()
                .append("fileName", fileName)
                .append("timestamp", LocalDateTime.now().toString())
                .append("status", status)
                .append("records", records)
                .append("type", type);
        if (message != null && !message.isBlank()) {
            log.append("message", message);
        }
        return log;
    }
}
