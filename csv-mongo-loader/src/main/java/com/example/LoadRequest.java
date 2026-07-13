package com.example;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

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

    @Schema(description = "Chiave primaria dichiarata nella chiamata: modalita' alternativa ai flag ;PK nel CSV. Supporta chiave composta. Opzionale: usata solo se la riga 1 non contiene alcun ;PK. La PK e' comunque richiesta (da ;PK oppure da qui) per tutti i modi.", example = "[\"id_chiave\"]")
    private List<String> chiaveUpsert;

    @Schema(description = "Numero di record per batch (default 1000). Opzionale.", example = "1000")
    private Integer batchSize;

    @Schema(description = "Nomi colonne da hashare con SHA-512, dichiarate nella chiamata: modalita' alternativa al flag ;HASH nel CSV. Opzionale: usata solo se la riga 1 non contiene alcun ;HASH (e solo su colonne di tipo S).", example = "[\"codice_fiscale\", \"cognome\"]")
    private List<String> colonneHash;

    @Schema(description = "Nome della vista MongoDB da creare dopo il caricamento. Se assente viene usato <collezione>_RAW.", example = "mia_vista_RAW")
    private String nomeVista;

    @Schema(description = "Identificativo job fornito dal chiamante. Opzionale, restituito nel body 202 e nel callback.", example = "id57")
    private String jobId;

    @Schema(description = "URL di callback. Se valorizzato il servizio risponde 202 immediatamente e notifica il risultato via POST al termine. Opzionale.", example = "https://be.eka.it/api/callback")
    private String callbackUrl;

    @Schema(description = "Username Basic Auth per il callback. Obbligatorio se callbackUrl e' valorizzato.", example = "user")
    private String callbackUser;

    @Schema(description = "Password Basic Auth per il callback. Obbligatoria se callbackUrl e' valorizzato.", example = "secret")
    private String callbackPassword;

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

    public List<String> getChiaveUpsert() { return chiaveUpsert; }
    public void setChiaveUpsert(List<String> v) { this.chiaveUpsert = v; }

    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer v) { this.batchSize = v; }

    public List<String> getColonneHash() { return colonneHash; }
    public void setColonneHash(List<String> v) { this.colonneHash = v; }

    public String getNomeVista() { return nomeVista; }
    public void setNomeVista(String v) { this.nomeVista = v; }

    public String getJobId() { return jobId; }
    public void setJobId(String v) { this.jobId = v; }

    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String v) { this.callbackUrl = v; }

    public String getCallbackUser() { return callbackUser; }
    public void setCallbackUser(String v) { this.callbackUser = v; }

    public String getCallbackPassword() { return callbackPassword; }
    public void setCallbackPassword(String v) { this.callbackPassword = v; }
}
