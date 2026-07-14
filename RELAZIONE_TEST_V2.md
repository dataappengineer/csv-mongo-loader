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
