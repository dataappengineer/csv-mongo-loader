package com.example;

import java.util.Collections;

/**
 * Helper condiviso per costruire ColumnSchema nei test dei transformer,
 * senza passare dal parser (isolamento unit).
 */
final class TransformerTestSupport {

    private TransformerTestSupport() {
    }

    /** Schema minimale per un tipo, senza flag. */
    static ColumnSchema plain(String type) {
        return new ColumnSchema("c", type, Collections.emptyList(),
                false, false, false, false, null, null);
    }

    /** Schema tipo S con i flag rilevanti. */
    static ColumnSchema v(boolean keepCase, boolean noCleanup, String maskMode,
                          Integer truncateLength, boolean hash) {
        return new ColumnSchema("c", "S", Collections.emptyList(),
                false, hash, keepCase, noCleanup, maskMode, truncateLength);
    }

    /** Schema tipo S marcato come PK (isPK = true), senza altri flag. */
    static ColumnSchema sPk() {
        return new ColumnSchema("c", "S", Collections.emptyList(),
                true, false, false, false, null, null);
    }
}
