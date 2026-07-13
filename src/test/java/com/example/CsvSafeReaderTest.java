package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Le stringhe di test sono costruite con cast (char) da codepoint, cosi' il sorgente
 * resta puro ASCII e il test e' indipendente dall'encoding/normalizzazione del file.
 *   BOM     = U+FEFF
 *   COMB    = U+0301 accento acuto combinante (forma NFD)
 *   E_ACUTE = U+00E9 "e con accento acuto" precomposta (forma NFC)
 *   A_ACUTE = U+00E1 "a con accento acuto" precomposta (forma NFC)
 */
class CsvSafeReaderTest {

    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final String COMB = String.valueOf((char) 0x0301);
    private static final String E_ACUTE = String.valueOf((char) 0x00E9);
    private static final String A_ACUTE = String.valueOf((char) 0x00E1);

    @Test
    void sanitize_null() {
        assertNull(CsvSafeReader.sanitize(null, true));
    }

    @Test
    void sanitize_stripsBomOnFirstLine() {
        assertEquals("N|PK", CsvSafeReader.sanitize(BOM + "N|PK", true));
    }

    @Test
    void sanitize_doesNotStripBomOnLaterLines() {
        assertEquals(BOM + "abc", CsvSafeReader.sanitize(BOM + "abc", false));
    }

    @Test
    void sanitize_normalizesNfd_toNfc() {
        // "Jos" + "e" + accento combinante (NFD, 5 codepoint) -> "Jos" + e-acuta (NFC, 4)
        String nfd = "Jose" + COMB;
        String result = CsvSafeReader.sanitize(nfd, false);
        assertEquals("Jos" + E_ACUTE, result);
        assertEquals(4, result.length());
    }

    @Test
    void sanitize_plainLineUnchanged() {
        assertEquals("id,nome,data", CsvSafeReader.sanitize("id,nome,data", true));
    }

    @Test
    void newUtf8Reader_readsAccentedContentWithBom(@TempDir Path dir) throws Exception {
        File f = dir.resolve("test.csv").toFile();
        // BOM + "citta" + accento combinante (NFD) + newline
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(BOM + "citta" + COMB + "\n");
        }
        try (BufferedReader br = CsvSafeReader.newUtf8Reader(f)) {
            String line = CsvSafeReader.sanitize(br.readLine(), true);
            assertEquals("citt" + A_ACUTE, line); // BOM tolto, NFC applicato
        }
    }
}
