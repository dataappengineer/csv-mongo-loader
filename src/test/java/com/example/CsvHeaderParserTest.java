package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvHeaderParserTest {

    private final CsvHeaderParser parser = new CsvHeaderParser(",", "NONE");

    // ─── Parsing valido ───

    @Test
    void parseHeader_simple() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("I,S,D", "id,nome,data");
        assertEquals(3, s.length);
        assertEquals("id", s[0].getName());
        assertEquals("I", s[0].getType());
        assertFalse(s[0].isPK());
    }

    @Test
    void parseHeader_pkMarked() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("I;PK,S,D", "id,nome,data");
        assertTrue(s[0].isPK());
        assertFalse(s[1].isPK());
    }

    @Test
    void parseHeader_compositePk() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("I;PK,S;PK,D", "id,cognome,data");
        assertTrue(s[0].isPK());
        assertTrue(s[1].isPK());
        assertFalse(s[2].isPK());
    }

    @Test
    void parseHeader_flagsHashAndKeepCase() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("I;PK,S;HASH,S;KEEP_CASE", "id,cf,nome_file");
        assertTrue(s[1].isShouldHash());
        assertTrue(s[2].isKeepCase());
    }

    @Test
    void parseHeader_noCleanup() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("S;NO_CLEANUP", "email");
        assertTrue(s[0].isNoCleanup());
    }

    @Test
    void parseHeader_combinedFlags() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("S;KEEP_CASE;HASH", "campo");
        assertTrue(s[0].isKeepCase());
        assertTrue(s[0].isShouldHash());
    }

    @Test
    void parseHeader_maskDefaultAndVariants() throws ValidationException {
        assertEquals("4", parser.parseHeader("S;MASK", "c")[0].getMaskMode());
        assertEquals("FULL", parser.parseHeader("S;MASK:FULL", "c")[0].getMaskMode());
        assertEquals("FIRST", parser.parseHeader("S;MASK:FIRST", "c")[0].getMaskMode());
        assertEquals("6", parser.parseHeader("S;MASK:6", "c")[0].getMaskMode());
    }

    @Test
    void parseHeader_truncate() throws ValidationException {
        assertEquals(Integer.valueOf(30), parser.parseHeader("S;TRUNCATE:30", "c")[0].getTruncateLength());
    }

    @Test
    void parseHeader_caseInsensitiveTypeAndFlag() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("i;pk", "id");
        assertEquals("I", s[0].getType());
        assertTrue(s[0].isPK());
    }

    @Test
    void parseHeader_tabSeparator_worksWithFlags() throws ValidationException {
        // il separatore di colonna non puo' essere ';' (delimitatore flag): qui TAB
        CsvHeaderParser p = new CsvHeaderParser("\t", "NONE");
        ColumnSchema[] s = p.parseHeader("I;PK\tS;HASH", "id\tcf");
        assertEquals(2, s.length);
        assertTrue(s[0].isPK());
        assertTrue(s[1].isShouldHash());
    }

    @Test
    void parseHeader_datetimeType() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("I;PK,DT,DD", "id,creato_il,importo");
        assertEquals("DT", s[1].getType());
        assertEquals("DD", s[2].getType());
    }

    @Test
    void parseHeader_noFlags_defaultsClean() throws ValidationException {
        ColumnSchema[] s = parser.parseHeader("S", "nome");
        assertFalse(s[0].isPK());
        assertFalse(s[0].isShouldHash());
        assertFalse(s[0].isKeepCase());
        assertFalse(s[0].isNoCleanup());
        assertNull(s[0].getMaskMode());
        assertNull(s[0].getTruncateLength());
    }

    // ─── Errori ───

    @Test
    void parseHeader_mismatchColumnCount() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("I,S", "id,nome,data"));
    }

    @Test
    void parseHeader_unknownType() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("X,S", "id,nome"));
    }

    @Test
    void parseHeader_oldTypeRejected() {
        // i vecchi codici N/V/T non sono piu' validi
        assertThrows(ValidationException.class, () -> parser.parseHeader("N,S", "id,nome"));
        assertThrows(ValidationException.class, () -> parser.parseHeader("I,V", "id,nome"));
        assertThrows(ValidationException.class, () -> parser.parseHeader("I,T", "id,creato"));
    }

    @Test
    void parseHeader_integerWithHash_rejected() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("I;HASH,S", "id,nome"));
    }

    @Test
    void parseHeader_booleanWithPk_rejected() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("I,B;PK", "id,flag"));
    }

    @Test
    void parseHeader_dateWithMask_rejected() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("D;MASK:4", "data"));
    }

    @Test
    void parseHeader_reservedEncrypt_rejected() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("S;ENCRYPT", "cf"));
    }

    @Test
    void parseHeader_invalidFieldName_rejected() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("I", "_id"));
    }

    @Test
    void parseHeader_maskInvalidVariant_rejected() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("S;MASK:PIPPO", "c"));
    }

    @Test
    void parseHeader_truncateWithoutArg_rejected() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader("S;TRUNCATE", "c"));
    }

    @Test
    void parseHeader_nullLines_rejected() {
        assertThrows(ValidationException.class,
                () -> parser.parseHeader(null, "id"));
    }
}
