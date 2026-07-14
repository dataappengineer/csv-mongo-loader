# csv-mongo-loader

Servizio **Spring Boot REST API** per il caricamento di file CSV su **MongoDB**.
Espone un endpoint HTTP `POST /api/load` invocabile dall'orchestratore o da qualsiasi client HTTP.
Documentazione interattiva disponibile tramite **Swagger UI**.

---

## Indice

- [Modalita' di caricamento](#modalita-di-caricamento)
- [Stack tecnologico](#stack-tecnologico)
- [Compilazione](#compilazione)
- [Avvio del servizio](#avvio-del-servizio)
- [Swagger UI](#swagger-ui)
- [Endpoint REST](#endpoint-rest)
- [Modalita' asincrona (callback)](#modalita-asincrona-callback)
- [Esempi curl](#esempi-curl)
- [Avvio MongoDB con Docker Compose](#avvio-mongodb-con-docker-compose)
- [Build immagine Docker](#build-immagine-docker)
- [Deploy su Kubernetes](#deploy-su-kubernetes)
- [Struttura del progetto](#struttura-del-progetto)

---

## Modalita' di caricamento

| Codice | Nome | Comportamento |
|--------|------|---------------|
| `TI` | Truncate Insert | Svuota la collezione, poi inserisce tutti i record del CSV |
| `IA` | Insert Append | Inserisce i nuovi record senza toccare quelli esistenti |
| `IU` | Insert Update (Upsert) | Aggiorna se il record esiste (per chiave), inserisce se nuovo |

Dopo ogni caricamento riuscito il servizio:
- **rinomina** il file con suffisso `_loaded_yyyyMMddHHmmss.csv` (evita doppi caricamenti)
- **crea/aggiorna** la vista MongoDB con il nome indicato nel parametro `nomeVista` (default: `<collezione>_RAW`)
- **scrive un documento di log** nella collezione configurata

Il caricamento avviene in **streaming per batch** (`batchSize` righe alla volta) per gestire file di grandi dimensioni senza esaurire la memoria.
Le colonne sensibili possono essere **anonimizzate con SHA-512** dichiarando il flag `;HASH` nella riga 1 del CSV (vedi sotto).

---

## Formato del file CSV

Il file ha **tre parti**: due righe di header + le righe dati. Guida completa alla compilazione in `GUIDA_CSV.md`; qui il riassunto operativo.

```csv
I;PK,S;HASH,S,D,B,DD            <- Riga 1: tipo di ogni campo + flag opzionali
id,codice_fiscale,nome,data,attivo,importo   <- Riga 2: nomi campi MongoDB
1,RSSMRA80A01H501U,mario rossi,9/7/2026,SI,2500.50   <- Riga 3+: dati
```

**Tipi (riga 1):** `I` integer (Long/Int64), `S` string, `D` date, `DT` datetime, `DD` double, `B` boolean.

**Flag opzionali (riga 1), separati da `;`:**

| Flag | Tipi | Effetto |
|------|------|---------|
| `PK` | I, S, D, DT, DD | Chiave primaria. **Obbligatoria: almeno un `;PK` per file, in tutti i modi** (identifica il record, abilita la verifica duplicati; per IU e' la chiave dell'upsert). Piu' flag `PK` = chiave composta |
| `HASH` | S | Anonimizza con SHA-512 |
| `KEEP_CASE` | S | Non forza il maiuscolo (preserva il case originale) |
| `NO_CLEANUP` | S | Non rimuove i caratteri speciali (utile per email, path) |
| `MASK:N` | S | Mostra solo gli ultimi N caratteri (anche `MASK:FULL`, `MASK:FIRST`) |
| `TRUNCATE:N` | S | Tronca a N caratteri |

> ⚠️ Il delimitatore tipo/flag e' `;`: il **separatore di colonna del CSV non puo' essere `;`** quando si usano i flag (usare `,` o TAB). L'argomento di `MASK`/`TRUNCATE` usa `:`.

**Trasformazioni automatiche (sempre applicate):** trim, normalizzazione spazi, UTF-8, strip BOM, normalizzazione Unicode NFC (accenti preservati). Per S anche maiuscolo (salvo `KEEP_CASE` o colonna `PK`, che preserva il case) e pulizia caratteri speciali (salvo `NO_CLEANUP`). Per `I` solo cifre con segno (nessun decimale/separatore); per `DD` virgola/punto decimale (no separatore migliaia). Per D/DT date/ore a cifra singola o doppia (es. `9/7/2026`), spazi multipli tollerati, validazione stretta (le date/ore invalide sono scartate).

**Campo tecnico timestamp:** a ogni record caricato viene aggiunto un campo tecnico (default `T`) con l'istante del caricamento, **uguale per tutti i record dello stesso load**, utile per il controllo dei delta successivi. Formato di default `epoch` (long millis); nome e formato configurabili (vedi `csv.load-timestamp-*`).

**Controlli:** tutti i campi sono obbligatori (cella vuota -> record scartato); i tipi incoerenti e le PK duplicate nel file vengono scartati e conteggiati, **senza interrompere** il caricamento.

---

## Stack tecnologico

- Java 11
- Spring Boot 2.7.18 (Tomcat embedded)
- MongoDB Driver Sync 4.11.1
- springdoc-openapi-ui 1.7.0 (Swagger)
- Maven 3.x

---

## Installazione e configurazione

### Prerequisiti

- **JDK 11+** a runtime (build verificata anche con JDK 21).
- **Maven 3.x** per la build.
- Un'istanza **MongoDB** raggiungibile: l'URI viene passato nel body di ogni chiamata (`mongoUri`), non è cablato nel servizio.
- Il file CSV deve risiedere in un **percorso leggibile dal processo del servizio** (`csvPath`). In container serve un volume condiviso / PVC (vedi sezione Kubernetes).

### Properties (configurazione runtime)

Il servizio usa solo `src/main/resources/application.properties`:

| Property | Default | Note |
|----------|---------|------|
| `server.port` | `8080` | Porta HTTP. Override via env: `SERVER_PORT` |
| `spring.application.name` | `csv-mongo-loader` | Override via env: `SPRING_APPLICATION_NAME` |
| `spring.autoconfigure.exclude` | `org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration` | Disattiva l'auto-config MongoDB di Spring: la connessione è creata manualmente, per-richiesta, dall'URI ricevuto nel body |

#### Configurazione dei tipi CSV (`csv.*`)

Formati e valori dei tipi, sovrascrivibili a startup. In K8s si impostano via env (es. `CSV_TIMEZONE`, `CSV_DATE_FORMATS`). Se non impostate valgono i default qui sotto (= comportamento storico).

| Property | Default | Note |
|----------|---------|------|
| `csv.timezone` | `UTC` | Timezone per i tipi `D` e `DT` |
| `csv.date-formats` | `dd/MM/yyyy,d/M/yyyy,dd/MM/yy,d/M/yy,yyyy-M-d,d-M-yyyy` | Formati data accettati (STRICT), in ordine di tentativo. Default primario `dd/MM/yyyy`; tollerati anche cifra singola, **anno a 2 cifre** (`dd/MM/yy`, pivot 2000-2099) e ISO |
| `csv.datetime-formats` | `dd/MM/yyyy HH:mm:ss,d/M/yyyy H:mm:ss,d/M/yy H:mm:ss,yyyy-M-d H:mm:ss,yyyy-M-d'T'H:mm:ss,yyyy-M-d'T'H:mm:ss'Z'` | Formati datetime (tipo `DT`). Default primario `dd/MM/yyyy HH:mm:ss`; spazi multipli tra data e ora tollerati |
| `csv.boolean-true` | `SI,S,TRUE,1,Y,YES,VRAI,V` | Valori interpretati come `true` (case-insensitive) |
| `csv.boolean-false` | `NO,N,FALSE,0,FAUX,F` | Valori interpretati come `false` (case-insensitive) |
| `csv.load-timestamp-field` | `T` | Nome del campo tecnico timestamp aggiunto a ogni record |
| `csv.load-timestamp-format` | `epoch` | Formato del valore: `epoch` (long millis), `date` (BSON ISODate), `iso` (stringa ISO-8601) |

> Il **locale** è fisso a `ROOT` (determinismo cross-piattaforma) e **non** è configurabile.
> I parametri di caricamento (`mongoUri`, `database`, `csvPath`, `modo`, ecc.) **non** sono properties: viaggiano nel body della POST ad ogni chiamata.

### Documenti collegati

- **Compilazione del file CSV**: [`GUIDA_CSV.md`](GUIDA_CSV.md)
- **Modifiche rispetto alla versione iniziale**: [`CHANGELOG.md`](CHANGELOG.md)
- **Report dei test**: [`TEST_RESULTS.md`](TEST_RESULTS.md)

---

## Compilazione

```bash
mvn clean package
```

Produce `target/csv-mongo-loader-1.0-SNAPSHOT.jar` (fat-jar autoconsistente).

---

## Avvio del servizio

```bash
java -jar target/csv-mongo-loader-1.0-SNAPSHOT.jar
```

Il servizio si avvia sulla porta **8080**.

---

## Swagger UI

Apri nel browser:

```
http://localhost:8080/swagger-ui/index.html
```

Swagger permette di esplorare l'endpoint, vedere i parametri richiesti e fare chiamate di test direttamente dall'interfaccia grafica.

OpenAPI JSON:
```
http://localhost:8080/v3/api-docs
```

---

## Endpoint REST

### `POST /api/load`

Carica un file CSV su MongoDB.

**Content-Type:** `application/json`

#### Body della richiesta

```json
{
  "mongoUri":      "mongodb://localhost:27017",
  "database":      "mio_database",
  "collezione":    "mia_collezione",
  "csvPath":       "/percorso/assoluto/dati.csv",
  "separatore":    ",",
  "enclosure":     "NONE",
  "modo":          "TI",
  "logCollezione": "C_DR_APP_LOG_FILE_CSV",
  "batchSize":     1000,
  "colonneHash":   ["codice_fiscale", "cognome"],
  "nomeVista":     "mia_collezione_RAW"
}
```

> **Valori di `mongoUri` per ambiente:**
> - Locale (no auth): `"mongodb://localhost:27017"`
> - K8s (no auth): `"mongodb://mongo-service:27017"`
> - K8s (con auth): `"mongodb://utente:password@mongo-service:27017/nome_database"`

> Per la modalita' **IU** aggiungere il campo:
> ```json
> "chiaveUpsert": ["nome_campo_chiave"]
> ```
> Per chiave composta da piu' campi:
> ```json
> "chiaveUpsert": ["campo1", "campo2"]
> ```

#### Campi del body

| Campo | Tipo | Obbligatorio | Descrizione |
|-------|------|:---:|-------------|
| `mongoUri` | string | SI | URI di connessione MongoDB |
| `database` | string | SI | Nome del database |
| `collezione` | string | SI | Nome della collezione target |
| `csvPath` | string | SI | Percorso assoluto del file CSV sul server |
| `separatore` | string | SI | Carattere separatore (es. `,` oppure `;`) |
| `enclosure` | string | SI | Delimitatore di testo (es. `"`) oppure `NONE` |
| `modo` | string | SI | `TI`, `IA` oppure `IU` |
| `logCollezione` | string | SI | Collezione MongoDB dove scrivere il log |
| `chiaveUpsert` | array di string | NO | Chiave primaria dichiarata **nella chiamata** (modalita' alternativa ai flag `;PK` nel CSV). Usata solo se la riga 1 non contiene alcun `;PK` |
| `batchSize` | integer | NO | Numero di righe per batch (default: `1000`). Controlla l'uso della RAM |
| `colonneHash` | array di string | NO | Colonne da hashare dichiarate **nella chiamata** (modalita' alternativa al flag `;HASH` nel CSV). Usato solo se la riga 1 non contiene alcun `;HASH` (e solo su colonne di tipo S) |
| `nomeVista` | string | NO | Nome della vista MongoDB da creare dopo il caricamento (default: `<collezione>_RAW`) |
| `jobId` | string | NO | Identificativo job fornito dal chiamante. Restituito nel body 202 e nel payload del callback |
| `callbackUrl` | string | NO | URL a cui inviare il risultato via POST al termine del caricamento. Se presente, il servizio risponde **202** immediatamente |
| `callbackUser` | string | Solo se callbackUrl | Username per l'autenticazione Basic Auth sul callback |
| `callbackPassword` | string | Solo se callbackUrl | Password per l'autenticazione Basic Auth sul callback |

#### Campi del report (nella risposta)

| Campo | Descrizione |
|-------|-------------|
| `status` | `SUCCESS`, `FILE_NOT_FOUND`, `EMPTY_FILE`, `ERROR`, `ACCEPTED` |
| `records` | Record **caricati** (inseriti + aggiornati). Semantica invariata rispetto alle versioni precedenti |
| `recordsRead` | Record totali letti dal CSV (righe dati), inclusi scartati e duplicati |
| `recordsInserted` | Record inseriti |
| `recordsUpdated` | Record aggiornati (modo IU) |
| `recordsSkipped` | Record scartati (campo vuoto, tipo incoerente, PK mancante) |
| `recordsDuplicati` | Record scartati perche' PK duplicata nel file |
| `errors` | Elenco errori dettagliati (max 100) |
| `jobId` | Presente solo in modalita' asincrona (202) |

Invariante: `recordsRead = records + recordsSkipped + recordsDuplicati`.

#### Tabella completa delle risposte

| Situazione | HTTP | `status` | `message` |
|---|:---:|---|---|
| Caricamento riuscito (sincrono) | 200 | `SUCCESS` | `null` |
| File CSV non trovato | 200 | `FILE_NOT_FOUND` | testo dell'errore |
| File senza righe dati | 200 | `EMPTY_FILE` | `null` |
| Errore generico (es. MongoDB non raggiungibile) | 200 | `ERROR` | testo dell'errore |
| Header riga 1/2 non valido | 200 | `ERROR` | `"Header non valido: ..."` |
| Caricamento asincrono avviato | 202 | `ACCEPTED` | `"Elaborazione asincrona avviata"` |
| Campo obbligatorio mancante nel body | 400 | `ERROR` | elenco campi mancanti |
| `modo` non valido (es. `XX`) | 400 | `ERROR` | `"Il campo modo deve essere TI, IA o IU"` |
| Nessuna PK definita (qualsiasi modo) | 200 | `ERROR` | `"E' richiesto almeno un campo PK..."` |
| `callbackUrl` presente senza credenziali | 400 | `ERROR` | `"callbackUser e callbackPassword sono obbligatori..."` |

> **Regola rapida:**
> - **HTTP 400** -> problema nel body della richiesta (colpa di chi chiama)
> - **HTTP 200** -> caricamento sincrono completato (verificare il campo `status`)
> - **HTTP 202** -> caricamento asincrono avviato; il risultato arriva via callback

Esempio risposta successo (con qualche scarto):
```json
{
  "status": "SUCCESS",
  "records": 93,
  "recordsRead": 100,
  "recordsInserted": 93,
  "recordsUpdated": 0,
  "recordsSkipped": 5,
  "recordsDuplicati": 2,
  "message": null,
  "errors": [
    "Riga 12, colonna importo: numero non valido (valore: abc)"
  ]
}
```

Esempio risposta errore validazione (HTTP 400):
```json
{
  "status": "ERROR",
  "records": 0,
  "message": "Tutti i campi obbligatori devono essere valorizzati: ..."
}
```

---

## Modalita' asincrona (callback)

Se il caricamento di file molto grandi rischia di causare timeout lato client (es. gateway con timeout 30s), e' possibile attivare la **modalita' asincrona** aggiungendo i campi `callbackUrl`, `callbackUser` e `callbackPassword` nel body della richiesta.

### Flusso

1. Il client invia `POST /api/load` con `callbackUrl` valorizzato.
2. Il servizio risponde **immediatamente con HTTP 202** e avvia il caricamento su un thread separato.
3. Al termine del caricamento (successo o errore) il servizio invia un **POST** all'URL di callback con autenticazione **Basic Auth** e il risultato nel body JSON.

### Body della risposta 202

```json
{
  "jobId":   "id57",
  "status":  "ACCEPTED",
  "records": 0,
  "message": "Elaborazione asincrona avviata"
}
```

### Payload inviato al callback (POST)

Stessa struttura della risposta sincrona, con in piu' `jobId`:

```json
{
  "jobId": "id57",
  "status": "SUCCESS",
  "records": 50000,
  "recordsRead": 50000,
  "recordsInserted": 50000,
  "recordsUpdated": 0,
  "recordsSkipped": 0,
  "recordsDuplicati": 0,
  "message": null,
  "errors": []
}
```

In caso di errore durante il caricamento, `status` sara' `ERROR` o `FILE_NOT_FOUND` e `message` conterra' la descrizione dell'errore.

### Note
- Il `jobId` e' generato dal chiamante e usato per correlare la risposta 202 con la notifica callback.
- Se il callback non e' raggiungibile, l'errore viene loggato lato server senza retry.
- Il timeout della chiamata al callback e' **30 secondi**.
- La modalita' sincrona (senza `callbackUrl`) e' invariata: `callbackUrl` e' completamente opzionale.

---

#### Caricamento in streaming (batchSize)

Il servizio legge il file CSV **una riga alla volta** (streaming) e inserisce su MongoDB a blocchi di `batchSize` righe, senza mai caricare l'intero file in RAM. Questo permette di gestire file di centinaia di MB senza `OutOfMemoryError`.

- Se `batchSize` non e' specificato, il default e' **1000** righe per batch.
- La strategia di streaming si applica a tutte le modalita' (`TI`, `IA`, `IU`).
- Una sola richiesta HTTP gestisce l'intero file: il caller non deve inviare piu' chiamate.

#### Mascheramento dati sensibili (colonneHash)

Le colonne elencate in `colonneHash` vengono sottoposte a hashing **SHA-512** prima dell'inserimento su MongoDB. Il valore originale non viene mai scritto nel database.

- SHA-512 e' irreversibile: dal valore hashato non e' possibile risalire all'originale.
- I valori vuoti non vengono hashati.
- Le colonne non presenti in `colonneHash` vengono scritte in chiaro.

Esempio: `"codice_fiscale": "RSSMRA80A01H501U"` diventa `"codice_fiscale": "3d5f8b2a...e9c1"` (128 caratteri esadecimali).

---

## Esempi curl

### TI - Truncate Insert con virgola

```bash
curl -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "mydb",
    "collezione":    "mycoll",
    "csvPath":       "/data/in/dati.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "C_DR_APP_LOG_FILE_CSV",
    "batchSize":     1000
  }'
```

### TI - Con mascheramento SHA-512 su colonne sensibili

```bash
curl -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "sanita_db",
    "collezione":    "pazienti",
    "csvPath":       "/data/in/pazienti.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "C_DR_APP_LOG_FILE_CSV",
    "batchSize":     1000,
    "colonneHash":   ["codice_fiscale", "cognome", "data_nascita"],
    "nomeVista":     "pazienti_RAW"
  }'
```

### IU - Upsert con punto e virgola ed enclosure

```bash
curl -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "mydb",
    "collezione":    "mycoll",
    "csvPath":       "/data/in/dati.csv",
    "separatore":    ";",
    "enclosure":     "\"",
    "modo":          "IU",
    "logCollezione": "C_DR_APP_LOG_FILE_CSV",
    "chiaveUpsert":  ["id_record"]
  }'
```

### TI - Test collaudo (risposta SUCCESS)

```bash
curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "collaudo_db",
    "collezione":    "test_collaudo",
    "csvPath":       "/tmp/test_collaudo.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "C_DR_APP_LOG_FILE_CSV"
  }'
```

Risposta attesa:
```json
{
    "status": "SUCCESS",
    "records": 3,
    "message": null
}
```

### Asincrono - Caricamento con callback (risposta 202 + notifica)

```bash
curl -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":        "mongodb://localhost:27017",
    "database":        "mydb",
    "collezione":      "mycoll",
    "csvPath":         "/data/in/dati.csv",
    "separatore":      ",",
    "enclosure":       "NONE",
    "modo":            "TI",
    "logCollezione":   "C_DR_APP_LOG_FILE_CSV",
    "jobId":           "id57",
    "callbackUrl":     "https://be.eka.it/api/callback",
    "callbackUser":    "utente",
    "callbackPassword":"password"
  }'
```

Risposta immediata (HTTP 202):
```json
{
  "jobId":   "id57",
  "status":  "ACCEPTED",
  "records": 0,
  "message": "Elaborazione asincrona avviata"
}
```

Al termine, il servizio invia automaticamente a `callbackUrl` il report completo (vedi "Payload inviato al callback").

### TI - File inesistente (risposta FILE_NOT_FOUND)

```bash
curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "collaudo_db",
    "collezione":    "test_collaudo",
    "csvPath":       "/tmp/file_inesistente.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "C_DR_APP_LOG_FILE_CSV"
  }'
```

Risposta attesa:
```json
{
    "status": "FILE_NOT_FOUND",
    "records": 0,
    "message": "File non trovato: /tmp/file_inesistente.csv"
}
```

---

## Avvio MongoDB con Docker Compose

```bash
docker compose up -d
```

Avvia:
- **MongoDB 7** su `localhost:27017`
- **Mongo Express** (UI web) su `http://localhost:8081`

---

## Build immagine Docker

### Prerequisiti

- Docker installato
- JAR compilato in `target/` (eseguire `mvn clean package` se necessario)

### Dockerfile

Il `Dockerfile` nella root del progetto usa `eclipse-temurin:11-jre-alpine` come base (~85 MB):

```dockerfile
FROM eclipse-temurin:11-jre-alpine
WORKDIR /app
COPY target/csv-mongo-loader-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Build e test locale

```bash
# 1. Compila il JAR (se non e' gia' presente)
mvn clean package

# 2. Build dell'immagine Docker
docker build -t csv-mongo-loader:1.0 .

# 3. Verifica che l'immagine sia stata creata
docker images | grep csv-mongo-loader

# 4. Avvia il container in test locale
docker run -d --name csv-loader-test -p 8082:8080 csv-mongo-loader:1.0

# 5. Verifica che il servizio sia up
curl -s http://localhost:8082/swagger-ui/index.html | grep -o '<title>[^<]*</title>'

# 6. Cleanup
docker stop csv-loader-test && docker rm csv-loader-test
```

> **Nota**: in test locale il container non raggiunge MongoDB su `localhost:27017` perche'
> il networking e' isolato. In K8s il `mongoUri` punta al Service di MongoDB nel cluster.

### Push su registry aziendale

```bash
# Tag con il registry aziendale
docker tag csv-mongo-loader:1.0 registry.azienda.it/csv-mongo-loader:1.0

# Push
docker push registry.azienda.it/csv-mongo-loader:1.0
```

---

## Deploy su Kubernetes

### Oggetti K8s necessari

| Oggetto | Scopo |
|---|---|
| `Deployment` | Gestisce il pod con il container csv-mongo-loader |
| `Service` | Espone il pod sulla rete del cluster |
| `ConfigMap` | Configurazioni non sensibili (porta, nome app) |
| `Secret` | Credenziali MongoDB (se con autenticazione) |
| `PersistentVolumeClaim` | Montaggio directory CSV sul pod |

### Deployment manifest di esempio

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: csv-mongo-loader
spec:
  replicas: 1
  selector:
    matchLabels:
      app: csv-mongo-loader
  template:
    metadata:
      labels:
        app: csv-mongo-loader
    spec:
      containers:
        - name: csv-mongo-loader
          image: registry.azienda.it/csv-mongo-loader:1.0
          ports:
            - containerPort: 8080
          env:
            - name: SERVER_PORT
              value: "8080"
          volumeMounts:
            - name: csv-volume
              mountPath: /data/in
      volumes:
        - name: csv-volume
          persistentVolumeClaim:
            claimName: csv-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: csv-loader-svc
spec:
  selector:
    app: csv-mongo-loader
  ports:
    - port: 8080
      targetPort: 8080
```

### Variabili d'ambiente configurabili

In locale i valori di default vengono da `src/main/resources/application.properties`:

```properties
server.port=8080
spring.application.name=csv-mongo-loader
```

In K8s questi stessi valori si sovrascrivono tramite la sezione `env` del container nel `Deployment`.
Spring Boot legge automaticamente le variabili d'ambiente convertendo `SERVER_PORT` → `server.port`
e `SPRING_APPLICATION_NAME` → `spring.application.name` (lettere maiuscole, underscore al posto del punto).

```yaml
containers:
  - name: csv-mongo-loader
    image: registry.azienda.it/csv-mongo-loader:1.0
    ports:
      - containerPort: 8080
    env:
      - name: SERVER_PORT
        value: "8080"
      - name: SPRING_APPLICATION_NAME
        value: "csv-mongo-loader"
```

Per valori sensibili (es. credenziali MongoDB) si usa un `Secret` invece di `value` diretto:

```yaml
env:
  - name: MONGO_PASSWORD
    valueFrom:
      secretKeyRef:
        name: mongo-credentials
        key: password
```

| Variabile | Default (application.properties) | Dove si imposta in K8s |
|---|---|---|
| `SERVER_PORT` | `8080` | `env` nel Deployment o ConfigMap |
| `SPRING_APPLICATION_NAME` | `csv-mongo-loader` | `env` nel Deployment o ConfigMap |
| credenziali MongoDB | - | `Secret` K8s, referenziato in `env` |

> I parametri `mongoUri`, `database`, `collezione`, `csvPath` ecc. **non** vanno nelle
> variabili d'ambiente - vengono passati nel body della POST dall'orchestratore ad ogni chiamata.

### Autenticazione MongoDB: locale vs collaudo/produzione

Il campo `mongoUri` cambia in base all'ambiente perche' MongoDB puo' girare con o senza autenticazione.

**In locale (Docker Compose)** - MongoDB e' avviato senza credenziali, quindi l'URI non le richiede:
```json
"mongoUri": "mongodb://localhost:27017"
```

Questo funziona perche' il `docker-compose.yml` avvia MongoDB **senza** `MONGO_INITDB_ROOT_USERNAME`
e `MONGO_INITDB_ROOT_PASSWORD`, lasciandolo aperto.

**In collaudo / produzione K8s** - MongoDB ha autenticazione abilitata, le credenziali vanno nell'URI:
```json
"mongoUri": "mongodb://utente_collaudo:password_segreta@mongo-svc:27017/reportistica_sanita"
```

Il servizio csv-mongo-loader non conosce la password a priori - la riceve nell'URI
ad ogni chiamata dall'orchestratore, che la recupera dal **K8s Secret**:

```
K8s Secret (mongo-credentials)
        │
        ▼
Orchestratore legge la password dal Secret
        │
        ▼
Orchestratore costruisce il mongoUri con credenziali
        │
        ▼
POST /api/load  { "mongoUri": "mongodb://user:pass@host/db", ... }
        │
        ▼
csv-mongo-loader si connette a MongoDB con quell'URI
```

> **Regola di sicurezza**: le credenziali non vanno mai hardcodate nel codice o nel Dockerfile.
> Vivono solo nel K8s Secret e viaggiano nel body della POST al momento della chiamata.

### Il problema del csvPath in K8s

Il servizio legge i file CSV da un percorso sul filesystem del pod:
```json
"csvPath": "/data/in/anagrafica.csv"
```

Il filesystem del container e' **effimero** (si cancella al restart). Soluzioni:

| Soluzione | Quando usarla |
|---|---|
| **PersistentVolumeClaim (PVC)** | Il file viene depositato su storage condiviso (NFS, cloud storage) |
| **Init Container** | Un container iniziale scarica il file prima che parta il servizio |
| **Shared Volume tra pod** | L'orchestratore e il loader condividono lo stesso volume |

### Comandi kubectl essenziali

```bash
# Applica il deployment
kubectl apply -f deployment.yaml

# Controlla lo stato dei pod
kubectl get pods -l app=csv-mongo-loader

# Leggi i log del pod
kubectl logs -l app=csv-mongo-loader --tail=50

# Verifica il service
kubectl get svc csv-loader-svc

# Aggiorna a una nuova versione dell'immagine
kubectl set image deployment/csv-mongo-loader csv-mongo-loader=registry.azienda.it/csv-mongo-loader:1.1
```

### URL del servizio dentro il cluster

| Risorsa | URL |
|---|---|
| API endpoint | `http://csv-loader-svc:8080/api/load` |
| Swagger UI | `http://csv-loader-svc:8080/swagger-ui/index.html` |
| OpenAPI JSON | `http://csv-loader-svc:8080/v3/api-docs` |

---

## Struttura del progetto

```
csv-mongo-loader/
├── src/main/java/com/example/
│   │  # Web / entry point
│   ├── CsvMongoLoaderApplication.java   # Entry point Spring Boot
│   ├── LoadController.java              # @RestController POST /api/load + callback async
│   ├── LoadRequest.java                 # DTO body della richiesta
│   ├── LoadResponse.java                # DTO risposta JSON (con report)
│   │  # Orchestrazione caricamento
│   ├── MongoCSVLoader.java              # @Service: connessione, streaming, flush, log, vista
│   ├── CsvRecordProcessor.java          # elabora le righe dati -> Document (senza Mongo)
│   ├── LoadReport.java                  # accumulatore conteggi/errori
│   │  # Parsing header e lettura CSV
│   ├── CsvHeaderParser.java             # riga 1 (tipi+flag) + riga 2 (nomi) -> schema
│   ├── ColumnSchema.java                # metadati di una colonna (tipo, flag)
│   ├── CsvLineSplitter.java             # split riga CSV (RFC 4180)
│   ├── CsvSafeReader.java               # UTF-8 + strip BOM + normalizzazione NFC
│   │  # Trasformazione tipi (Transformer pattern)
│   ├── FieldTransformer.java            # interfaccia (validate + transform)
│   ├── TransformerRegistry.java         # factory tipo -> transformer
│   ├── CsvTypeConfig.java               # formati date/datetime, boolean, timezone, campo tecnico TS
│   ├── CsvTypeConfigBinder.java         # @Component: applica le property csv.* a startup
│   ├── IntegerTransformer.java          # I -> Long (Int64)
│   ├── DoubleTransformer.java           # DD -> Double
│   ├── StringTransformer.java           # S -> String (HASH, MASK, KEEP_CASE, ...)
│   ├── DateTransformer.java             # D -> Date (STRICT)
│   ├── BooleanTransformer.java          # B -> Boolean
│   ├── DateTimeTransformer.java         # DT -> Date UTC (STRICT)
│   ├── ValidationException.java         # errore header/validazione
│   └── TransformException.java          # errore trasformazione valore
├── src/test/java/com/example/          # 104 test: unit, integrazione E2E, callback
├── src/main/resources/
│   └── application.properties           # server.port, app name, esclusione MongoAutoConfiguration
├── Dockerfile                           # Build immagine Docker
├── docker-compose.yml                   # MongoDB + Mongo Express (sviluppo locale)
├── pom.xml                              # Spring Boot 2.7.18 + springdoc + starter-test
├── GUIDA_CSV.md                         # come compilare il file CSV (per chi produce i dati)
├── CHANGELOG.md                         # modifiche rispetto alla versione iniziale
└── README.md
```
