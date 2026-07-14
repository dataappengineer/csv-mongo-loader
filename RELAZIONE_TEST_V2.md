# Relazione Tecnica di Validazione - csv-mongo-loader v2.0

Questo documento descrive il processo di validazione e i risultati dei test eseguiti sulla nuova versione del caricatore CSV. La strategia di test è stata progettata per garantire non solo la correttezza della logica di trasformazione, ma anche l'integrità dei dati salvati su MongoDB 8.0.

---

## Indice Cliccabile
1. [Strategia di Testing: Come abbiamo validato il software](#strategia-di-testing-come-abbiamo-validato-il-software)
2. [Riepilogo Risultati](#riepilogo-risultati)
3. [Dettaglio Aree Funzionali](#dettaglio-aree-funzionali)
4. [Esempi di Test Reali (E2E)](#esempi-di-test-reali-e2e)
5. [Controlli di Qualità e Robustezza](#controlli-di-qualità-e-robustezza)

---

## Strategia di Testing: Come abbiamo validato il software

Per assicurare la massima affidabilità, abbiamo adottato un approccio a due livelli:

### Livello 1: Test Unitari e di Logica (113 test)
Questi test verificano la "mente" del software in isolamento. Non richiedono un database attivo e sono eseguiti istantaneamente tramite:
```bash
mvn test
```
**Cosa garantiscono:** Che ogni singolo trasformatore (Date, Numeri, Boolean) funzioni esattamente come da specifiche, gestendo correttamente casi limite come date ambigue o caratteri speciali.

### Livello 2: Test di Integrazione End-to-End (6 test)
Questi test verificano il "corpo" del software nel mondo reale. Utilizziamo Docker per sollevare un'istanza di MongoDB 8.0 identica a quella di produzione:
```bash
docker compose up -d      # Avvio database reale
mvn test                  # Esecuzione test su DB
docker compose down       # Pulizia ambiente
```
**Cosa garantiscono:** Che la connessione al database sia stabile e che i dati vengano scritti con i corretti tipi BSON (es. le date come `ISODate` e non come semplici stringhe).

---

## Riepilogo Risultati

| Stato Totale | Test Eseguiti | Passati | Falliti | Successo % |
|:---:|:---:|:---:|:---:|:---:|
| **VERIFICATO** | 119 | 119 | 0 | 100% |

---

## Dettaglio Aree Funzionali

### 1. Parsing dell'Header a due righe
Abbiamo validato il nuovo sistema di metadati. Il software ora riconosce correttamente la **Riga 1** (Tipi e Flag) e la **Riga 2** (Nomi campi).
*   **Verificato:** Corretto accoppiamento tipo-colonna.
*   **Verificato:** Gestione dei flag multipli (es. `S;PK;HASH`).

### 2. Trasformatori di Tipo
Ogni tipo di dato ha un motore di conversione dedicato:
*   **Numeri (`I`, `DD`):** Gestione di interi Long e decimali a doppia precisione.
*   **Date (`D`, `DT`):** Supporto a formati multipli e gestione dell'anno a 2 cifre (pivot 2000-2099).
*   **Testi (`S`):** Pulizia automatica, normalizzazione accenti e mascheramento (Masking/Hashing).

### 3. Sostegno e Privacy
*   **Hashing:** Validata la trasformazione SHA-512 per i campi sensibili.
*   **Masking:** Verifica della protezione parziale dei dati (es. mostrare solo le ultime 4 cifre).

---

## Dettaglio dei Test Funzionali (Collaudo "Su Strada")

A differenza dei test automatici di Maven, queste prove sono state eseguite inviando richieste HTTP reali ad un'istanza dell'applicazione attiva, simulando esattamente ciò che farà l'orchestratore o l'utente finale.

### Scenario 1: Caricamento TI (Truncate Insert) con Hashing e PK
**Obiettivo:** Verificare che il sistema svuoti la collezione, inserisca nuovi dati e applichi l'anonimizzazione dove richiesto.

*   **File Test:** `test_ti.csv` (Header: `S;PK, S;HASH, DD`)
*   **Richiesta API:**
    ```bash
    curl -X POST http://localhost:8080/api/load \
      -d '{
        "mongoUri": "mongodb://localhost:27017",
        "database": "client_db",
        "collezione": "utenti",
        "csvPath": "/tmp/test_ti.csv",
        "modo": "TI"
      }'
    ```
*   **Esito Verificato:** 
    *   La collezione è stata svuotata prima dell'inserimento.
    *   Il campo `S;HASH` nel database appare come una stringa SHA-512 di 128 caratteri (irreversibile).
    *   Il campo tecnico `T` è presente e identico per tutti i record.

### Scenario 2: Upsert (IU) con gestione Duplicati e Scarti
**Obiettivo:** Gestire un file "sporco" dove ci sono record duplicati internamente e record con tipi errati.

*   **File Test:** `test_iu.csv` (Contiene un record con PK duplicata e uno con una stringa al posto di un numero).
*   **Risposta Ricevuta:**
    ```json
    {
      "status": "SUCCESS",
      "recordsRead": 10,
      "recordsInserted": 7,
      "recordsUpdated": 1,
      "recordsSkipped": 1,
      "recordsDuplicati": 1
    }
    ```
*   **Esito Verificato:** 
    *   Il sistema non si è bloccato.
    *   Il record duplicato è stato ignorato correttamente (vince la prima occorrenza).
    *   Il record con "tipo errato" è stato scartato e il dettaglio dell'errore è apparso nella lista `errors`.

### Scenario 3: Transizione Anno a 2 Cifre (Pivot 2000-2099)
**Obiettivo:** Assicurarsi che le date con anno abbreviato vengano interpretate correttamente nel secolo attuale.

*   **Input CSV:** `05/06/24` e `12/12/99`.
*   **Esito Verificato su MongoDB:**
    *   `05/06/24` -> `2024-06-05T00:00:00Z`
    *   `12/12/99` -> `2099-12-12T00:00:00Z`
*   **Nota:** Questo garantisce la compatibilità con i sistemi legacy che esportano date in formato breve.

### Scenario 4: Modalità Asincrona (Callback Post-Caricamento)
**Obiettivo:** Testare il flusso per file di grandi dimensioni che richiedono più di 30 secondi.

*   **Flusso di Test:**
    1. Inviata richiesta con `callbackUrl` e credenziali Basic Auth.
    2. Ricevuto immediatamente HTTP `202 ACCEPTED` con un `jobId`.
    3. Monitorato il server di callback esterno.
*   **Esito Verificato:**
    *   Al termine del caricamento, il server di callback ha ricevuto una POST contenente lo stesso JSON di report della modalità sincrona, permettendo all'orchestratore di chiudere il task.

---

## Esempi di Test Reali (E2E)

Durante la fase di test "fumo" (smoke test), sono state simulate chiamate API complete. Ecco un esempio di un'operazione di **Upsert (IU)** con chiave composta:

**Comando di test:**
```bash
curl -X POST http://localhost:8080/api/load \
  -H "Content-Type: application/json" \
  -d '{
    "mongoUri": "mongodb://localhost:27017",
    "database": "test_db",
    "collezione": "anagrafica",
    "csvPath": "/percorso/dati_v2.csv",
    "modo": "IU",
    "chiaveUpsert": ["codice", "anno"]
  }'
```

**Verifica sul Database:**
Dopo l'esecuzione, il test interroga MongoDB per assicurarsi che:
1. Se il record esisteva, il campo tecnico `T` (timestamp) sia stato aggiornato.
2. Se il record era nuovo, sia stato inserito un unico documento senza duplicati.
3. Il file CSV sia stato rinominato in `_loaded_...csv` per prevenirne il ricaricamento accidentale.

---

## Controlli di Qualità e Robustezza

Il software è stato messo alla prova con scenari di errore comuni:
*   **Campi Obbligatori Vuoti:** Il record viene scartato chirurgicamente e segnalato nel report finale, senza bloccare il caricamento delle altre righe.
*   **PK Mancante:** Se il file non definisce una chiave primaria, il sistema risponde con `status: ERROR` e non tocca il database, garantendo l'integrità dei dati.
*   **Normalizzazione Unicode:** Abbiamo testato il caricamento di testi con accenti complessi, verificando che la normalizzazione **NFC** mantenga la leggibilità dei dati su diverse piattaforme.

---
*Relazione generata il 14/07/2026 dal team di sviluppo.*
