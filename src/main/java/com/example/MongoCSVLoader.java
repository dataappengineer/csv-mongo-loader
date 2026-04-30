package com.example;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.io.*;
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

    /**
     * Esegue il caricamento del file CSV su MongoDB secondo i parametri ricevuti.
     */
    public LoadResponse load(LoadRequest req) {
        String enclosure = "NONE".equalsIgnoreCase(req.getEnclosure()) ? "" : req.getEnclosure();
        String mode = req.getModo().toUpperCase();
        String updateKey = req.getChiaveUpsert();

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
                List<Document> rows = parseCSV(csvFile, req.getSeparatore(), enclosure);
                count = rows.size();

                if (rows.isEmpty()) {
                    status = "EMPTY_FILE";
                } else {
                    switch (mode) {
                        case "TI":
                            coll.deleteMany(new Document());
                            coll.insertMany(rows);
                            break;

                        case "IA":
                            coll.insertMany(rows);
                            break;

                        case "IU":
                            List<WriteModel<Document>> ops = new ArrayList<>();
                            UpdateOptions opt = new UpdateOptions().upsert(true);
                            for (Document d : rows) {
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
                    renameFile(csvFile);
                    createRawView(db, req.getCollezione());
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

    // ── Parsing CSV ─────────────────────────────────────────────────────────

    private List<Document> parseCSV(File file, String separator, String enclosure)
            throws IOException {
        List<Document> docs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {

            String headerLine = br.readLine();
            if (headerLine == null) return docs;

            String[] headers = splitLine(headerLine, separator, enclosure);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = splitLine(line, separator, enclosure);
                Document d = new Document();
                for (int i = 0; i < headers.length; i++) {
                    d.append(headers[i].trim(), i < values.length ? values[i] : "");
                }
                docs.add(d);
            }
        }
        return docs;
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

    private void createRawView(MongoDatabase db, String collName) {
        String viewName = collName + "_RAW";
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
