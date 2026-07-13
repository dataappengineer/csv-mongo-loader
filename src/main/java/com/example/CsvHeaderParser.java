package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsa le righe di header del CSV:
 *   Riga 1 -> tipo + flag opzionali (delimitatore flag: '|')
 *   Riga 2 -> nomi campi MongoDB
 *
 * Produce un ColumnSchema[] e valida le combinazioni tipo/flag secondo la
 * specifica. Il delimitatore tipo/flag e' ';' (es. "I;PK", "S;HASH"); l'argomento
 * di MASK/TRUNCATE usa ':'. ATTENZIONE: poiche' i flag usano ';', il separatore di
 * colonna del CSV non puo' essere ';' quando si usano i flag (usare ',' o TAB).
 */
public class CsvHeaderParser {

    private static final Set<String> VALID_TYPES = Set.of("I", "S", "D", "DT", "DD", "B");
    private static final Set<String> RESERVED_FLAGS = Set.of("ENCRYPT", "SIGN");
    private static final String FLAG_DELIMITER = ";"; // delimitatore tipo/flag

    private final String separator;
    private final String enclosure; // "" se assente

    public CsvHeaderParser(String separator, String enclosure) {
        this.separator = separator;
        this.enclosure = (enclosure == null || "NONE".equalsIgnoreCase(enclosure)) ? "" : enclosure;
    }

    /**
     * @param line1 riga 1 (tipo + flag)
     * @param line2 riga 2 (nomi campi)
     * @return schema colonne
     * @throws ValidationException se l'header e' incompleto o non valido
     */
    public ColumnSchema[] parseHeader(String line1, String line2) throws ValidationException {
        if (line1 == null || line2 == null) {
            throw new ValidationException("Header incompleto: riga 1 (tipi) e riga 2 (nomi) sono obbligatorie");
        }

        String[] typeParts = CsvLineSplitter.split(line1, separator, enclosure);
        String[] nameParts = CsvLineSplitter.split(line2, separator, enclosure);

        if (typeParts.length != nameParts.length) {
            throw new ValidationException(String.format(
                    "Riga 1 e Riga 2 hanno numero di colonne diverso (%d vs %d)",
                    typeParts.length, nameParts.length));
        }

        ColumnSchema[] schema = new ColumnSchema[typeParts.length];
        for (int i = 0; i < typeParts.length; i++) {
            schema[i] = parseColumn(typeParts[i], nameParts[i]);
        }
        return schema;
    }

    private ColumnSchema parseColumn(String typeDecl, String rawName) throws ValidationException {
        String name = rawName.trim();
        if (!isValidFieldName(name)) {
            throw new ValidationException(2, name, rawName,
                    "Nome campo non valido (ammesso: lettera iniziale, poi lettere/numeri/underscore)");
        }

        String[] parts = typeDecl.trim().split(FLAG_DELIMITER);
        String type = parts[0].trim().toUpperCase(Locale.ROOT);
        if (!VALID_TYPES.contains(type)) {
            throw new ValidationException(1, name, typeDecl,
                    "Tipo '" + type + "' non riconosciuto (ammessi: I, S, D, DT, DD, B)");
        }

        List<String> flags = new ArrayList<>();
        boolean isPK = false, shouldHash = false, keepCase = false, noCleanup = false;
        String maskMode = null;
        Integer truncateLength = null;

        for (int j = 1; j < parts.length; j++) {
            String rawFlag = parts[j].trim();
            if (rawFlag.isEmpty()) {
                continue;
            }
            String flagUpper = rawFlag.toUpperCase(Locale.ROOT);
            String base = flagUpper.contains(":")
                    ? flagUpper.substring(0, flagUpper.indexOf(':'))
                    : flagUpper;

            if (RESERVED_FLAGS.contains(base)) {
                throw new ValidationException(1, name, typeDecl,
                        "Flag '" + base + "' e' reserved (non implementato in questa versione)");
            }
            validateFlagForType(type, base, name, typeDecl);

            switch (base) {
                case "PK":         isPK = true; break;
                case "HASH":       shouldHash = true; break;
                case "KEEP_CASE":  keepCase = true; break;
                case "NO_CLEANUP": noCleanup = true; break;
                case "MASK":       maskMode = parseMask(flagUpper, name, typeDecl); break;
                case "TRUNCATE":   truncateLength = parseTruncate(flagUpper, name, typeDecl); break;
                default:
                    throw new ValidationException(1, name, typeDecl, "Flag '" + base + "' sconosciuto");
            }
            flags.add(flagUpper);
        }

        return new ColumnSchema(name, type, flags, isPK, shouldHash, keepCase,
                noCleanup, maskMode, truncateLength);
    }

    /** Applica la tabella di compatibilita' flag-tipo di requisiti.md. */
    private void validateFlagForType(String type, String base, String name, String decl)
            throws ValidationException {
        boolean allowed;
        switch (type) {
            case "I":
            case "D":
            case "DT":
            case "DD":
                allowed = base.equals("PK");
                break;
            case "S":
                allowed = base.equals("PK") || base.equals("HASH") || base.equals("KEEP_CASE")
                        || base.equals("NO_CLEANUP") || base.equals("MASK") || base.equals("TRUNCATE");
                break;
            case "B":
            default:
                allowed = false; // Boolean non accetta flag opzionali (PK incluso)
                break;
        }
        if (!allowed) {
            throw new ValidationException(1, name, decl,
                    "Flag '" + base + "' non applicabile al tipo " + type);
        }
    }

    private String parseMask(String flagUpper, String name, String decl) throws ValidationException {
        if (!flagUpper.contains(":")) {
            return "4"; // default: ultimi 4 caratteri
        }
        String variant = flagUpper.substring(flagUpper.indexOf(':') + 1).trim();
        if (variant.equals("FULL") || variant.equals("FIRST")) {
            return variant;
        }
        try {
            int n = Integer.parseInt(variant);
            if (n <= 0) {
                throw new NumberFormatException();
            }
            return String.valueOf(n);
        } catch (NumberFormatException e) {
            throw new ValidationException(1, name, decl,
                    "MASK variante non valida: '" + variant + "' (ammessi: numero>0, FULL, FIRST)");
        }
    }

    private Integer parseTruncate(String flagUpper, String name, String decl) throws ValidationException {
        if (!flagUpper.contains(":")) {
            throw new ValidationException(1, name, decl, "TRUNCATE richiede una lunghezza: TRUNCATE:N");
        }
        String arg = flagUpper.substring(flagUpper.indexOf(':') + 1).trim();
        try {
            int n = Integer.parseInt(arg);
            if (n <= 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ValidationException(1, name, decl, "TRUNCATE lunghezza non valida: '" + arg + "'");
        }
    }

    private boolean isValidFieldName(String name) {
        return name.matches("^[a-zA-Z][a-zA-Z0-9_]*$");
    }
}
