# Changelog

Tutte le modifiche rilevanti rispetto alla **versione iniziale** del connettore.
Formato ispirato a [Keep a Changelog](https://keepachangelog.com/it/).

> Nota versione: il `pom.xml` riporta ancora `1.0-SNAPSHOT`. Questa evolutiva
> giustifica un incremento (es. `2.0.0`): il **formato del file CSV in ingresso
> cambia** ed è incompatibile con quello precedente (vedi "Compatibilità").

---

## [Evolutiva] - 2026-07-10

### Modificato (NON retrocompatibile con i CSV della versione precedente)

- **Nuovo set di tipi di dato.** I codici della riga 1 diventano: `I` integer (→ Long/Int64),
  `S` string, `D` date, `DT` datetime, `DD` double, `B` boolean. I vecchi codici `N`/`V`/`T`
  **non sono più validi** e vengono rifiutati: i CSV vanno rigenerati con i nuovi codici
  (mapping: `N` → `I` o `DD`, `V` → `S`, `T` → `DT`). `PK` resta un **flag** (`I|PK`, `S|PK`, …),
  non un tipo.
- **Numeri sdoppiati.** Il vecchio tipo numerico unico (BigDecimal → Decimal128) è sostituito
  da `I` (intero, Int64) e `DD` (double). Cambia il codec di salvataggio su MongoDB.
- **Delimitatore tipo/flag cambiato da `|` a `;`** (es. `I;PK`, `S;HASH`), per allinearsi al
  formato sorgente. ⚠️ Conseguenza: il **separatore di colonna del CSV non può più essere `;`**
  quando si usano i flag (usare `,` o TAB).
- **Formati data/datetime**: default primario `dd/MM/yyyy` e `dd/MM/yyyy HH:mm:ss`; tollerati anche
  la **cifra singola** (`9/7/2026`), l'**anno a 2 cifre** (`dd/MM/yy`, pivot 2000-2099), gli
  **spazi multipli** tra data e ora (`9/7/2026  12:38:00`) e l'ISO. Property `csv.date-formats` e
  `csv.datetime-formats` aggiornate di conseguenza.
- **Le colonne `S;PK` preservano il case** originale (niente maiuscolo): la chiave resta
  identica alla sorgente. La pulizia dei caratteri speciali resta attiva.
- Property rinominata: `csv.timestamp-formats` → `csv.datetime-formats` (formati del tipo `DT`).

### Aggiunto

- **Campo tecnico di timestamp** su ogni record: calcolato **una sola volta** per caricamento
  e **identico per tutti** i record, per il controllo dei delta successivi. Nome e formato
  configurabili: `csv.load-timestamp-field` (default `T`), `csv.load-timestamp-format`
  (**default `epoch`** = long millis; `date` = ISODate; `iso` = stringa ISO-8601). Il caricamento
  fallisce (`ERROR`) se una colonna del CSV collide con il nome del campo tecnico.

---

## [Evolutiva] - 2026-07-09

### Compatibilità (leggere prima)

- **Contratto HTTP: retrocompatibile.** Endpoint `POST /api/load`, campi del body,
  jar e modalità di deploy (Docker/K8s) invariati. I campi di risposta preesistenti
  (`status`, `records`, `message`, `jobId`) mantengono lo stesso significato; i nuovi
  campi sono solo aggiuntivi.
- **Formato CSV: NON retrocompatibile.** I vecchi CSV (riga 1 = nomi campi) non sono
  più validi: ora la riga 1 dichiara i **tipi** e la riga 2 i **nomi**. I file vanno
  rigenerati nel nuovo formato (vedi `GUIDA_CSV.md`). È una migrazione dei dati in
  ingresso, non del modo di installare/chiamare il servizio.
- **Unico cambio osservabile di codice HTTP:** il caso "IU/CSV senza alcuna PK" passa
  da `400` a `200 + status=ERROR` (coerente con gli altri errori rilevati leggendo il file).

### Aggiunto

- **Formato CSV tipizzato a 3 righe**: riga 1 tipi + flag, riga 2 nomi, righe 3+ dati.
- **Cinque tipi di dato** con trasformazione/validazione dedicata: `N` (BigDecimal),
  `V` (stringa), `D` (data), `B` (boolean), `T` (timestamp).
- **Flag di colonna** nella riga 1 (delimitatore `|`): `PK`, `HASH`, `KEEP_CASE`,
  `NO_CLEANUP`, `MASK:N|FULL|FIRST`, `TRUNCATE:N`.
- **Trasformazioni automatiche**: trim, normalizzazione spazi, maiuscolo (V),
  pulizia caratteri speciali (V), normalizzazione decimale (N), parsing date/timestamp,
  riconoscimento boolean multi-lingua.
- **Lettura sicura**: UTF-8 esplicito, strip del BOM, normalizzazione Unicode NFC
  (accenti preservati, match PK deterministico su tutte le piattaforme).
- **Controlli**: campo obbligatorio vuoto, coerenza di tipo, **verifica duplicati**
  sulla PK all'interno del file, con scarto del record senza interrompere il caricamento.
- **Report strutturato** nella risposta: `recordsRead`, `recordsInserted`,
  `recordsUpdated`, `recordsSkipped`, `recordsDuplicati`, `errors` (max 100).
- **Configurazione esternalizzata** (`csv.*` in application.properties, override via
  env in K8s): `csv.timezone`, `csv.date-formats`, `csv.timestamp-formats`,
  `csv.boolean-true`, `csv.boolean-false`. Default = comportamento storico. Il locale
  resta fisso a ROOT.
- **Suite di test** (104 test): unit per parser/transformer/registry/lettura/config,
  integrazione E2E su MongoDB reale, smoke del callback e context-load.

### Modificato

- **Anonimizzazione**: si dichiara con il flag `|HASH` nella riga 1 (per colonna),
  non più solo con il parametro globale `colonneHash`.
- **Chiave upsert**: si dichiara con il flag `|PK` nella riga 1, non più solo con
  `chiaveUpsert`.
- **PK obbligatoria per tutti i modi** (TI, IA, IU): identifica il record e abilita
  la verifica duplicati. In assenza → `status=ERROR`.
- **Callback asincrono**: il payload POST ora contiene il **report completo**
  (tutti i nuovi campi), serializzato con Jackson.
- **Conteggi IU**: `recordsInserted`/`recordsUpdated` derivati dal `BulkWriteResult`
  reale (upsert vs modified), non stimati.
- **Risposta**: campo `records` mantenuto con la stessa semantica ("record caricati" =
  inseriti + aggiornati) per retrocompatibilità; i dettagli sono nei nuovi campi.

### Corretto

- **Validazione date**: passaggio a `ResolverStyle.STRICT` (+ pattern `uuuu`): le date
  inesistenti (es. `29/02` in anno non bisestile) ora sono **scartate** invece di essere
  "aggiustate" silenziosamente dal resolver SMART.
- **Callback JSON**: eliminato l'escaping manuale fragile (i messaggi d'errore con
  a-capo potevano produrre JSON non valido); ora la serializzazione è affidata a Jackson.

