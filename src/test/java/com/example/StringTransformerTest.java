package com.example;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.example.TransformerTestSupport.plain;
import static com.example.TransformerTestSupport.sPk;
import static com.example.TransformerTestSupport.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringTransformerTest {

    private final StringTransformer t = new StringTransformer();

    // ─── Default ───

    @Test
    void default_trimAndUppercase() {
        assertEquals("MARIO", t.transform("  mario  ", plain("S")));
    }

    @Test
    void default_normalizeSpaces() {
        assertEquals("MARIO ROSSI", t.transform("mario   rossi", plain("S")));
    }

    @Test
    void default_removeSpecialChars() {
        assertEquals("MARIOEXAMPLECOM", t.transform("mario@example.com", plain("S")));
    }

    @Test
    void default_accentsPreserved() {
        assertEquals("JOSÉ GARCÍA", t.transform("josé garcía", plain("S")));
    }

    // ─── Flag ───

    @Test
    void pk_preservesCase_butStillCleansSpecials() {
        // PK: niente maiuscolo (chiave identica alla sorgente)...
        assertEquals("aaa", t.transform("aaa", sPk()));
        assertEquals("Mario Rossi", t.transform("  Mario Rossi  ", sPk()));
        // ...ma la pulizia dei caratteri speciali resta attiva
        assertEquals("aa", t.transform("a@a", sPk()));
    }

    @Test
    void keepCase_preservesOriginalCase() {
        assertEquals("Mario Rossi", t.transform("  Mario Rossi  ", v(true, false, null, null, false)));
    }

    @Test
    void noCleanup_keepsSpecialChars() {
        assertEquals("MARIO@EXAMPLE.COM", t.transform("mario@example.com", v(false, true, null, null, false)));
    }

    @Test
    void keepCaseAndNoCleanup_pathPreserved() {
        assertEquals("/data/Reports/Q4", t.transform("  /data/Reports/Q4  ", v(true, true, null, null, false)));
    }

    @Test
    void mask_lastFour() {
        // 16 char -> primi 12 mascherati, ultimi 4 visibili
        assertEquals("************501U", t.transform("RSSMRA80A01H501U", v(true, false, "4", null, false)));
    }

    @Test
    void mask_full() {
        assertEquals("*****", t.transform("MARIO", v(false, false, "FULL", null, false)));
    }

    @Test
    void mask_first() {
        assertEquals("M****", t.transform("MARIO", v(false, false, "FIRST", null, false)));
    }

    @Test
    void mask_shorterThanN_fullyMasked() {
        assertEquals("**", t.transform("AB", v(false, false, "4", null, false)));
    }

    @Test
    void truncate_cutsToLength() {
        assertEquals("ABCDE", t.transform("ABCDEFGH", v(false, false, null, 5, false)));
    }

    @Test
    void hash_producesSha512Hex() {
        String out = (String) t.transform("MARIO", v(false, false, null, null, true));
        assertTrue(out.matches("[a-f0-9]{128}"));
    }

    @Test
    void order_maskBeforeHash() {
        // "ABCDEFGH" -> MASK:4 -> "****EFGH" -> HASH
        String out = (String) t.transform("ABCDEFGH", v(false, false, "4", null, true));
        assertEquals(sha512("****EFGH"), out);
    }

    // helper indipendente per verificare l'ordine mask->hash
    private static String sha512(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] h = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(128);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
