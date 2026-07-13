# Piano di Test Completo — csv-mongo-loader REST API

> **Scopo di questo documento**: consentire a un nuovo agente (senza contesto pregresso)
> di ripetere **tutti** i test eseguiti sulla versione precedente del servizio, verificando
> che la nuova versione si comporti in modo identico.

---

## Contesto del progetto

- **Servizio**: Spring Boot REST API (Java 11, fat-JAR)
- **Endpoint**: `POST /api/load`
- **MongoDB**: driver sync 4.x, nessuna autenticazione in ambiente di test
- **Porta**: 8080
- **Repository**: https://github.com/dataappengineer/csv-mongo-loader
- **JAR prodotto da**: `mvn clean package` → `target/csv-mongo-loader-1.0-SNAPSHOT.jar`

---

## Prerequisiti prima di eseguire i test

### 1. Compilare il progetto

```bash
cd /home/giovanni/csv-mongo-loader
mvn clean package -DskipTests
```

### 2. Avviare MongoDB (Docker)

```bash
docker compose up -d
```

Verifica che i container siano attivi:

```bash
docker ps | grep csv_mongo
```

Devono risultare attivi:
- `csv_mongo` → MongoDB 7 su porta `27017`
- `csv_mongo_express` → Mongo Express su porta `8081`

### 3. Avviare il servizio Spring Boot

```bash
java -jar /home/giovanni/csv-mongo-loader/target/csv-mongo-loader-1.0-SNAPSHOT.jar > /tmp/app.log 2>&1 &
```

Attendi ~8 secondi, poi verifica:

```bash
ss -tlnp | grep 8080
```

Deve mostrare una riga con il processo Java in ascolto su `*:8080`.

### 4. Creare i file CSV di test

Questi file vengono **rinominati dal servizio** dopo ogni caricamento riuscito
(suffisso `_loaded_yyyyMMddHHmmss.csv`). Vanno ricreati prima di ogni test che li usa.

```bash
# CSV piccolo (5 righe, separatore virgola, nessun enclosure)
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF

# CSV con separatore punto e virgola e enclosure doppi apici
cat > /tmp/test_pv_quoted.csv << 'EOF'
id;descrizione;codice
1;"TRASLOCAZIONE (8;14), QUANT.-SUSC.TRATT.FARM.";ABC001
2;"ESAME NORMALE (senza puntovirgola)";ABC002
3;"TRASLOCAZIONE (9;22) CROMOSOMA PHILADELPHIA";ABC003
EOF

# CSV con separatore virgola e enclosure, con virgole nei valori
cat > /tmp/test_comma_quoted.csv << 'EOF'
id,descrizione,codice
1,"valore con virgola, qui dentro",C001
2,"valore normale",C002
3,"terzo, campo, con, virgole",C003
EOF

# CSV grande (50.000 righe) — generato con lo script Python
python3 /home/giovanni/csv-mongo-loader/gen_large_csv.py > /tmp/test_large.csv
wc -l /tmp/test_large.csv   # deve mostrare 50001 (header + 50000 righe)

# CSV per test IU composito (3 righe, duplicato intenzionale per verifica upsert)
cat > /tmp/test_composite.csv << 'EOF'
nome,cognome,eta,citta
Mario,Rossi,35,Roma
Lucia,Bianchi,28,Milano
Mario,Rossi,99,Napoli
EOF
```

---

## Variabili riutilizzate nei test

Tutte le chiamate usano:
- `mongoUri`: `mongodb://localhost:27017`
- `logCollezione`: `log_test`
- Database: `test_regression`

---

## T01 — TI con virgola, nessun enclosure

**Obiettivo**: caricamento base Truncate Insert, separatore virgola, nessun enclosure.

**Setup**: assicurarsi che `/tmp/test_small.csv` esista (5 righe).

```bash
curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t01",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_test"
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 5, "message": null}
```

**Verifica MongoDB**: collezione `t01` del database `test_regression` contiene 5 documenti.

**Verifica log**: un documento è stato inserito nella collezione `log_test`.

**Verifica rinomina file**: `/tmp/test_small.csv` non esiste più;
esiste un file `/tmp/test_small_loaded_<timestamp>.csv` oppure nella cartella del progetto un file con suffisso `_loaded_`.

---

## T02 — TI con batchSize piccolo (streaming)

**Obiettivo**: verifica che il parametro `batchSize` venga rispettato senza alterare il risultato.

**Setup**: ricreare `/tmp/test_small.csv`.

