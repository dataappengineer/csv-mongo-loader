package com.example;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "Risposta del servizio di caricamento CSV")
public class LoadResponse {

    @Schema(description = "Identificativo job (presente solo in modalita' asincrona)")
    private String jobId;

    @Schema(description = "Esito: SUCCESS, FILE_NOT_FOUND, EMPTY_FILE, ERROR, ACCEPTED")
    private String status;

    @Schema(description = "Record effettivamente caricati (inseriti + aggiornati). Semantica invariata rispetto alle versioni precedenti.")
    private int records;

    @Schema(description = "Record totali letti dal CSV (righe dati), inclusi scartati e duplicati")
    private int recordsRead;

    @Schema(description = "Record inseriti in MongoDB")
    private int recordsInserted;

    @Schema(description = "Record aggiornati in MongoDB (modo IU)")
    private int recordsUpdated;

    @Schema(description = "Record scartati per errore (campo vuoto, tipo incoerente, PK mancante)")
    private int recordsSkipped;

    @Schema(description = "Record scartati perche' PK duplicata nel file")
    private int recordsDuplicati;

    @Schema(description = "Messaggio descrittivo in caso di errore o anomalia")
    private String message;

    @Schema(description = "Elenco errori dettagliati (max 100)")
    private List<String> errors = new ArrayList<>();

    public LoadResponse() {
    }

    public LoadResponse(String status, int records, String message) {
        this.status = status;
        this.records = records;
        this.message = message;
    }

    public LoadResponse(String jobId, String status, int records, String message) {
        this.jobId = jobId;
        this.status = status;
        this.records = records;
        this.message = message;
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRecords() { return records; }
    public void setRecords(int records) { this.records = records; }

    public int getRecordsRead() { return recordsRead; }
    public void setRecordsRead(int recordsRead) { this.recordsRead = recordsRead; }

    public int getRecordsInserted() { return recordsInserted; }
    public void setRecordsInserted(int recordsInserted) { this.recordsInserted = recordsInserted; }

    public int getRecordsUpdated() { return recordsUpdated; }
    public void setRecordsUpdated(int recordsUpdated) { this.recordsUpdated = recordsUpdated; }

    public int getRecordsSkipped() { return recordsSkipped; }
    public void setRecordsSkipped(int recordsSkipped) { this.recordsSkipped = recordsSkipped; }

    public int getRecordsDuplicati() { return recordsDuplicati; }
    public void setRecordsDuplicati(int recordsDuplicati) { this.recordsDuplicati = recordsDuplicati; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
}
