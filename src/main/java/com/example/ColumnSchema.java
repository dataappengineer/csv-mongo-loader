package com.example;

import java.util.Collections;
import java.util.List;

/**
 * Rappresenta una colonna del CSV come dichiarata nelle righe di header:
 * nome (riga 2), tipo e flag (riga 1). Value object immutabile.
 *
 * Non contiene il transformer: quello viene risolto a runtime tramite
 * TransformerRegistry (introdotto nei task successivi), mantenendo questo
 * oggetto un puro contenitore di metadati.
 */
public class ColumnSchema {

    private final String name;          // nome campo MongoDB (riga 2)
    private final String type;          // I, S, D, DT, DD, B (riga 1)
    private final List<String> flags;   // flag grezzi normalizzati maiuscoli (es. ["PK","HASH"])

    private final boolean isPK;
    private final boolean shouldHash;
    private final boolean keepCase;
    private final boolean noCleanup;
    private final String maskMode;       // null, "FULL", "FIRST" oppure lunghezza numerica ("4")
    private final Integer truncateLength; // null oppure > 0

    public ColumnSchema(String name, String type, List<String> flags,
                        boolean isPK, boolean shouldHash, boolean keepCase,
                        boolean noCleanup, String maskMode, Integer truncateLength) {
        this.name = name;
        this.type = type;
        this.flags = flags != null ? Collections.unmodifiableList(flags) : Collections.emptyList();
        this.isPK = isPK;
        this.shouldHash = shouldHash;
        this.keepCase = keepCase;
        this.noCleanup = noCleanup;
        this.maskMode = maskMode;
        this.truncateLength = truncateLength;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public List<String> getFlags() { return flags; }
    public boolean isPK() { return isPK; }
    public boolean isShouldHash() { return shouldHash; }
    public boolean isKeepCase() { return keepCase; }
    public boolean isNoCleanup() { return noCleanup; }
    public String getMaskMode() { return maskMode; }
    public Integer getTruncateLength() { return truncateLength; }

    @Override
    public String toString() {
        return name + " (" + type + ")" + (flags.isEmpty() ? "" : " " + flags);
    }
}
