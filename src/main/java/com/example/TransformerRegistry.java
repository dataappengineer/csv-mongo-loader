package com.example;

import java.util.Map;

/**
 * Factory dei transformer per tipo (I, S, D, DT, DD, B).
 *
 * I transformer sono stateless, quindi una singola istanza condivisa per tipo è
 * sicura anche sotto carico concorrente (modalità asincrona). La mappa è immutabile:
 * per aggiungere un tipo si aggiunge una entry qui. Il flag PK non è un tipo: si
 * dichiara come |PK in combinazione con un tipo reale (es. I|PK, S|PK).
 */
public final class TransformerRegistry {

    private static final Map<String, FieldTransformer> TRANSFORMERS = Map.of(
            "I", new IntegerTransformer(),
            "S", new StringTransformer(),
            "D", new DateTransformer(),
            "DT", new DateTimeTransformer(),
            "DD", new DoubleTransformer(),
            "B", new BooleanTransformer()
    );

    private TransformerRegistry() {
    }

    /**
     * @param type tipo (case-insensitive)
     * @return il transformer per il tipo
     * @throws IllegalArgumentException se il tipo è null o sconosciuto
     */
    public static FieldTransformer get(String type) {
        if (type != null) {
            FieldTransformer t = TRANSFORMERS.get(type.toUpperCase(CsvTypeConfig.LOCALE));
            if (t != null) {
                return t;
            }
        }
        throw new IllegalArgumentException("Tipo sconosciuto: " + type);
    }

    /** true se il tipo è gestito. */
    public static boolean supports(String type) {
        return type != null && TRANSFORMERS.containsKey(type.toUpperCase(CsvTypeConfig.LOCALE));
    }
}
