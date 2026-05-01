# Piano di Test — csv-mongo-loader REST API

Data: 01/05/2026  
Versione: 1.0-SNAPSHOT  
Endpoint base: `http://localhost:8080`

---

## Prerequisiti

- MongoDB attivo su `localhost:27017` (via Docker Compose)
- Servizio avviato: `java -jar target/csv-mongo-loader-1.0-SNAPSHOT.jar`
- File di test presente: `/home/giovanni/csv-mongo-loader/dati.csv`

---

## Casi di test

| # | Caso | Input | Risposta attesa | Risultato |
|---|------|-------|-----------------|:---------:|
| T1 | **FILE_NOT_FOUND** — file inesistente | `csvPath` punta a un file che non esiste | HTTP 200, `status: FILE_NOT_FOUND`, log scritto su MongoDB | ⬜ |
| T2 | **TI — Truncate Insert** | `modo: TI`, file CSV valido (5 righe) | HTTP 200, `status: SUCCESS`, `records: 5`, collezione svuotata e ricaricata, file rinominato, vista `_RAW` creata | ⬜ |
| T3 | **IA — Insert Append** | `modo: IA`, stesso file CSV (5 righe, ricreato) | HTTP 200, `status: SUCCESS`, `records: 5`, totale documenti in DB = 10 (5 precedenti + 5 nuovi) | ⬜ |
| T4 | **IU — Insert Update (Upsert)** | `modo: IU`, `chiaveUpsert: nome`, file CSV con record già presenti | HTTP 200, `status: SUCCESS`, `records: 5`, nessun duplicato (upsert per chiave) | ⬜ |
| T5 | **Validazione: body incompleto** | Body JSON con un campo obbligatorio mancante (es. omettere `modo`) | HTTP 400, `status: ERROR`, messaggio con elenco campi obbligatori | ⬜ |
| T6 | **Validazione: modo non valido** | `modo: XX` (valore non previsto) | HTTP 400, `status: ERROR`, `message: Il campo modo deve essere TI, IA o IU` | ⬜ |
| T7 | **Validazione: IU senza chiaveUpsert** | `modo: IU`, campo `chiaveUpsert` assente | HTTP 400, `status: ERROR`, `message: Il campo chiaveUpsert e' obbligatorio per la modalita' IU` | ⬜ |

**Legenda:** ⬜ Da eseguire &nbsp;|&nbsp; ✅ Superato &nbsp;|&nbsp; ❌ Fallito

---

## Verifica Swagger UI

| # | Verifica | Risultato |
|---|----------|-----------|
| S1 | `GET http://localhost:8080/swagger-ui/index.html` risponde con pagina HTML | ⬜ |
| S2 | Endpoint `POST /api/load` visibile con descrizione e campi del body | ⬜ |
| S3 | Chiamata di test eseguibile direttamente dalla UI | ⬜ |

---

## Note

- Il log di ogni operazione è consultabile nella collezione `C_DR_APP_LOG_FILE_CSV` su MongoDB
- Dopo T2/T3/T4 il file CSV viene rinominato con suffisso `_loaded_yyyyMMddHHmmss.csv` — ricrearlo prima del test successivo
- La vista `<collezione>_RAW` viene creata/aggiornata dopo ogni caricamento riuscito
