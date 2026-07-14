# Report di Test - csv-mongo-loader

Versione: 2.0 (formato CSV tipizzato). Stack: Java 11, Spring Boot 2.7, MongoDB driver-sync 4.x.
Esito complessivo: **119 test automatici, 119 PASS** con Docker attivo (E2E inclusi); **113 PASS** senza Docker (gli E2E si auto-saltano).

---

## Come eseguire i test

```bash
# Unit + slice: nessuna dipendenza esterna
mvn test

# Gli integration test E2E richiedono MongoDB locale:
docker compose up -d      # avvia MongoDB 8 su localhost:27017
mvn test                  # ora girano anche gli E2E
docker compose down       # a fine test
```

> Gli E2E si **auto-saltano** (JUnit Assumption) se MongoDB non e' raggiungibile:
> `mvn test` resta verde anche senza Docker (esegue unit + slice).

---

## Riepilogo per area

| Area | Classe di test | # test |
|------|----------------|:---:|
| Avvio contesto (context-load) | `CsvMongoLoaderApplicationTests` | 1 |
| Split riga CSV (RFC 4180) | `CsvLineSplitterTest` | 6 |
| Parsing header (tipi + flag) | `CsvHeaderParserTest` | 23 |
| Transformer `I` (integer → Long) | `IntegerTransformerTest` | 5 |
| Transformer `DD` (double) | `DoubleTransformerTest` | 6 |
| Transformer `S` (stringa) | `StringTransformerTest` | 15 |
| Transformer `D` (data) | `DateTransformerTest` | 9 |
| Transformer `DT` (datetime) | `DateTimeTransformerTest` | 6 |
| Transformer `B` (boolean) | `BooleanTransformerTest` | 5 |
| Registry transformer | `TransformerRegistryTest` | 5 |
| DTO risposta (report) | `LoadResponseTest` | 5 |
| Lettura sicura (BOM/UTF-8/NFC) | `CsvSafeReaderTest` | 6 |
| Elaborazione righe dati | `CsvRecordProcessorTest` | 7 |
| Accumulatore report | `LoadReportTest` | 2 |
| Precedenza flag CSV vs parametri chiamata | `MongoCSVLoaderCompatTest` | 7 |
| Callback asincrono | `LoadControllerCallbackTest` | 1 |
| Configurazione da properties | `CsvTypeConfigTest` | 4 |
| **E2E su MongoDB reale** | `MongoCSVLoaderE2ETest` | 6 |
| **Totale** | | **119** (113 senza E2E) |

---

## Casi funzionali chiave verificati

### Formato CSV e trasformazioni
- Parsing riga 1 (tipi + flag, delimitatore tipo/flag `;`) e riga 2 (nomi); combinazioni tipo-flag valide/invalide.
- Tipi: `I` → Long (Int64), `DD` → Double (virgola/punto, no separatore migliaia), `S` (trim, maiuscolo, pulizia, accenti preservati), `D`/`DT` (parsing STRICT, cifra singola/doppia, anno a 2 cifre con pivot 2000-2099, spazi multipli tollerati, date invalide scartate), `B` (valori multi-lingua).
- Flag `S`: `HASH` (SHA-512), `KEEP_CASE`, `NO_CLEANUP`, `MASK:N/FULL/FIRST`, `TRUNCATE:N`; ordine MASK prima di HASH. Le colonne `S;PK` preservano il case.
- Lettura sicura: strip BOM, UTF-8, normalizzazione NFC.

### Controlli e report
- Campo obbligatorio vuoto → record scartato.
- Tipo incoerente → record scartato con errore (riga fisica corretta anche con righe vuote).
- PK duplicata nel file (anche chiave composta) → scartata e conteggiata (`recordsDuplicati`), vince la prima.
- Report: `records`, `recordsRead`, `recordsInserted`, `recordsUpdated`, `recordsSkipped`, `recordsDuplicati`, `errors`.
- PK obbligatoria per tutti i modi: se assente → `status=ERROR`.
- Due modalità paritarie per PK/HASH: flag nel CSV (`;PK`/`;HASH`) oppure parametri della chiamata (`chiaveUpsert`/`colonneHash`); se presenti entrambe vince il CSV.

### Campo tecnico timestamp
- Aggiunto a ogni record (default nome `T`, formato `epoch` long), **uguale per tutti** i record dello stesso caricamento; i record toccati da un load successivo ricevono il nuovo valore (controllo delta).

### Integrazione E2E (MongoDB reale)
| Caso | Verifica | Esito |
|------|----------|:---:|
| TI + tipi | svuotamento collezione, inserimento, codec: `Int64`, `double`, `ISODate`, boolean, hash, campo `T` | PASS |
| IU upsert | conteggi `recordsInserted`/`recordsUpdated` reali da `BulkWriteResult` | PASS |
| Campo `T` | stesso valore su tutti i record del load | PASS |
| Scarti + duplicati | conteggi e documenti effettivamente inseriti | PASS |
| Vista + rename | vista `<collezione>_RAW` creata, file rinominato `_loaded_<ts>.csv` | PASS |
| Nessuna PK | `status=ERROR`, nessun documento inserito | PASS |

### Smoke HTTP end-to-end (app avviata + POST reali su Docker)
| Caso | Richiesta | Esito |
|------|-----------|:---:|
| Load TI + hash via chiamata | `POST /api/load` (modo TI, `colonneHash=["codice_fiscale"]`, 2 PK nel CSV) | HTTP 200, `SUCCESS`; `codice_fiscale` hashato (SHA-512) pur senza `;HASH` nel CSV; tipi `long`/`double`/`date`; `T` epoch identico |
| Chiave composta | PK `(anno, codice)` dichiarata con `;PK` | dedup nel file; upsert per chiave composta |
| Anno a 2 cifre | data `05/06/24` | salvata come `2024-06-05` (pivot 2000-2099) |
| Load IU | update per chiave composta + insert nuovo record | `recordsUpdated=1`, `recordsInserted=1`; `T` aggiornato solo sui record toccati |

---

## Note

- La connessione a MongoDB usa l'URI ricevuto nel body ad ogni chiamata (nessuna credenziale cablata).
- Log di ogni operazione nella collezione indicata da `logCollezione`.
- Formati date/datetime, valori boolean, timezone e campo tecnico timestamp sono configurabili via properties `csv.*` (vedi README).
