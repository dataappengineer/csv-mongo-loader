package com.example;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Lettura CSV deterministica rispetto all'encoding (requisiti "LETTURA SICURA"):
 *
 *  1. UTF-8 esplicito       (mai il charset di default: su Windows sarebbe
 *                            windows-1252 con conseguente mojibake).
 *  2. Strip del BOM UTF-8    (U+FEFF) sulla prima riga: prodotto da Excel/strumenti
 *                            Windows, altrimenti resterebbe attaccato al primo campo.
 *  3. Normalizzazione NFC    di ogni riga: le lettere accentate hanno un'unica forma
 *                            byte canonica (e-acuta = U+00E9), cosi' il match su PK e
 *                            la verifica duplicati sono deterministici su ogni piattaforma.
 *
 * Applicare NFC all'intera riga (prima dello split) e' corretto: separatori ed
 * enclosure sono caratteri ASCII, non toccati dalla normalizzazione.
 */
public final class CsvSafeReader {

    private static final char BOM = (char) 0xFEFF;

    private CsvSafeReader() {
    }

    /** Apre un reader bufferizzato sul file forzando UTF-8. */
    public static BufferedReader newUtf8Reader(File file) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
    }

    /**
     * Ripulisce una riga letta: strip BOM (solo sulla prima riga) + normalizzazione NFC.
     *
     * @param line        riga grezza (puo' essere null a fine file)
     * @param isFirstLine true se e' la prima riga fisica del file
     * @return riga sanificata, oppure null se line era null
     */
    public static String sanitize(String line, boolean isFirstLine) {
        if (line == null) {
            return null;
        }
        if (isFirstLine && !line.isEmpty() && line.charAt(0) == BOM) {
            line = line.substring(1);
        }
        return Normalizer.normalize(line, Normalizer.Form.NFC);
    }
}
