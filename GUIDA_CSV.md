# Guida alla compilazione del file CSV

Guida operativa per chi **produce** i file CSV da caricare con il connettore.
È la "bussola": regole, tabelle ed esempi pronti all'uso.

---

## 1. Struttura obbligatoria: 3 parti

Il file ha **due righe di intestazione + i dati**:

| Riga | Contenuto | Esempio |
|------|-----------|---------|
| **1** | Tipo di ogni campo + flag opzionali | `I;PK,S;HASH,S,D,B,DD` |
| **2** | Nomi dei campi in MongoDB | `id,codice_fiscale,nome,data,attivo,importo` |
| **3+** | I dati, una riga per record | `1,RSSMRA80A01H501U,mario rossi,9/7/2026,SI,2500.50` |

Regole di base:
- Le tre parti usano lo **stesso separatore di colonna** (quello dichiarato nella chiamata: `,` oppure TAB ecc.).
- La riga 1 e la riga 2 devono avere lo **stesso numero di colonne**.
- Il file deve essere **UTF-8**. L'eventuale BOM iniziale viene gestito automaticamente.

---

## 2. Tipi di campo (riga 1)

| Codice | Tipo | Cosa accetta | Come viene salvato |
|:---:|------|--------------|--------------------|
| `I` | Integer | solo cifre con segno, **senza** decimali o separatore migliaia | intero (Int64/Long) |
| `DD` | Double | interi/decimali, con `.` o `,` come separatore decimale | numero (double) |
| `S` | String | qualsiasi testo | stringa (normalizzata) |
| `D` | Date | default `gg/mm/aaaa`; tollerati cifra singola (`g/m/aaaa`), **anno a 2 cifre** `gg/mm/aa` (pivot 2000-2099) e ISO `aaaa-mm-gg` | data (ISODate) |
| `DT` | DateTime | default `gg/mm/aaaa hh:mm:ss`; come `D` + ora (anche `aaaa-mm-ggThh:mm:ss[Z]`); spazi multipli tollerati | data-ora UTC (ISODate) |
| `B` | Boolean | vedi valori sotto | `true`/`false` |

> `PK` **non** è un tipo: è un flag che si combina con un tipo reale (es. `I;PK`, `S;PK`). Vedi sezione 3.

**Valori Boolean accettati** (maiuscole/minuscole indifferenti):
- Vero: `SI`, `S`, `TRUE`, `1`, `Y`, `YES`, `VRAI`, `V`
- Falso: `NO`, `N`, `FALSE`, `0`, `FAUX`, `F`

---

## 3. Flag opzionali (riga 1)

Si aggiungono al tipo separandoli con il carattere **`;`** (punto e virgola): es. `S;HASH;KEEP_CASE`.

| Flag | Su quali tipi | Effetto |
|------|:---:|---------|
| `PK` | I, S, D, DT, DD | Campo chiave primaria. **Almeno un `;PK` è obbligatorio in ogni file.** Più `;PK` = chiave composta. Le colonne `S;PK` **preservano il case** originale (la chiave resta identica alla sorgente) |
| `HASH` | S | Anonimizza con SHA-512 (irreversibile) |
| `KEEP_CASE` | S | Non converte in maiuscolo (preserva il case originale) |
| `NO_CLEANUP` | S | Non rimuove i caratteri speciali (per email, path, ecc.) |
| `MASK:N` | S | Mostra solo gli **ultimi N** caratteri, il resto `*` |
| `MASK:FULL` | S | Maschera tutto con `*` |
| `MASK:FIRST` | S | Mostra solo il primo carattere |
| `TRUNCATE:N` | S | Taglia a N caratteri |

> ⚠️ Il delimitatore tipo/flag è `;`. Di conseguenza il **separatore di colonna del CSV non può essere `;`** quando si usano i flag: usare `,` o TAB.
> L'argomento di `MASK`/`TRUNCATE` usa `:` (es. `MASK:4`).

### Combinazioni valide / non valide

| Dichiarazione | Valida? | Perché |
|---------------|:---:|--------|
| `I;PK` | ✅ | intero chiave |
| `S;HASH` | ✅ | stringa anonimizzata |
| `S;KEEP_CASE;HASH` | ✅ | più flag sulla stessa colonna S |
| `S;MASK:4;HASH` | ✅ | prima maschera, poi hash del valore mascherato |
| `D;PK` | ✅ | data chiave |
| `I;HASH` | ❌ | HASH solo su S |
| `D;MASK:4` | ❌ | MASK solo su S |
| `B;PK` | ❌ | il boolean non può essere chiave |
| `S;ENCRYPT` | ❌ | riservato, non implementato |

---

## 4. Trasformazioni automatiche (sempre applicate)

Non serve dichiararle: valgono per tutti i valori.

