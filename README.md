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
| credenziali MongoDB | — | `Secret` K8s, referenziato in `env` |

> I parametri `mongoUri`, `database`, `collezione`, `csvPath` ecc. **non** vanno nelle
> variabili d'ambiente — vengono passati nel body della POST dall'orchestratore ad ogni chiamata.

### Autenticazione MongoDB: locale vs collaudo/produzione

Il campo `mongoUri` cambia in base all'ambiente perche' MongoDB puo' girare con o senza autenticazione.

**In locale (Docker Compose)** — MongoDB e' avviato senza credenziali, quindi l'URI non le richiede:
```json
"mongoUri": "mongodb://localhost:27017"
```

Questo funziona perche' il `docker-compose.yml` avvia MongoDB **senza** `MONGO_INITDB_ROOT_USERNAME`
e `MONGO_INITDB_ROOT_PASSWORD`, lasciandolo aperto.

**In collaudo / produzione K8s** — MongoDB ha autenticazione abilitata, le credenziali vanno nell'URI:
```json
"mongoUri": "mongodb://utente_collaudo:password_segreta@mongo-svc:27017/reportistica_sanita"
```

Il servizio csv-mongo-loader non conosce la password a priori — la riceve nell'URI
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
│   ├── CsvMongoLoaderApplication.java   # Entry point Spring Boot
│   ├── LoadController.java              # @RestController POST /api/load
│   ├── LoadRequest.java                 # DTO body della richiesta
│   ├── LoadResponse.java                # DTO risposta JSON
│   └── MongoCSVLoader.java              # @Service logica di business
├── src/main/resources/
│   └── application.properties           # Porta 8080, configurazione
├── Dockerfile                           # Build immagine Docker
├── docker-compose.yml                   # MongoDB + Mongo Express (sviluppo locale)
├── pom.xml                              # Spring Boot 2.7.18 + springdoc
└── README.md
```
