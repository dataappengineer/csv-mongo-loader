package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulatore dei conteggi e degli errori di un singolo caricamento.
 * Oggetto interno mutabile, creato per-load (non condiviso tra thread).
 */
public class LoadReport {

    /** Numero massimo di errori riportati; oltre, si aggiunge un marcatore di troncamento. */
    public static final int MAX_ERRORS = 100;

    public int recordsRead;
    public int recordsSkipped;
    public int recordsDuplicati;
    public int recordsInserted;
    public int recordsUpdated;

    public final List<String> errors = new ArrayList<>();
    private boolean truncated;

    public void addError(String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        } else if (!truncated) {
            errors.add("... (ulteriori errori omessi)");
            truncated = true;
        }
    }

    /** Record effettivamente caricati (inseriti + aggiornati). */
    public int recordsLoaded() {
        return recordsInserted + recordsUpdated;
    }
}
