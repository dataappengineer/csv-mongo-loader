package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CsvLineSplitterTest {

    @Test
    void split_simple_noEnclosure() {
        assertArrayEquals(new String[]{"a", "b", "c"},
                CsvLineSplitter.split("a,b,c", ",", ""));
    }

    @Test
    void split_trailingEmptyFields_preserved() {
        assertArrayEquals(new String[]{"a", "", "c"},
                CsvLineSplitter.split("a,,c", ",", ""));
        assertArrayEquals(new String[]{"a", "b", ""},
                CsvLineSplitter.split("a,b,", ",", ""));
    }

    @Test
    void split_semicolonSeparator() {
        assertArrayEquals(new String[]{"a", "b", "c"},
                CsvLineSplitter.split("a;b;c", ";", ""));
    }

    @Test
    void split_enclosureProtectsSeparator() {
        assertArrayEquals(new String[]{"a", "b,c", "d"},
                CsvLineSplitter.split("a,\"b,c\",d", ",", "\""));
    }

    @Test
    void split_escapedDoubleEnclosure() {
        // "a""b" -> a"b
        assertArrayEquals(new String[]{"a\"b"},
                CsvLineSplitter.split("\"a\"\"b\"", ",", "\""));
    }

    @Test
    void split_pipeIsNotSpecial() {
        // il pipe (delimitatore flag) non ha significato per lo splitter di dati
        assertArrayEquals(new String[]{"N|PK", "V|HASH"},
                CsvLineSplitter.split("N|PK,V|HASH", ",", ""));
    }
}