```bash
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t02",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_test",
    "batchSize":     2
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 5, "message": null}
```

**Verifica**: nonostante il batch da 2, tutti i 5 record sono stati inseriti.

---

## T03 — SHA-512 su colonne sensibili (colonneHash)

**Obiettivo**: le colonne `cognome` e `email` devono essere salvate hashate (SHA-512 = 128 caratteri esadecimali), non in chiaro.

**Setup**: ricreare `/tmp/test_small.csv`.

```bash
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t03",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_test",
    "colonneHash":   ["cognome", "email"]
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 5, "message": null}
```

**Verifica MongoDB** — primo documento della collezione `t03`:
```bash
# Accedere a Mongo Express su http://localhost:8081 oppure via mongosh:
# db.t03.findOne()
```
- `cognome` deve essere una stringa di **128 caratteri esadecimali** (non "Rossi")
- `email` deve essere una stringa di **128 caratteri esadecimali** (non "mario@example.com")
- `nome`, `id_chiave`, `eta`, `citta` devono essere in chiaro

---

## T04 — 50.000 righe, streaming, nessun OOM

**Obiettivo**: il servizio gestisce file grandi senza errori di memoria.

**Setup**: assicurarsi che `/tmp/test_large.csv` esista (50.001 righe).

```bash
python3 /home/giovanni/csv-mongo-loader/gen_large_csv.py > /tmp/test_large.csv

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t04",
    "csvPath":       "/tmp/test_large.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_test",
    "batchSize":     1000
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 50000, "message": null}
```

**Verifica**: `records` deve essere esattamente 50000. Nessun errore nel log del servizio.

---

## T05 — IU (upsert) con colonneHash

**Obiettivo**: la modalità upsert funziona correttamente anche con hashing attivo.

**Setup**: ricreare `/tmp/test_small.csv`.

```bash
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t05",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "IU",
    "logCollezione": "log_test",
    "chiaveUpsert":  ["id_chiave"],
    "colonneHash":   ["cognome", "email"]
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 5, "message": null}
```

---

## T06 — FILE_NOT_FOUND

**Obiettivo**: se il file CSV non esiste, il servizio risponde con `FILE_NOT_FOUND` (non un errore 500).

```bash
curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t06",
    "csvPath":       "/tmp/file_che_non_esiste.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_test"
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "FILE_NOT_FOUND", "records": 0, "message": "File non trovato: /tmp/file_che_non_esiste.csv"}
```

---

## T07 — Enclosure con virgole nei valori (RFC 4180)

**Obiettivo**: campi quotati che contengono virgole non devono essere spezzati.
Questo test verifica il fix del parser RFC 4180.

**Setup**: creare `/tmp/test_comma_quoted.csv`.

```bash
cat > /tmp/test_comma_quoted.csv << 'EOF'
id,descrizione,codice
1,"valore con virgola, qui dentro",C001
2,"valore normale",C002
3,"terzo, campo, con, virgole",C003
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t07",
    "csvPath":       "/tmp/test_comma_quoted.csv",
    "separatore":    ",",
    "enclosure":     "\"",
    "modo":          "TI",
    "logCollezione": "log_test"
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 3, "message": null}
```

**Verifica MongoDB** — collezione `t07`:
- documento 1: `descrizione` = `"valore con virgola, qui dentro"` (virgola preservata, non spezzata in due campi)
- documento 3: `descrizione` = `"terzo, campo, con, virgole"`

---

## T08 — Enclosure con punto e virgola nei valori (caso Claudio, RFC 4180)

**Obiettivo**: campi quotati che contengono `;` non devono essere spezzati
quando il separatore è `;`. Questo è il caso clinico originale di Claudio.

**Setup**: creare `/tmp/test_pv_quoted.csv`.

```bash
cat > /tmp/test_pv_quoted.csv << 'EOF'
id;descrizione;codice
1;"TRASLOCAZIONE (8;14), QUANT.-SUSC.TRATT.FARM.";ABC001
2;"ESAME NORMALE (senza puntovirgola)";ABC002
3;"TRASLOCAZIONE (9;22) CROMOSOMA PHILADELPHIA";ABC003
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t08",
    "csvPath":       "/tmp/test_pv_quoted.csv",
    "separatore":    ";",
    "enclosure":     "\"",
    "modo":          "TI",
    "logCollezione": "log_test"
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 3, "message": null}
```

