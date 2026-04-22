package com.example;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * MongoCSVLoader - Carica un file CSV su MongoDB con tre modalità:
 *   TI  = Truncate Insert  (svuota la collezione poi inserisce)
 *   IA  = Insert Append    (inserisce senza toccare i dati esistenti)
 *   IU  = Insert Update    (upsert: aggiorna se esiste, inserisce se nuovo; richiede --key)
 *
 * Uso:
 *   java -jar csv-mongo-loader.jar \
 *     <mongoUri> <database> <collection> <csvPath> \
 *     <separatore> <enclosure|NONE> <modo:TI|IA|IU> [chiaveUpsert]
 *
 * Esempi:
 *   # TI con virgola, nessun enclosure
 *   java -jar csv-mongo-loader.jar mongodb://localhost:27017 mydb mycoll dati.csv , NONE TI
 *
 *   # IU con punto e virgola, enclosure doppio apice, chiave = id_chiave
 *   java -jar csv-mongo-loader.jar mongodb://localhost:27017 mydb mycoll dati.csv ; '"' IU id_chiave
 *
 *   # IA con virgola, enclosure asterisco
 *   java -jar csv-mongo-loader.jar mongodb://localhost:27017 mydb mycoll dati.csv , '*' IA
 */
public class MongoCSVLoader {

    private static final String LOG_COLLECTION = "C_DR_APP_LOG_FILE_CSV";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static void main(String[] args) {
        if (args.length < 7) {
            System.err.println("Uso: java -jar csv-mongo-loader.jar " +
                    "<mongoUri> <database> <collection> <csvPath> " +
                    "<separatore> <enclosure|NONE> <modo:TI|IA|IU> [chiaveUpsert]");
            System.exit(1);
        }

        String uri       = args[0];
        String dbName    = args[1];
        String collName  = args[2];
        String csvPath   = args[3];
        String separator = args[4];
        String enclosure = "NONE".equalsIgnoreCase(args[5]) ? "" : args[5];
        String mode      = args[6].toUpperCase();
        String updateKey = args.length > 7 ? args[7] : null;

        if ("IU".equals(mode) && (updateKey == null || updateKey.isBlank())) {
            System.err.println("Errore: la modalità IU richiede il parametro chiaveUpsert.");
            System.exit(1);
        }

        System.out.printf("Avvio: modo=%s | file=%s | sep='%s' | enclosure='%s'%s%n",
                mode, csvPath, separator, enclosure,
                updateKey != null ? " | chiave=" + updateKey : "");

        try (MongoClient mongoClient = MongoClients.create(uri)) {
            MongoDatabase db       = mongoClient.getDatabase(dbName);
            MongoCollection<Document> coll    = db.getCollection(collName);
            MongoCollection<Document> logColl = db.getCollection(LOG_COLLECTION);

            File csvFile = new File(csvPath);

            // ── Controllo presenza file ──────────────────────────────────────
            if (!csvFile.exists()) {
                logColl.insertOne(buildLog(csvPath, mode, "FILE_NOT_FOUND", 0,
                        "File non trovato nel percorso indicato."));
                System.out.println("File non trovato. Log scritto su MongoDB.");
                return;
            }

            // ── Elaborazione ────────────────────────────────────────────────
            String status   = "SUCCESS";
            String errorMsg = null;
            int    count    = 0;

            try {
                List<Document> rows = parseCSV(csvFile, separator, enclosure);
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
                                    "Modalità sconosciuta: '" + mode + "'. Usa TI, IA o IU.");
                    }
                    renameFile(csvFile);
                }

            } catch (Exception e) {
                status   = "ERROR";
                errorMsg = e.getMessage();
                e.printStackTrace();
            } finally {
                logColl.insertOne(buildLog(csvPath, mode, status, count, errorMsg));
                System.out.printf("Fine. status=%s | record=%d%n", status, count);
            }
        }
    }

    // ── Parsing CSV ─────────────────────────────────────────────────────────

    private static List<Document> parseCSV(File file, String separator, String enclosure)
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

    private static String[] splitLine(String line, String separator, String enclosure) {
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

    private static void renameFile(File file) {
        String ts      = LocalDateTime.now().format(TS_FMT);
        String oldName = file.getName();
        String newName = oldName.replace(".csv", "_loaded_" + ts + ".csv");
        File   renamed = new File(file.getParent(), newName);
        if (!file.renameTo(renamed)) {
            System.err.println("Attenzione: impossibile rinominare il file " + file.getPath());
        } else {
            System.out.println("File rinominato in: " + renamed.getName());
        }
    }

    // ── Documento di log ─────────────────────────────────────────────────────

    private static Document buildLog(String fileName, String type,
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
