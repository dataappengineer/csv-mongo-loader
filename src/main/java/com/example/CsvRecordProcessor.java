package com.example;

import org.bson.Document;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Elabora le righe dati del CSV (righe 3+) producendo documenti MongoDB.
 *
 * Logica pura (nessuna dipendenza da MongoDB): applica per ogni cella il controllo
 * "campo vuoto" (uniforme per tutti i tipi), la trasformazione tipizzata, e la
 * verifica duplicati sulle chiavi PK all'interno del file. Aggiorna un LoadReport
 * e consegna i documenti validi a un sink (che in produzione accumula i batch da
 * scrivere su Mongo, nei test li raccoglie).
 *
 * A ogni documento valido viene aggiunto un campo tecnico di timestamp del
 * caricamento (nome e valore forniti dal chiamante): lo stesso valore per tutti i
 * record dello stesso caricamento, per il controllo dei delta successivi.
 *
 * Stateful per-load (tiene le PK gia' viste): NON condividere tra thread/caricamenti.
 */
public class CsvRecordProcessor {

    private final ColumnSchema[] schema;
    private final List<String> pkFields;
    private final String separator;
    private final String enclosure;
    private final String loadTsField;   // null/blank => nessun campo tecnico aggiunto
    private final Object loadTsValue;   // stesso valore per tutti i record del caricamento
    private final Set<String> seenPks = new HashSet<>();

    public CsvRecordProcessor(ColumnSchema[] schema, List<String> pkFields,
                              String separator, String enclosure,
                              String loadTsField, Object loadTsValue) {
        this.schema = schema;
        this.pkFields = pkFields;
        this.separator = separator;
        this.enclosure = enclosure;
        this.loadTsField = loadTsField;
        this.loadTsValue = loadTsValue;
    }

    /**
     * Legge le righe dati dal reader (l'header dev'essere gia' stato consumato) e
     * consegna al sink i documenti validi.
     *
     * @param br          reader posizionato dopo le righe di header
     * @param headerLines numero di righe di header gia' consumate (per numerare le righe fisiche)
     * @param report      accumulatore conteggi/errori (aggiornato in-place)
     * @param sink        consumatore dei documenti validi
     */
    public void processData(BufferedReader br, int headerLines, LoadReport report,
                            Consumer<Document> sink) throws IOException {
        int physicalRow = headerLines;
        String raw;
        while ((raw = br.readLine()) != null) {
            physicalRow++;
            String line = CsvSafeReader.sanitize(raw, false);
            if (line.isBlank()) {
                continue; // righe vuote non contano come record
            }
            report.recordsRead++;
            String[] values = CsvLineSplitter.split(line, separator, enclosure);
            Document doc = toDocument(values, physicalRow, report);
            if (doc != null) {
                sink.accept(doc);
            }
        }
    }

    /** Trasforma una riga in Document, oppure ritorna null se scartata (report aggiornato). */
    Document toDocument(String[] values, int physicalRow, LoadReport report) {
        Document doc = new Document();

        for (int i = 0; i < schema.length; i++) {
            ColumnSchema col = schema[i];
            String rawValue = i < values.length ? values[i] : "";

            // Campo obbligatorio vuoto: controllo uniforme per tutti i tipi, prima del transform
            if (rawValue == null || rawValue.trim().isEmpty()) {
                report.addError(err(physicalRow, col.getName(), "campo obbligatorio vuoto"));
                report.recordsSkipped++;
                return null;
            }

            FieldTransformer transformer = TransformerRegistry.get(col.getType());
            try {
                doc.append(col.getName(), transformer.transform(rawValue, col));
            } catch (TransformException e) {
                report.addError(err(physicalRow, col.getName(), e.getMessage()));
                report.recordsSkipped++;
                return null;
            }
        }

        // Verifica duplicati (solo se sono definite PK)
        if (!pkFields.isEmpty()) {
            String key = pkKey(doc);
            if (!seenPks.add(key)) {
                report.addError("Riga " + physicalRow + ": PK duplicata nel file (" + key + "), riga scartata");
                report.recordsDuplicati++;
                return null;
            }
        }

        // Campo tecnico timestamp del caricamento: stesso valore per tutti i record
        if (loadTsField != null && !loadTsField.isBlank()) {
            doc.append(loadTsField, loadTsValue);
        }

        return doc;
    }

    private String pkKey(Document doc) {
        StringBuilder b = new StringBuilder();
        for (String f : pkFields) {
            // separatore di campo non stampabile (U+0001) per evitare collisioni tra valori
            b.append(f).append('=').append(doc.get(f)).append((char) 0x01);
        }
        return b.toString();
    }

    private static String err(int row, String col, String reason) {
        return "Riga " + row + ", colonna " + col + ": " + reason;
    }
}
