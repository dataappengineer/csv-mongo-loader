#!/usr/bin/env bash
# =============================================================================
# run_tests.sh  –  Build e test completo di MongoCSVLoader
# Prerequisiti: Java 11+, Maven 3.x, MongoDB in ascolto su localhost:27017
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/csv-mongo-loader-1.0-SNAPSHOT.jar"
MONGO_URI="mongodb://localhost:27017"
DB="test_db"
COLL="test_coll"

# ── Build ────────────────────────────────────────────────────────────────────
echo ">>> Build Maven..."
cd "$SCRIPT_DIR"
mvn -q clean package
echo ">>> JAR prodotto: $JAR"
echo

# ── Ripristina i file di test (vengono rinominati dopo ogni caricamento) ──────
restore_csv() {
    cp "$SCRIPT_DIR/dati.csv.bak"                "$SCRIPT_DIR/dati.csv"
    cp "$SCRIPT_DIR/dati_punto_virgola.csv.bak"  "$SCRIPT_DIR/dati_punto_virgola.csv"
}
# Crea i backup la prima volta
[ -f "$SCRIPT_DIR/dati.csv.bak" ]               || cp "$SCRIPT_DIR/dati.csv"               "$SCRIPT_DIR/dati.csv.bak"
[ -f "$SCRIPT_DIR/dati_punto_virgola.csv.bak" ] || cp "$SCRIPT_DIR/dati_punto_virgola.csv"  "$SCRIPT_DIR/dati_punto_virgola.csv.bak"

# ── Test 1: FILE_NOT_FOUND ───────────────────────────────────────────────────
echo ">>> TEST 1: file mancante (atteso: FILE_NOT_FOUND nel log)"
java -jar "$JAR" "$MONGO_URI" "$DB" "$COLL" "file_inesistente.csv" , NONE TI
echo

# ── Test 2: TI (Truncate Insert) con virgola ─────────────────────────────────
echo ">>> TEST 2: TI – separatore virgola, nessun enclosure"
restore_csv
java -jar "$JAR" "$MONGO_URI" "$DB" "$COLL" "$SCRIPT_DIR/dati.csv" , NONE TI
echo

# ── Test 3: IA (Insert Append) con virgola ───────────────────────────────────
echo ">>> TEST 3: IA – separatore virgola, nessun enclosure (i dati precedenti restano)"
restore_csv
java -jar "$JAR" "$MONGO_URI" "$DB" "$COLL" "$SCRIPT_DIR/dati.csv" , NONE IA
echo

# ── Test 4: IU (Insert Update / Upsert) con punto e virgola + enclosure " ────
echo ">>> TEST 4: IU – separatore ';', enclosure '\"', chiave=id_chiave"
restore_csv
java -jar "$JAR" "$MONGO_URI" "$DB" "$COLL" "$SCRIPT_DIR/dati_punto_virgola.csv" ';' '"' IU id_chiave
echo

echo "============================================================"
echo " Tutti i test completati."
echo " Controlla la collezione '$COLL' e 'C_DR_APP_LOG_FILE_CSV'"
echo " nel database '$DB' su MongoDB per verificare i risultati."
echo "============================================================"