| Ambito | Trasformazione |
|--------|----------------|
| Tutti | trim, spazi multipli → singolo spazio, UTF-8, strip BOM, normalizzazione Unicode NFC (accenti **preservati**) |
| `S` | MAIUSCOLO (salvo `KEEP_CASE` **o colonna `PK`**) + rimozione caratteri speciali (salvo `NO_CLEANUP`) |
| `I` | solo cifre con segno; **nessun** decimale o separatore delle migliaia |
| `DD` | virgola → punto; **nessun separatore delle migliaia** |
| `D`/`DT` | cifra singola/doppia e spazi multipli tollerati; validazione stretta: date/ore inesistenti vengono **scartate** |
| `B` | riconoscimento dei valori vero/falso sopra elencati |

Ordine dei flag su S: `TRUNCATE` → `MASK` → `HASH`.

---

## 5. Campo tecnico di timestamp (delta)

A ogni record caricato il connettore aggiunge in automatico un **campo tecnico di timestamp** con l'istante del caricamento:

- è **uguale per tutti i record** dello stesso caricamento (calcolato una sola volta);
- serve per il **controllo dei delta** successivi (es. "tutti i record caricati dopo X").

Non va dichiarato nel CSV: viene aggiunto dal servizio. Nome e formato sono configurabili a startup (`application.properties`):

| Property | Default | Valori |
|----------|---------|--------|
| `csv.load-timestamp-field` | `T` | nome del campo aggiunto |
| `csv.load-timestamp-format` | `epoch` | `epoch` = long (millisecondi da epoch) · `date` = BSON ISODate · `iso` = stringa ISO-8601 |

> ⚠️ Nessuna colonna del CSV può chiamarsi come il campo tecnico (default `T`): in tal caso il caricamento fallisce con `ERROR`. Rinominare la colonna o cambiare `csv.load-timestamp-field`.

---

## 6. Regole di validazione (cosa fa scartare un record)

| Situazione | Conseguenza |
|------------|-------------|
| Cella **vuota** (tutti i campi sono obbligatori) | record scartato |
| Valore non coerente col tipo (es. `abc` per `I`, `99/99/2026` per `D`) | record scartato |
| **PK duplicata** nel file (stessa chiave su più righe) | scartata dalla 2ª occorrenza in poi (vince la prima) |
| Nessun `;PK` dichiarato nel file | **l'intero caricamento fallisce** (status `ERROR`) |

Lo scarto di un record **non interrompe** il caricamento: gli altri proseguono. Il conteggio di letti/caricati/scartati/duplicati e l'elenco errori sono nella risposta.

---

## 7. Attenzione ai numeri decimali e al separatore

Se il separatore CSV è la **virgola** `,`, un numero decimale scritto con la virgola (`1500,00`) verrebbe spezzato in due colonne. Due soluzioni:

1. **Usare il punto** come separatore decimale: `1500.00` (consigliato).
2. **Racchiudere** il campo tra virgolette e dichiarare l'enclosure: `"1500,00"` con `enclosure="\""`.

---

## 8. Esempi completi

### Anagrafica con dati sensibili
```csv
I;PK,S;HASH,S,D,DD
id,codice_fiscale,nome,data_nascita,importo
1,RSSMRA80A01H501U,mario rossi,1/1/1980,2500.50
2,VRDLNN85B15L736K,anna verdi,15/2/1985,1500.00
```
Risultato: `id` intero chiave (Int64), `codice_fiscale` hashato, `nome` = `MARIO ROSSI`, `data_nascita` ISODate, `importo` double. In più il campo tecnico `T` con l'istante del caricamento (epoch millis).

### File con path e email (case e simboli da preservare)
```csv
I;PK,S;KEEP_CASE,S;NO_CLEANUP,D
id,nome_file,email,data_upload
1,ReportQ4_2026.pdf,mario.rossi@dxc.com,1/1/2026
```
Risultato: `nome_file` preserva maiuscole/minuscole, `email` mantiene `@` e `.`.

### Chiave composta
```csv
I;PK,S;PK,S,DD
anno,codice_comune,descrizione,importo
2026,H501,Roma,1000.00
```
Chiave = (`anno`, `codice_comune`).

---

## 9. Checklist rapida prima di inviare il file

- [ ] Riga 1 = tipi (+flag), riga 2 = nomi, righe 3+ = dati
- [ ] Almeno un campo con `;PK`
- [ ] Separatore di colonna **diverso da `;`** (il `;` è riservato ai flag): usare `,` o TAB
- [ ] Stesso numero di colonne tra riga 1 e riga 2
- [ ] File salvato in UTF-8
- [ ] Nessuna cella obbligatoria vuota
- [ ] Interi (`I`) senza decimali; decimali con `DD` e `.` (oppure `,` ma con enclosure) se il separatore è `,`
- [ ] Date/ore in uno dei formati supportati
- [ ] `HASH`/`MASK`/`KEEP_CASE`/`NO_CLEANUP`/`TRUNCATE` solo su colonne `S`
- [ ] Nessuna colonna chiamata come il campo tecnico timestamp (default `T`)
