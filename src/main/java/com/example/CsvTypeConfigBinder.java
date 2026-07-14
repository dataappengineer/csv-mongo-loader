package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applica a startup la configurazione dei tipi CSV letta da application.properties
 * (con default = comportamento storico), scrivendola in {@link CsvTypeConfig}.
 *
 * Usa l'iniezione da costruttore con default nei @Value: funziona anche senza alcuna
 * property impostata. Viene creato all'avvio del contesto, prima di qualsiasi richiesta.
 *
 * Properties supportate (prefisso csv.*), override in K8s via env (es. CSV_TIMEZONE):
 *   csv.timezone, csv.date-formats, csv.datetime-formats, csv.boolean-true, csv.boolean-false,
 *   csv.load-timestamp-field, csv.load-timestamp-format
 */
@Component
public class CsvTypeConfigBinder {

    public CsvTypeConfigBinder(
            @Value("${csv.timezone:UTC}") String timezone,
            @Value("${csv.date-formats:dd/MM/yyyy,d/M/yyyy,dd/MM/yy,d/M/yy,yyyy-M-d,d-M-yyyy}") String[] dateFormats,
            @Value("${csv.datetime-formats:dd/MM/yyyy HH:mm:ss,d/M/yyyy H:mm:ss,d/M/yy H:mm:ss,yyyy-M-d H:mm:ss,yyyy-M-d'T'H:mm:ss,yyyy-M-d'T'H:mm:ss'Z'}") String[] dateTimeFormats,
            @Value("${csv.boolean-true:SI,S,TRUE,1,Y,YES,VRAI,V}") String[] booleanTrue,
            @Value("${csv.boolean-false:NO,N,FALSE,0,FAUX,F}") String[] booleanFalse,
            @Value("${csv.load-timestamp-field:T}") String loadTimestampField,
            @Value("${csv.load-timestamp-format:epoch}") String loadTimestampFormat) {

        CsvTypeConfig.configure(
                ZoneId.of(timezone.trim()),
                trimAll(dateFormats),
                trimAll(dateTimeFormats),
                toUpperSet(booleanTrue),
                toUpperSet(booleanFalse));

        CsvTypeConfig.configureLoadTimestamp(loadTimestampField, loadTimestampFormat);
    }

    private static String[] trimAll(String[] a) {
        return Arrays.stream(a).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static Set<String> toUpperSet(String[] a) {
        return Arrays.stream(a)
                .map(s -> s.trim().toUpperCase(CsvTypeConfig.LOCALE))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
