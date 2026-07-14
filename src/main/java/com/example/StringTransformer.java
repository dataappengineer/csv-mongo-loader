package com.example;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tipo V (Stringa). Output: String.
 *
 * Ordine di applicazione:
 *   1. trim + normalizza spazi                         [sempre]
 *   2. maiuscolo        SE NON keepCase E SE NON PK     (accenti preservati)
 *   3. pulizia speciali SE NON noCleanup                (tiene lettere/numeri/spazi/trattino)
 *
 * Le colonne PK preservano il case originale (la chiave deve restare identica alla
 * sorgente); la pulizia dei caratteri speciali resta comunque attiva.
 *   4. TRUNCATE (se presente)
 *   5. MASK     (se presente)
 *   6. HASH     (se presente, sempre per ultimo)
 *
 * La normalizzazione Unicode NFC è responsabilità del layer di lettura (per riga),
 * non di questo transformer.
 */
public class StringTransformer implements FieldTransformer {

    @Override
    public boolean validate(String rawValue) {
        // Qualsiasi stringa è valida per il tipo V (il "campo vuoto" è controllato a monte).
        return true;
    }

    @Override
    public Object transform(String rawValue, ColumnSchema schema) {
        String v = rawValue == null ? "" : rawValue;

        v = v.trim().replaceAll("\\s+", " ");

        // Le colonne PK preservano il case (chiave identica alla sorgente)
        if (!schema.isKeepCase() && !schema.isPK()) {
            v = v.toUpperCase(CsvTypeConfig.LOCALE);
        }
        if (!schema.isNoCleanup()) {
            v = v.replaceAll("[^\\p{L}\\p{N}\\s-]", "").trim();
        }
        if (schema.getTruncateLength() != null) {
            v = truncate(v, schema.getTruncateLength());
        }
        if (schema.getMaskMode() != null) {
            v = mask(v, schema.getMaskMode());
        }
        if (schema.isShouldHash()) {
            v = sha512(v);
        }
        return v;
    }

    private String truncate(String v, int maxLen) {
        return v.length() > maxLen ? v.substring(0, maxLen) : v;
    }

    /** MASK:N mostra gli ultimi N; FULL maschera tutto; FIRST mostra solo il primo. */
    private String mask(String v, String mode) {
        int len = v.length();
        if ("FULL".equals(mode)) {
            return "*".repeat(len);
        }
        if ("FIRST".equals(mode)) {
            if (len == 0) return "";
            if (len == 1) return "*";
            return v.charAt(0) + "*".repeat(len - 1);
        }
        int n = Integer.parseInt(mode); // il parser garantisce un intero > 0
        if (len <= n) {
            return "*".repeat(len); // stringa troppo corta: mascherata interamente
        }
        return "*".repeat(len - n) + v.substring(len - n);
    }

    private String sha512(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(128);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 non disponibile", e);
        }
    }
}
