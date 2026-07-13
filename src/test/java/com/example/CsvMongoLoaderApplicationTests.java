package com.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test di context-load: avvia il contesto applicativo COMPLETO (component-scan
 * incluso) per intercettare conflitti di bean name ed errori di inizializzazione
 * che i test slice non vedono (cfr. lesson JS-008).
 *
 * Non richiede MongoDB attivo: l'applicazione esclude MongoAutoConfiguration e il
 * MongoClient viene creato per-richiesta dentro MongoCSVLoader.load(), non al boot.
 */
@SpringBootTest
class CsvMongoLoaderApplicationTests {

    @Test
    void contextLoads() {
        // Se il contesto non si avvia, questo test fallisce: è il gate di boot.
    }
}
