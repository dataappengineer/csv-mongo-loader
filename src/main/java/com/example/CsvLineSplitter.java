package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splitter di righe CSV secondo RFC 4180.
 *
 * Riconosce l'enclosure e tratta il separatore come delimitatore di campo solo
 * quando non si trova all'interno di un campo delimitato; gestisce le virgolette
 * doppie escapate ("" -> ").
 *
 * Estratto come utility riutilizzabile: usato dal parser dell'header e (in
 * seguito) dal loop di caricamento. Stateless: solo metodi statici.
 */
public final class CsvLineSplitter {

    private CsvLineSplitter() {
    }

    /**
     * @param line      riga da splittare
     * @param separator separatore di campo (es. "," o ";")
     * @param enclosure delimitatore di testo (es. "\""); vuoto o null = nessun enclosure
     * @return array dei campi (mai null)
     */
    public static String[] split(String line, String separator, String enclosure) {
        // Fast path: nessun enclosure, split diretto (mantiene i campi vuoti finali con -1)
        if (enclosure == null || enclosure.isEmpty()) {
            return line.split(Pattern.quote(separator), -1);
        }

        char sep = separator.charAt(0);
        char enc = enclosure.charAt(0);
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == enc) {
                    // virgoletta doppia escapata: "" -> un singolo "
                    if (i + 1 < line.length() && line.charAt(i + 1) == enc) {
                        current.append(enc);
                        i++;
                    } else {
                        inQuotes = false; // fine campo delimitato
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == enc) {
                    inQuotes = true; // inizio campo delimitato
                } else if (c == sep) {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString()); // ultimo campo
        return fields.toArray(new String[0]);
    }
}
