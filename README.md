# CSV → MongoDB Loader

Programma Java che carica un file CSV su MongoDB in tre modalità (**TI**, **IA**, **IU**),  
registra ogni operazione su una collezione di log e rinomina il file dopo il caricamento.

Progetto sviluppato come risposta alla specifica fornita da Giulia (email del 21/04/2026).

---

## Indice

1. [Cosa fa il programma](#1-cosa-fa-il-programma)
2. [Prerequisiti](#2-prerequisiti)
3. [Come compilare](#3-come-compilare)
4. [Come avviare MongoDB con Docker](#4-come-avviare-mongodb-con-docker)
5. [Come eseguire il caricamento](#5-come-eseguire-il-caricamento)
6. [Le tre modalità: TI, IA, IU](#6-le-tre-modalit%C3%A0-ti-ia-iu)
7. [Il timestamp nel nome del file](#7-il-timestamp-nel-nome-del-file)
8. [I log su MongoDB](#8-i-log-su-mongodb)
9. [File di test inclusi](#9-file-di-test-inclusi)
10. [Cosa serve all'orchestratore](#10-cosa-serve-allorchestratore)
11. [Verifica visiva con Mongo Express](#11-verifica-visiva-con-mongo-express)

---

## 1. Cosa fa il programma

Ogni volta che viene avviato (dall'orchestratore o manualmente), il programma:

1. **Controlla** se il file CSV indicato esiste nella cartella specificata
2. Se il file **non esiste** → scrive un log su MongoDB con `status: FILE_NOT_FOUND` e termina
3. Se il file **esiste** → carica i dati nella collezione MongoDB secondo la modalità scelta (TI / IA / IU)
4. Scrive un log su MongoDB con `status: SUCCESS` e il numero di righe caricate
5. **Rinomina** il file aggiungendo un timestamp (es. `dati.csv` → `dati_loaded_20260421183655.csv`)

Alla successiva esecuzione, l'orchestratore cercherà ancora `dati.csv`:  
non trovandolo (è stato rinominato), il programma registrerà `FILE_NOT_FOUND` e attenderà il prossimo deposito.

---

## 2. Prerequisiti

| Strumento | Versione minima | Installazione (Ubuntu/WSL) |
|-----------|----------------|----------------------------|
| Java JDK  | 11             | `sudo apt install openjdk-11-jdk` |
| Maven     | 3.x            | `sudo apt install maven` |
| Docker    | qualsiasi      | [docs.docker.com](https://docs.docker.com/engine/install/) |
| Docker Compose | v2        | incluso in Docker Desktop |

Verifica dopo l'installazione:
```bash
java -version
mvn -version
docker --version
```

---

## 3. Come compilare

La compilazione scarica automaticamente il driver MongoDB da internet  
e impacchetta tutto in un unico file `.jar` eseguibile (detto **fat-jar**).

```bash
# Clona il repository
git clone https://github.com/dataappengineer/csv-mongo-loader.git
cd csv-mongo-loader

# Compila e impacchetta
mvn clean package
```

Al termine troverai il file pronto:
```
target/csv-mongo-loader-1.0-SNAPSHOT.jar   (circa 2.3 MB)
```

> **Nota:** la compilazione richiede connessione internet solo la prima volta.  
> Le dipendenze vengono salvate nella cache locale di Maven (`~/.m2/`).

---

## 4. Come avviare MongoDB con Docker

Il `docker-compose.yml` incluso avvia due servizi:

| Servizio | Descrizione | Porta |
|----------|-------------|-------|
| `csv_mongo` | MongoDB 7 | 27017 |
| `csv_mongo_express` | Interfaccia web per vedere i dati | 8081 |

```bash
# Avvia i container in background
docker compose up -d

# Verifica che siano attivi
docker compose ps
```

Per spegnere i container:
```bash
docker compose down
```

> Se il team di Giulia ha già un'istanza MongoDB, non è necessario Docker:  
> basta passare il proprio URI al posto di `mongodb://localhost:27017`.

---

## 5. Come eseguire il caricamento

### Firma del comando

```
java -jar target/csv-mongo-loader-1.0-SNAPSHOT.jar \
  <mongoUri> <database> <collezione> <percorsoCSV> \
  <separatore> <enclosure|NONE> <modo:TI|IA|IU> [chiaveUpsert]
```

### Parametri

| Posizione | Parametro | Esempio | Descrizione |
|-----------|-----------|---------|-------------|
| 1 | mongoUri | `mongodb://localhost:27017` | URI di connessione MongoDB |
| 2 | database | `mio_database` | Nome del database |
| 3 | collezione | `mia_collezione` | Nome della collezione di destinazione |
| 4 | percorsoCSV | `/data/dati.csv` | Percorso completo o relativo del file CSV |
| 5 | separatore | `,` oppure `;` | Carattere separatore del CSV |
| 6 | enclosure | `"` oppure `*` oppure `NONE` | Carattere che racchiude i valori (NONE = assente) |
| 7 | modo | `TI` / `IA` / `IU` | Modalità di caricamento |
| 8 | chiaveUpsert | `id_chiave` | Solo per IU: colonna usata come chiave di aggiornamento |

### Esempi pratici

```bash
# TI – separatore virgola, nessun enclosure
java -jar target/csv-mongo-loader-1.0-SNAPSHOT.jar \
  mongodb://localhost:27017 mio_db mia_coll dati.csv , NONE TI

# IA – separatore punto e virgola, enclosure doppio apice
java -jar target/csv-mongo-loader-1.0-SNAPSHOT.jar \
  mongodb://localhost:27017 mio_db mia_coll dati.csv ';' '"' IA

# IU – separatore virgola, enclosure asterisco, chiave = codice
java -jar target/csv-mongo-loader-1.0-SNAPSHOT.jar \
  mongodb://localhost:27017 mio_db mia_coll dati.csv , '*' IU codice
```

---

## 6. Le tre modalità: TI, IA, IU

### TI — Truncate Insert (svuota e reinserisce)

- **Cancella tutti** i documenti esistenti nella collezione
- Inserisce tutte le righe del CSV
- Usare quando il CSV è una fotografia completa aggiornata dei dati

```
Collezione prima: [A, B, C, D]  (100 documenti vecchi)
CSV contiene:     [A', B', C']  (3 righe nuove)
Collezione dopo:  [A', B', C']  (solo i 3 nuovi)
```

### IA — Insert Append (aggiunge senza cancellare)

- **Non tocca** i documenti già presenti
- Aggiunge le righe del CSV alla collezione
- Usare per accumulare dati incrementali

```
Collezione prima: [A, B, C]  (3 documenti esistenti)
CSV contiene:     [D, E]     (2 righe nuove)
Collezione dopo:  [A, B, C, D, E]  (5 documenti totali)
```

### IU — Insert Update / Upsert (aggiorna o inserisce)

- Per ogni riga del CSV, cerca un documento con la stessa **chiave**
- Se lo trova → **aggiorna** i campi
- Se non lo trova → **inserisce** come nuovo documento
- Richiede il parametro aggiuntivo con il nome della colonna chiave

```
Collezione prima: [{id:001, nome:"Mario", eta:35}]
CSV contiene:     [{id:001, nome:"Mario", eta:36}, {id:002, nome:"Laura"}]
Collezione dopo:  [{id:001, nome:"Mario", eta:36}, {id:002, nome:"Laura"}]
                   ^--- aggiornato                  ^--- inserito nuovo
```

---

## 7. Il timestamp nel nome del file

Dopo ogni caricamento **riuscito**, il file originale viene rinominato automaticamente  
aggiungendo la data e l'ora nel formato `yyyyMMddHHmmss`:

```
dati.csv  →  dati_loaded_20260421183655.csv
```

Questo meccanismo garantisce:
- **Idempotenza**: alla successiva esecuzione il programma non trova `dati.csv` → registra `FILE_NOT_FOUND` → non duplica i dati
- **Storico**: ogni file caricato viene conservato con il timestamp di caricamento
- **Sicurezza**: non si perde mai il CSV originale

Se il file ha già un nome con timestamp (es. da un sistema esterno), il comportamento è identico:  
il programma aggiunge `_loaded_TIMESTAMP` prima dell'estensione `.csv`.

---

## 8. I log su MongoDB

Ogni esecuzione scrive un documento nella collezione **`C_DR_APP_LOG_FILE_CSV`**.

### Struttura del documento di log

```json
{
  "fileName":  "dati.csv",
  "timestamp": "2026-04-21T18:36:55.123",
  "status":    "SUCCESS",
  "records":   5,
  "type":      "TI",
  "message":   "..."   // presente solo in caso di errore o FILE_NOT_FOUND
}
```

### Valori possibili di `status`

| status | Significato |
|--------|-------------|
| `SUCCESS` | Caricamento completato con successo |
| `FILE_NOT_FOUND` | Il file CSV non esisteva nella cartella |
| `EMPTY_FILE` | Il file esisteva ma non conteneva righe di dati |
| `ERROR` | Errore imprevisto (dettaglio nel campo `message`) |

---

## 9. File di test inclusi

Nella root del progetto sono inclusi due file CSV di esempio.

### `dati.csv` — separatore `,`, nessun enclosure

```
id_chiave,nome,cognome,eta,citta,email
001,Mario,Rossi,35,Roma,mario.rossi@example.com
002,Laura,Bianchi,28,Milano,laura.bianchi@example.com
003,Giuseppe,Verdi,42,Napoli,giuseppe.verdi@example.com
004,Anna,Ferrari,31,Torino,anna.ferrari@example.com
005,Marco,Esposito,25,Bologna,marco.esposito@example.com
```

**Comando di test:**
```bash
java -jar target/csv-mongo-loader-1.0-SNAPSHOT.jar \
  mongodb://localhost:27017 test_db test_coll dati.csv , NONE TI
```

### `dati_punto_virgola.csv` — separatore `;`, enclosure `"`

```
id_chiave;nome;cognome;eta;citta;email
"001";"Mario";"Rossi";"35";"Roma";"mario.rossi@example.com"
...
```

**Comando di test:**
```bash
java -jar target/csv-mongo-loader-1.0-SNAPSHOT.jar \
  mongodb://localhost:27017 test_db test_coll dati_punto_virgola.csv ';' '"' IU id_chiave
```

> **Attenzione:** dopo il primo caricamento il file viene rinominato (es. `dati_loaded_TIMESTAMP.csv`).  
> Per rieseguire il test, rinominarlo nuovamente in `dati.csv`:
> ```bash
> cp dati_loaded_*.csv dati.csv
> ```

---

## 10. Cosa serve all'orchestratore

L'orchestratore deve solamente:

1. **Depositare** il file CSV nella cartella concordata
2. **Avviare** il programma Java con i parametri corretti
3. **Leggere l'exit code** (0 = OK, 1 = errore di configurazione)

### File da distribuire

L'unico file necessario in produzione è il **fat-jar**:

```
target/csv-mongo-loader-1.0-SNAPSHOT.jar
```

Contiene già al suo interno il driver MongoDB e tutte le dipendenze.  
**Non serve installare nulla oltre a Java 11+** sul server dove gira l'orchestratore.

### Esempio di chiamata dall'orchestratore

```bash
java -jar /opt/loader/csv-mongo-loader-1.0-SNAPSHOT.jar \
  mongodb://mongo-server:27017 \
  produzione \
  anagrafica \
  /data/in/anagrafica.csv \
  , \
  NONE \
  TI
```

### Parametri da rendere configurabili nell'orchestratore

| Parametro | Tipicamente variabile tra ambienti |
|-----------|------------------------------------|
| mongoUri | diverso tra dev / test / prod |
| database | diverso tra ambienti |
| collezione | dipende dal tipo di file |
| percorsoCSV | cartella di input dell'orchestratore |
| separatore | dipende dal sistema sorgente |
| enclosure | dipende dal sistema sorgente |
| modo | TI / IA / IU scelto per ogni flusso |
| chiaveUpsert | solo per IU, nome della colonna chiave |

---

## 11. Verifica visiva con Mongo Express

Se hai avviato il Docker Compose incluso, puoi vedere i dati nel browser:

**http://localhost:8081**

Naviga:
```
Database: test_db
  ├── test_coll               → i documenti caricati dal CSV
  └── C_DR_APP_LOG_FILE_CSV   → tutti i log di esecuzione
```

---

## Struttura del progetto

```
csv-mongo-loader/
├── pom.xml                                    # Dipendenze Maven + configurazione build
├── docker-compose.yml                         # MongoDB + Mongo Express via Docker
├── dati.csv                                   # File di test (virgola, no enclosure)
├── dati_punto_virgola.csv                     # File di test (punto e virgola + enclosure ")
└── src/
    └── main/
        └── java/
            └── com/example/
                └── MongoCSVLoader.java        # Codice sorgente principale
```

---

## Dipendenza Maven

```xml
<dependency>
    <groupId>org.mongodb</groupId>
    <artifactId>mongodb-driver-sync</artifactId>
    <version>4.11.1</version>
</dependency>
```
