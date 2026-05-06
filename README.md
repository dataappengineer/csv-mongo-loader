# csv-mongo-loader

Servizio **Spring Boot REST API** per il caricamento di file CSV su **MongoDB**.
Espone un endpoint HTTP `POST /api/load` invocabile dall'orchestratore o da qualsiasi client HTTP.
Documentazione interattiva disponibile tramite **Swagger UI**.

---

## Modalita' di caricamento

| Codice | Nome | Comportamento |
|--------|------|---------------|
| `TI` | Truncate Insert | Svuota la collezione, poi inserisce tutti i record del CSV |
| `IA` | Insert Append | Inserisce i nuovi record senza toccare quelli esistenti |
| `IU` | Insert Update (Upsert) | Aggiorna se il record esiste (per chiave), inserisce se nuovo |

Dopo ogni caricamento riuscito il servizio:
- **rinomina** il file con suffisso `_loaded_yyyyMMddHHmmss.csv` (evita doppi caricamenti)
- **crea/aggiorna** la vista MongoDB `<collezione>_RAW`
- **scrive un documento di log** nella collezione configurata

---

## Stack tecnologico

- Java 11
- Spring Boot 2.7.18 (Tomcat embedded)
- MongoDB Driver Sync 4.11.1
- springdoc-openapi-ui 1.7.0 (Swagger)
- Maven 3.x

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
  "logCollezione": "C_DR_APP_LOG_FILE_CSV"
}
```

> Per la modalita' **IU** aggiungere il campo:
> ```json
> "chiaveUpsert": "nome_campo_chiave"
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
| `chiaveUpsert` | string | Solo per IU | Campo usato come chiave per l'upsert |

#### Risposta (HTTP 200)

```json
{
  "status":  "SUCCESS",
  "records": 5,
  "message": null
}
```

| Campo | Valori possibili |
|-------|------------------|
| `status` | `SUCCESS`, `FILE_NOT_FOUND`, `EMPTY_FILE`, `ERROR` |
| `records` | Numero di record elaborati |
| `message` | Messaggio di errore (null se tutto OK) |

#### Risposta (HTTP 400)

Restituito in caso di parametri mancanti o modalita' non valida:

```json
{
  "status":  "ERROR",
  "records": 0,
  "message": "Tutti i campi obbligatori devono essere valorizzati: ..."
}
```

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
    "logCollezione": "C_DR_APP_LOG_FILE_CSV"
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
    "chiaveUpsert":  "id_record"
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

## Struttura del progetto

```
csv-mongo-loader/
├── src/main/java/com/example/
│   ├── CsvMongoLoaderApplication.java   # Entry point Spring Boot
│   ├── LoadController.java              # @RestController POST /api/load
│   ├── LoadRequest.java                 # DTO body della richiesta
│   ├── LoadResponse.java                # DTO risposta JSON
│   └── MongoCSVLoader.java              # @Service logica di business
├── src/main/resources/
│   └── application.properties           # Porta 8080, configurazione
├── docker-compose.yml                   # MongoDB + Mongo Express
├── pom.xml                              # Spring Boot 2.7.18 + springdoc
└── README.md
```