### Modalità alternativa (metadati nella chiamata)

Due modi paritari per dichiarare PK e hashing: nel CSV (flag di riga 1) oppure nella chiamata.
Se presenti entrambi, vince il CSV.

- `colonneHash` nel body: alternativa al flag `|HASH` nella riga 1. Applicato solo se la
  riga 1 non contiene alcun `|HASH`, e solo a colonne di tipo `V`.
- `chiaveUpsert` nel body: alternativa al flag `|PK` nella riga 1. Usato solo se la riga 1
  non contiene alcun `|PK`.

### Invariato

- Modalità di caricamento `TI` / `IA` / `IU`.
- Elaborazione in streaming per batch (`batchSize`).
- Modalità asincrona con callback e Basic Auth.
- Creazione della vista `<collezione>_RAW` e rinomina del file `_loaded_<timestamp>.csv`.
- Log su collezione MongoDB, documentazione Swagger/OpenAPI.

---

## [Iniziale] - versione MVP

- Endpoint `POST /api/load` con modalità `TI`/`IA`/`IU`.
- Riga 1 del CSV interpretata come **nomi dei campi** (nessuna tipizzazione).
- Nessuna trasformazione di tipo: tutti i valori salvati come stringhe.
- Nessuna validazione di tipo/duplicati; risposta con `status`, `records`, `message`.
- Anonimizzazione via `colonneHash`, chiave upsert via `chiaveUpsert` (nel body).
- Streaming per batch, callback asincrono, vista `_RAW`, rinomina file, log su MongoDB.
