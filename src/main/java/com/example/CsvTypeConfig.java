package com.example;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Configurazione dei tipi CSV: formati date/datetime, valori booleani, timezone e
 * il campo tecnico di timestamp del caricamento.
 *
 * I valori hanno un default sensato ed sono **sovrascrivibili a startup** da
 * application.properties tramite {@link CsvTypeConfigBinder} (che chiama
 * {@link #configure} e {@link #configureLoadTimestamp}). Dopo l'avvio la
 * configurazione è di sola lettura.
 *
 * Il locale è fisso a ROOT (determinismo, indipendenza dalla piattaforma) e NON
 * è configurabile. I DateTimeFormatter sono costruiti in modalità STRICT con
 * anno prolettico ("uuuu") per rifiutare le date invalide (vedi lesson JS-010).
 */
public final class CsvTypeConfig {

    private CsvTypeConfig() {
    }

    /** Locale fisso (non configurabile) per garantire trasformazioni deterministiche. */
    public static final Locale LOCALE = Locale.ROOT;

    /** Formato del campo tecnico timestamp del caricamento. */
    public enum LoadTimestampFormat { DATE, ISO, EPOCH }

    // ─── Default ───
    public static final ZoneId DEFAULT_TIMEZONE = ZoneOffset.UTC;
    // Formato primario dd/MM/yyyy (data) e dd/MM/yyyy HH:mm:ss (datetime); i pattern
    // successivi sono tolleranze: cifra singola (d/M, H), anno a 2 cifre (yy, pivot
    // 2000-2099), ISO. L'ordine conta: i 4-cifre-anno prima dei 2-cifre per evitare ambiguita'.
    public static final String[] DEFAULT_DATE_FORMATS = {
            "dd/MM/yyyy", "d/M/yyyy", "dd/MM/yy", "d/M/yy", "yyyy-M-d", "d-M-yyyy"};
    public static final String[] DEFAULT_DATETIME_FORMATS = {
            "dd/MM/yyyy HH:mm:ss", "d/M/yyyy H:mm:ss", "d/M/yy H:mm:ss",
            "yyyy-M-d H:mm:ss", "yyyy-M-d'T'H:mm:ss", "yyyy-M-d'T'H:mm:ss'Z'"};
    public static final Set<String> DEFAULT_BOOLEAN_TRUE =
            Set.of("SI", "S", "TRUE", "1", "Y", "YES", "VRAI", "V");
    public static final Set<String> DEFAULT_BOOLEAN_FALSE =
            Set.of("NO", "N", "FALSE", "0", "FAUX", "F");
    /** Nome di default del campo tecnico timestamp aggiunto a ogni record. */
    public static final String DEFAULT_LOAD_TIMESTAMP_FIELD = "T";
    public static final LoadTimestampFormat DEFAULT_LOAD_TIMESTAMP_FORMAT = LoadTimestampFormat.EPOCH;

    // ─── Stato configurabile (volatile: scritto una volta a startup, letto dai transformer) ───
    private static volatile ZoneId timezone = DEFAULT_TIMEZONE;
    private static volatile String[] dateFormats = DEFAULT_DATE_FORMATS;
    private static volatile String[] dateTimeFormats = DEFAULT_DATETIME_FORMATS;
    private static volatile Set<String> booleanTrue = DEFAULT_BOOLEAN_TRUE;
    private static volatile Set<String> booleanFalse = DEFAULT_BOOLEAN_FALSE;
    private static volatile String loadTimestampField = DEFAULT_LOAD_TIMESTAMP_FIELD;
    private static volatile LoadTimestampFormat loadTimestampFormat = DEFAULT_LOAD_TIMESTAMP_FORMAT;

    // Formatter derivati, costruiti in lazy e invalidati da configure()
    private static volatile DateTimeFormatter[] dateFormatters;
    private static volatile DateTimeFormatter[] dateTimeFormatters;

    public static ZoneId getTimezone() {
        return timezone;
    }

    public static Set<String> getBooleanTrue() {
        return booleanTrue;
    }

    public static Set<String> getBooleanFalse() {
        return booleanFalse;
    }

    public static String getLoadTimestampField() {
        return loadTimestampField;
    }

    public static LoadTimestampFormat getLoadTimestampFormat() {
        return loadTimestampFormat;
    }

    public static DateTimeFormatter[] getDateFormatters() {
        DateTimeFormatter[] f = dateFormatters;
        if (f == null) {
            synchronized (CsvTypeConfig.class) {
                f = dateFormatters;
                if (f == null) {
                    f = build(dateFormats);
                    dateFormatters = f;
                }
            }
        }
        return f;
    }

    public static DateTimeFormatter[] getDateTimeFormatters() {
        DateTimeFormatter[] f = dateTimeFormatters;
        if (f == null) {
            synchronized (CsvTypeConfig.class) {
                f = dateTimeFormatters;
                if (f == null) {
                    f = build(dateTimeFormats);
                    dateTimeFormatters = f;
                }
            }
        }
        return f;
    }

    /**
     * Costruisce il valore del campo tecnico timestamp dall'istante di caricamento,
     * secondo il formato configurato. Il chiamante calcola l'istante **una sola volta**
     * per caricamento, così da avere lo stesso valore su tutti i record (utile per i
     * delta successivi).
     *
     * @return java.util.Date (DATE), String ISO-8601 (ISO) oppure Long epoch millis (EPOCH)
     */
    public static Object buildLoadTimestamp(Instant instant) {
        switch (loadTimestampFormat) {
            case ISO:
                return instant.toString();
            case EPOCH:
                return instant.toEpochMilli();
            case DATE:
            default:
                return Date.from(instant);
        }
    }

    /**
     * Sovrascrive la configurazione dei tipi (chiamato una volta a startup dal binder).
     * I parametri null o vuoti lasciano invariato il valore corrente. Invalida i
     * formatter in cache.
     */
    public static synchronized void configure(ZoneId tz, String[] df, String[] dtf,
                                              Set<String> bt, Set<String> bf) {
        if (tz != null) {
            timezone = tz;
        }
        if (df != null && df.length > 0) {
            dateFormats = df;
        }
        if (dtf != null && dtf.length > 0) {
            dateTimeFormats = dtf;
        }
        if (bt != null && !bt.isEmpty()) {
            booleanTrue = bt;
        }
        if (bf != null && !bf.isEmpty()) {
            booleanFalse = bf;
        }
        dateFormatters = null;
        dateTimeFormatters = null;
    }

    /**
     * Sovrascrive nome e formato del campo tecnico timestamp (chiamato una volta a
     * startup dal binder). I parametri null o vuoti lasciano invariato il valore
     * corrente. Un formato non riconosciuto lascia il default (DATE).
     */
    public static synchronized void configureLoadTimestamp(String field, String format) {
        if (field != null && !field.isBlank()) {
            loadTimestampField = field.trim();
        }
        if (format != null && !format.isBlank()) {
            loadTimestampFormat = parseFormat(format);
        }
    }

    /** Ripristina i default (usato principalmente nei test per isolamento). */
    public static synchronized void resetDefaults() {
        timezone = DEFAULT_TIMEZONE;
        dateFormats = DEFAULT_DATE_FORMATS;
        dateTimeFormats = DEFAULT_DATETIME_FORMATS;
        booleanTrue = DEFAULT_BOOLEAN_TRUE;
        booleanFalse = DEFAULT_BOOLEAN_FALSE;
        loadTimestampField = DEFAULT_LOAD_TIMESTAMP_FIELD;
        loadTimestampFormat = DEFAULT_LOAD_TIMESTAMP_FORMAT;
        dateFormatters = null;
        dateTimeFormatters = null;
    }

    private static LoadTimestampFormat parseFormat(String format) {
        switch (format.trim().toUpperCase(LOCALE)) {
            case "ISO":
            case "STRING":
                return LoadTimestampFormat.ISO;
            case "EPOCH":
            case "MILLIS":
            case "LONG":
                return LoadTimestampFormat.EPOCH;
            case "DATE":
            case "ISODATE":
            default:
                return LoadTimestampFormat.DATE;
        }
    }

    /**
     * Costruisce i formatter: 'y' (year-of-era) -> 'u' (proleptic year), richiesto da
     * STRICT, che rifiuta le date invalide (es. 29/02 non bisestile). Vedi lesson JS-010.
     */
    private static DateTimeFormatter[] build(String[] patterns) {
        DateTimeFormatter[] f = new DateTimeFormatter[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            f[i] = DateTimeFormatter.ofPattern(patterns[i].replace('y', 'u'), LOCALE)
                    .withResolverStyle(ResolverStyle.STRICT);
        }
        return f;
    }
}
