#!/usr/bin/env python3
import json

base = {
    "mongoUri": "mongodb://localhost:27017",
    "database": "test_delivery",
    "separatore": ",",
    "enclosure": "NONE",
    "logCollezione": "C_DR_APP_LOG_FILE_CSV"
}

tests = {
    "t01": {**base, "collezione": "t01_regressione", "csvPath": "/tmp/test_small.csv", "modo": "TI"},
    "t02": {**base, "collezione": "t02_batchsize",   "csvPath": "/tmp/test_small.csv", "modo": "TI", "batchSize": 2},
    "t03": {**base, "collezione": "t03_hash",        "csvPath": "/tmp/test_small.csv", "modo": "TI", "colonneHash": ["cognome", "email"]},
    "t04": {**base, "collezione": "t04_large",       "csvPath": "/tmp/test_large.csv", "modo": "TI", "batchSize": 1000},
    "t05": {**base, "collezione": "t05_iu_hash",     "csvPath": "/tmp/test_small.csv", "modo": "IU", "chiaveUpsert": "id_chiave", "colonneHash": ["cognome", "email"]},
    "t06": {**base, "collezione": "t06_notfound",    "csvPath": "/tmp/file_inesistente.csv", "modo": "TI"},
}

for name, body in tests.items():
    path = f"/tmp/{name}.json"
    with open(path, "w") as f:
        json.dump(body, f)
    print(f"Created {path}")
