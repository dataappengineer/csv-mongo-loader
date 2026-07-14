package com.example;

/**
 * Trasformatore di campo CSV, uno per tipo (I, S, D, DT, DD, B).
 *
 * Contratto a due fasi, stateless (nessuno stato d'istanza -> sicuro sotto carico
 * concorrente, es. modalità asincrona):
 *
 *   - validate(raw): verifica la coerenza col tipo. NON verifica il "campo vuoto":
 *     quel controllo è a monte, uniforme per tutti i tipi, nel loop di caricamento.
 *   - transform(raw, schema): normalizza e converte al tipo Java. Per il tipo S i
 *     flag (KEEP_CASE/NO_CLEANUP/MASK/TRUNCATE/HASH) presenti in schema guidano la
 *     trasformazione. Gli altri tipi ignorano i flag (già validati dal parser).
 *
 * validate e transform condividono la stessa logica di conversione: transform è
 * autoritativo e lancia TransformException sui valori non validi.
 */
public interface FieldTransformer {

    /** true se il valore è coerente col tipo (non applica flag né conversione finale). */
    boolean validate(String rawValue);

    /**
     * Converte il valore grezzo nel tipo Java corretto applicando le regole di
     * default del tipo e, per S, i flag di schema.
     *
     * @return oggetto tipizzato (Long, Double, String, java.util.Date, Boolean)
     * @throws TransformException se il valore non è valido per il tipo
     */
    Object transform(String rawValue, ColumnSchema schema) throws TransformException;
}
