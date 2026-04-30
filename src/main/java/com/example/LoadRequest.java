package com.example;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Parametri per il caricamento del file CSV su MongoDB")
public class LoadRequest {

    @Schema(description = "URI di connessione MongoDB", example = "mongodb://localhost:27017")
    private String mongoUri;

    @Schema(description = "Nome del database MongoDB", example = "mio_database")
    private String database;

    @Schema(description = "Nome della collezione target", example = "mia_collezione")
    private String collezione;

    @Schema(description = "Percorso assoluto del file CSV", example = "/data/in/dati.csv")
    private String csvPath;

    @Schema(description = "Carattere separatore del CSV", example = ",")
    private String separatore;

    @Schema(description = "Enclosure/delimitatore di testo. Usare NONE se assente", example = "NONE")
    private String enclosure;

    @Schema(description = "Modalita' di caricamento: TI (Truncate Insert), IA (Insert Append), IU (Insert Update/Upsert)", example = "TI")
    private String modo;

    @Schema(description = "Nome della collezione di log", example = "C_DR_APP_LOG_FILE_CSV")
    private String logCollezione;

    @Schema(description = "Campo chiave per la modalita' IU (upsert). Obbligatorio solo se modo=IU", example = "id_chiave")
    private String chiaveUpsert;

    public String getMongoUri() { return mongoUri; }
    public void setMongoUri(String v) { this.mongoUri = v; }

    public String getDatabase() { return database; }
    public void setDatabase(String v) { this.database = v; }

    public String getCollezione() { return collezione; }
    public void setCollezione(String v) { this.collezione = v; }

    public String getCsvPath() { return csvPath; }
    public void setCsvPath(String v) { this.csvPath = v; }

    public String getSeparatore() { return separatore; }
    public void setSeparatore(String v) { this.separatore = v; }

    public String getEnclosure() { return enclosure; }
    public void setEnclosure(String v) { this.enclosure = v; }

    public String getModo() { return modo; }
    public void setModo(String v) { this.modo = v; }

    public String getLogCollezione() { return logCollezione; }
    public void setLogCollezione(String v) { this.logCollezione = v; }

    public String getChiaveUpsert() { return chiaveUpsert; }
    public void setChiaveUpsert(String v) { this.chiaveUpsert = v; }
}
