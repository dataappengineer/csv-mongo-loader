package com.example;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Servizio di caricamento CSV su MongoDB.
 * Modalita' supportate:
 *   TI = Truncate Insert  (svuota la collezione poi inserisce)
 *   IA = Insert Append    (inserisce senza toccare i dati esistenti)
 *   IU = Insert Update    (upsert: aggiorna se esiste, inserisce se nuovo)
 */
@Service
public class MongoCSVLoader {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final int DEFAULT_BATCH_SIZE = 1000;

    /**
     * Esegue il caricamento del file CSV su MongoDB secondo i parametri ricevuti.
     * Legge il file in streaming per batch di batchSize righe per evitare OutOfMemoryError.
     * Le colonne in colonneHash vengono mascherate con SHA-512 prima dell'inserimento.
     */
    public LoadResponse load(LoadRequest req) {
        String enclosure = "NONE".equalsIgnoreCase(req.getEnclosure()) ? "" : req.getEnclosure();
        String mode = req.getModo().toUpperCase();
        String updateKey = req.getChiaveUpsert();
        int batchSize = (req.getBatchSize() != null && req.getBatchSize() > 0)
                ? req.getBatchSize() : DEFAULT_BATCH_SIZE;
        List<String> colonneHash = req.getColonneHash() != null
                ? req.getColonneHash() : Collections.emptyList();

        try (MongoClient mongoClient = MongoClients.create(req.getMongoUri())) {
            MongoDatabase db = mongoClient.getDatabase(req.getDatabase());
            MongoCollection<Document> coll    = db.getCollection(req.getCollezione());
            MongoCollection<Document> logColl = db.getCollection(req.getLogCollezione());

            File csvFile = new File(req.getCsvPath());

            // Controllo presenza file
            if (!csvFile.exists()) {
                logColl.insertOne(buildLog(req.getCsvPath(), mode, "FILE_NOT_FOUND", 0,
                        "File non trovato nel percorso indicato."));
                return new LoadResponse("FILE_NOT_FOUND", 0,
                        "File non trovato: " + req.getCsvPath());
            }

            String status   = "SUCCESS";
            String errorMsg = null;
            int    count    = 0;

            try {
                // TI: svuota la collezione prima di iniziare lo streaming
                if ("TI".equals(mode)) {
                    coll.deleteMany(new Document());
                }

                count = streamCSV(csvFile, req.getSeparatore(), enclosure,
                        batchSize, colonneHash, mode, updateKey, coll);

                if (count == 0) {
                    status = "EMPTY_FILE";
                } else {
                    renameFile(csvFile);
                    String viewName = (req.getNomeVista() != null && !req.getNomeVista().isBlank())
                            ? req.getNomeVista()
                            : req.getCollezione() + "_RAW";
                    createRawView(db, req.getCollezione(), viewName);
                }
            } catch (Exception e) {
                status   = "ERROR";
                errorMsg = e.getMessage();
            } finally {
                logColl.insertOne(buildLog(req.getCsvPath(), mode, status, count, errorMsg));
            }

            return new LoadResponse(status, count, errorMsg);
        }
    }

    // ── Streaming CSV con batch ──────────────────────────────────────────────

    private int streamCSV(File file, String separator, String enclosure,
                          int batchSize, List<String> colonneHash,
                          String mode, String updateKey,
                          MongoCollection<Document> coll) throws IOException {
        int total = 0;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {

            String headerLine = br.readLine();
            if (headerLine == null) return 0;

            String[] headers = splitLine(headerLine, separator, enclosure);
            List<Document> batch = new ArrayList<>(batchSize);
            String line;

            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = splitLine(line, separator, enclosure);
                Document d = new Document();
                for (int i = 0; i < headers.length; i++) {
                    String colName = headers[i].trim();
                    String val = i < values.length ? values[i] : "";
                    if (!val.isEmpty() && colonneHash.contains(colName)) {
                        val = sha512(val);
                    }
                    d.append(colName, val);
                }
                batch.add(d);

                if (batch.size() >= batchSize) {
                    flushBatch(batch, mode, updateKey, coll);
                    total += batch.size();
                    batch.clear();
                }
            }

            // ultimo batch parziale
            if (!batch.isEmpty()) {
                flushBatch(batch, mode, updateKey, coll);
                total += batch.size();
                batch.clear();
            }
        }
        return total;
    }

    private void flushBatch(List<Document> batch, String mode, String updateKey,
                            MongoCollection<Document> coll) {
        switch (mode) {
            case "TI":
            case "IA":
                coll.insertMany(batch);
                break;
            case "IU":
                List<WriteModel<Document>> ops = new ArrayList<>(batch.size());
                UpdateOptions opt = new UpdateOptions().upsert(true);
                for (Document d : batch) {
                    ops.add(new UpdateOneModel<>(
                            new Document(updateKey, d.get(updateKey)),
                            new Document("$set", d),
                            opt));
                }
                coll.bulkWrite(ops);
                break;
            default:
                throw new IllegalArgumentException(
                        "Modalita' sconosciuta: '" + mode + "'. Usa TI, IA o IU.");
        }
    }

    // ── SHA-512 ──────────────────────────────────────────────────────────────

    private String sha512(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(128);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 non disponibile", e);
        }
    }

    private String[] splitLine(String line, String separator, String enclosure) {
        String[] parts = line.split(Pattern.quote(separator), -1);
        if (enclosure == null || enclosure.isEmpty()) return parts;

        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() >= 2 * enclosure.length()
                    && p.startsWith(enclosure)
                    && p.endsWith(enclosure)) {
                p = p.substring(enclosure.length(), p.length() - enclosure.length());
            }
            result[i] = p;
        }
        return result;
    }

    // ── Rename file ──────────────────────────────────────────────────────────

    private void renameFile(File file) {
        String ts      = LocalDateTime.now().format(TS_FMT);
        String newName = file.getName().replace(".csv", "_loaded_" + ts + ".csv");
        File   renamed = new File(file.getParent(), newName);
        if (!file.renameTo(renamed)) {
            System.err.println("Attenzione: impossibile rinominare " + file.getPath());
        }
    }

    // ── Vista _RAW ───────────────────────────────────────────────────────────

    private void createRawView(MongoDatabase db, String collName, String viewName) {
        try { db.getCollection(viewName).drop(); } catch (Exception ignored) { }
        db.createView(viewName, collName, Collections.emptyList());
    }

    // ── Documento di log ─────────────────────────────────────────────────────

    private Document buildLog(String fileName, String type,
                               String status, int records, String message) {
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