**Verifica MongoDB** — collezione `t08`:
- documento 1: `descrizione` = `"TRASLOCAZIONE (8;14), QUANT.-SUSC.TRATT.FARM."` (il `;14)` non spezza il campo)
- documento 3: `descrizione` = `"TRASLOCAZIONE (9;22) CROMOSOMA PHILADELPHIA"`

---

## T09 — nomeVista esplicito

**Obiettivo**: se `nomeVista` è valorizzato, viene creata la vista MongoDB con quel nome
(non il default `<collezione>_RAW`).

**Setup**: ricreare `/tmp/test_small.csv`.

```bash
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t09",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_test",
    "nomeVista":     "vista_personalizzata"
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 5, "message": null}
```

**Verifica MongoDB** (Mongo Express su http://localhost:8081 oppure mongosh):
- Database `test_regression`: deve esistere la **vista** `vista_personalizzata`
- **Non** deve esistere una vista `t09_RAW`

---

## T10 — nomeVista assente → default `<collezione>_RAW`

**Obiettivo**: se `nomeVista` non è specificato, il servizio crea automaticamente la vista
con nome `<collezione>_RAW`.

**Setup**: ricreare `/tmp/test_small.csv`.

```bash
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t10",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_test"
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 5, "message": null}
```

**Verifica MongoDB**:
- Deve esistere la **vista** `t10_RAW` nel database `test_regression`

---

## T-IU-SINGLE — IU chiave singola (array con un elemento)

**Obiettivo**: la modalità IU con `chiaveUpsert` come array a un elemento funziona correttamente.

**Setup**: ricreare `/tmp/test_small.csv`.

```bash
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t_iu_single",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "IU",
    "logCollezione": "log_test",
    "chiaveUpsert":  ["id_chiave"]
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 5, "message": null}
```

**Verifica MongoDB**: 5 documenti nella collezione, nessun duplicato.

---

## T-IU-COMPOSITE — IU chiave composta (array con più elementi)

**Obiettivo**: la chiave composta su più campi funziona — il match avviene solo se
**tutti** i campi della chiave coincidono. Nel CSV ci sono due righe con `nome=Mario, cognome=Rossi`:
la seconda deve aggiornare la prima (non inserire un duplicato), quindi il risultato finale
deve avere **3 documenti** (non 4).

**Setup**: creare `/tmp/test_composite.csv`.

```bash
cat > /tmp/test_composite.csv << 'EOF'
nome,cognome,eta,citta
Mario,Rossi,35,Roma
Lucia,Bianchi,28,Milano
Mario,Rossi,99,Napoli
EOF

curl -s -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t_iu_composite",
    "csvPath":       "/tmp/test_composite.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "IU",
    "logCollezione": "log_test",
    "chiaveUpsert":  ["nome", "cognome"]
  }'
```

**Risposta attesa** (HTTP 200):
```json
{"status": "SUCCESS", "records": 3, "message": null}
```

**Verifica MongoDB** — collezione `t_iu_composite`:
- Numero documenti: **3** (non 4 — la terza riga ha fatto upsert sulla prima)
- Documento `Mario Rossi`: `eta` = `"99"`, `citta` = `"Napoli"` (valori aggiornati dall'ultima riga)

---

## T-IU-NO-KEY — IU senza chiaveUpsert → 400

**Obiettivo**: se `modo=IU` ma `chiaveUpsert` è assente, il servizio risponde 400.

```bash
curl -s -w '\nHTTP_STATUS:%{http_code}' \
  -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t_iu_nokey",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "IU",
    "logCollezione": "log_test"
  }'
```

**Risposta attesa** (HTTP **400**):
```json
{"status": "ERROR", "records": 0, "message": "Il campo chiaveUpsert e' obbligatorio per la modalita' IU"}
```

---

## T-IU-EMPTY-KEY — IU con chiaveUpsert=[] → 400

**Obiettivo**: array vuoto è equivalente a chiave assente — deve essere rifiutato con 400.

```bash
curl -s -w '\nHTTP_STATUS:%{http_code}' \
  -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t_iu_emptykey",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "IU",
    "logCollezione": "log_test",
    "chiaveUpsert":  []
  }'
```

**Risposta attesa** (HTTP **400**):
```json
{"status": "ERROR", "records": 0, "message": "Il campo chiaveUpsert e' obbligatorio per la modalita' IU"}
```

---

## T-VAL-01 — Campi obbligatori mancanti → 400

**Obiettivo**: se manca anche un solo campo obbligatorio, il servizio risponde 400
con l'elenco dei campi richiesti.

```bash
curl -s -w '\nHTTP_STATUS:%{http_code}' \
  -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri": "mongodb://localhost:27017",
    "database": "test_regression"
  }'
```

**Risposta attesa** (HTTP **400**):
```json
{
  "status": "ERROR",
  "records": 0,
  "message": "Tutti i campi obbligatori devono essere valorizzati: mongoUri, database, collezione, csvPath, separatore, enclosure, modo, logCollezione"
}
```

---

## T-VAL-02 — modo non valido → 400

**Obiettivo**: un valore di `modo` non riconosciuto restituisce 400.

```bash
curl -s -w '\nHTTP_STATUS:%{http_code}' \
  -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_regression",
    "collezione":    "t_val02",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "XX",
    "logCollezione": "log_test"
  }'
```

**Risposta attesa** (HTTP **400**):
```json
{"status": "ERROR", "records": 0, "message": "Il campo modo deve essere TI, IA o IU"}
```

---

## T-ASYNC-01 — Risposta 202 immediata (modalità asincrona)

**Obiettivo**: se `callbackUrl` è presente, il servizio risponde immediatamente con **202**
(non aspetta la fine del caricamento) e il body contiene `jobId` e `status: ACCEPTED`.

**Setup**: avviare il server mock callback su porta 9999 **prima** di lanciare il test:

```bash
python3 /home/giovanni/csv-mongo-loader/mock_callback.py &
# Verifica che sia in ascolto:
ss -tlnp | grep 9999
```

Ricreare il CSV:

```bash
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF
```

```bash
curl -s -w '\nHTTP_STATUS:%{http_code}' \
  -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":         "mongodb://localhost:27017",
    "database":         "test_async",
    "collezione":       "t_async01",
    "csvPath":          "/tmp/test_small.csv",
    "separatore":       ",",
    "enclosure":        "NONE",
    "modo":             "TI",
    "logCollezione":    "log_async",
    "jobId":            "job-test-001",
    "callbackUrl":      "http://localhost:9999/callback",
    "callbackUser":     "donato",
    "callbackPassword": "secretpwd123"
  }'
```

**Risposta HTTP attesa** (HTTP **202**):
```json
{
  "jobId":   "job-test-001",
  "status":  "ACCEPTED",
  "records": 0,
  "message": "Elaborazione asincrona avviata"
}
```

**Verifica callback** — entro pochi secondi il server mock deve stampare sul terminale:
```
--- CALLBACK RICEVUTO ---
Authorization: Basic ZG9uYXRvOnNlY3JldHB3ZDEyMw==
Body: {"jobId":"job-test-001","status":"SUCCESS","records":5,"message":""}
-------------------------
```

Punti di verifica:
1. `HTTP_STATUS:202` nella risposta curl ✓
2. Il callback viene ricevuto (non solo la 202) ✓
3. `Authorization` decodificato = `donato:secretpwd123` ✓
4. `records: 5` nel payload del callback ✓

---

## T-ASYNC-02 — callbackUrl senza credenziali → 400

**Obiettivo**: se `callbackUrl` è valorizzato ma `callbackUser`/`callbackPassword` mancano,
il servizio rifiuta con 400 **senza avviare il thread**.

```bash
curl -s -w '\nHTTP_STATUS:%{http_code}' \
  -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_async",
    "collezione":    "t_async02",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_async",
    "callbackUrl":   "http://localhost:9999/callback"
  }'
```

**Risposta attesa** (HTTP **400**):
```json
{
  "status":  "ERROR",
  "records": 0,
  "message": "callbackUser e callbackPassword sono obbligatori quando callbackUrl e' valorizzato"
}
```

---

## T-ASYNC-03 — Path sincrono invariato (no callbackUrl → HTTP 200)

**Obiettivo**: in assenza di `callbackUrl`, il servizio torna al comportamento sincrono
originale (HTTP 200, non 202). La presenza dei nuovi campi nel codice non deve rompere
il path esistente.

**Setup**: ricreare `/tmp/test_small.csv`.

```bash
cat > /tmp/test_small.csv << 'EOF'
id_chiave,nome,cognome,eta,citta,email
1,Mario,Rossi,35,Roma,mario@example.com
2,Lucia,Bianchi,28,Milano,lucia@example.com
3,Carlo,Verdi,42,Napoli,carlo@example.com
4,Anna,Ferrari,31,Torino,anna@example.com
5,Marco,Russo,55,Firenze,marco@example.com
EOF

curl -s -w '\nHTTP_STATUS:%{http_code}' \
  -X POST http://localhost:8080/api/load \
  -H 'Content-Type: application/json' \
  -d '{
    "mongoUri":      "mongodb://localhost:27017",
    "database":      "test_async",
    "collezione":    "t_async03_sync",
    "csvPath":       "/tmp/test_small.csv",
    "separatore":    ",",
    "enclosure":     "NONE",
    "modo":          "TI",
    "logCollezione": "log_async"
  }'
```

**Risposta attesa** (HTTP **200**, non 202):
```json
{"status": "SUCCESS", "records": 5, "message": null}
```

---

## Verifica Swagger UI

```bash
curl -s http://localhost:8080/swagger-ui/index.html | grep -o '<title>[^<]*</title>'
```

**Atteso**: `<title>Swagger UI</title>`

```bash
curl -s http://localhost:8080/v3/api-docs | python3 -m json.tool | grep '"/api/load"'
```

**Atteso**: una riga contenente `"/api/load"`

---

## Riepilogo esiti attesi

| ID | Scenario | HTTP | `status` | `records` |
|----|----------|:----:|----------|:---------:|
| T01 | TI virgola NONE | 200 | SUCCESS | 5 |
| T02 | TI batchSize=2 | 200 | SUCCESS | 5 |
| T03 | colonneHash SHA-512 | 200 | SUCCESS | 5 |
| T04 | 50k righe streaming | 200 | SUCCESS | 50000 |
| T05 | IU + colonneHash | 200 | SUCCESS | 5 |
| T06 | FILE_NOT_FOUND | 200 | FILE_NOT_FOUND | 0 |
| T07 | Virgola + enclosure (RFC 4180) | 200 | SUCCESS | 3 |
| T08 | PuntoVirgola + enclosure (RFC 4180) | 200 | SUCCESS | 3 |
| T09 | nomeVista esplicito | 200 | SUCCESS | 5 |
| T10 | nomeVista default _RAW | 200 | SUCCESS | 5 |
| T-IU-SINGLE | IU chiave singola `["id_chiave"]` | 200 | SUCCESS | 5 |
| T-IU-COMPOSITE | IU chiave composta `["nome","cognome"]` | 200 | SUCCESS | 3 |
| T-IU-NO-KEY | IU senza chiaveUpsert | **400** | ERROR | 0 |
| T-IU-EMPTY-KEY | IU con chiaveUpsert=[] | **400** | ERROR | 0 |
| T-VAL-01 | Campi obbligatori mancanti | **400** | ERROR | 0 |
| T-VAL-02 | modo non valido (XX) | **400** | ERROR | 0 |
| T-ASYNC-01 | Async: 202 + callback SUCCESS + Basic Auth | **202** | ACCEPTED | 0 |
| T-ASYNC-02 | callbackUrl senza credenziali | **400** | ERROR | 0 |
| T-ASYNC-03 | No callbackUrl → sincrono invariato | 200 | SUCCESS | 5 |

---

## Note operative importanti

### Il servizio rinomina i file dopo il caricamento
Dopo ogni caricamento riuscito (`SUCCESS`) il file CSV viene rinominato con il suffisso
`_loaded_yyyyMMddHHmmss`. Questo significa che lo stesso `csvPath` **non può essere
riutilizzato** senza ricreare il file. I test che dipendono dallo stesso file CSV
devono ricreare il file prima di essere eseguiti.

### chiaveUpsert è un array (non una stringa)
A partire dalla versione con il fix di Claudio, `chiaveUpsert` è un **array di stringhe**,
non una stringa singola. Anche per chiave singola va passato come array:
- ✅ `"chiaveUpsert": ["id_record"]`
- ❌ `"chiaveUpsert": "id_record"` (non valido)

### Differenza tra jobId nella response
- In modalità **sincrona** (no callbackUrl): il campo `jobId` nella risposta è `null`
- In modalità **asincrona** (callbackUrl presente): la risposta 202 include `"jobId": "<valore passato>"`

### Mock server per test asincroni
Il file `mock_callback.py` nella root del progetto avvia un server HTTP sulla porta 9999
che stampa su stdout l'header `Authorization` e il body ricevuto. Va avviato **prima** di
T-ASYNC-01 e lasciato in esecuzione.

### Verifica Basic Auth (T-ASYNC-01)
L'header `Authorization: Basic ZG9uYXRvOnNlY3JldHB3ZDEyMw==` può essere decodificato con:
```bash
echo "ZG9uYXRvOnNlY3JldHB3ZDEyMw==" | base64 -d
# Output: donato:secretpwd123
```
