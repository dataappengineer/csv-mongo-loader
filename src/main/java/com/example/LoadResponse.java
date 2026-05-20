package com.example;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Risposta del servizio di caricamento CSV")
public class LoadResponse {

    @Schema(description = "Identificativo job (presente solo in modalita' asincrona)")
    private String jobId;

    @Schema(description = "Esito: SUCCESS, FILE_NOT_FOUND, EMPTY_FILE, ERROR, ACCEPTED")
    private String status;

    @Schema(description = "Numero di record elaborati")
    private int records;

    @Schema(description = "Messaggio descrittivo in caso di errore o anomalia")
    private String message;

    public LoadResponse() {}

    public LoadResponse(String status, int records, String message) {
        this.status  = status;
        this.records = records;
        this.message = message;
    }

    public LoadResponse(String jobId, String status, int records, String message) {
        this.jobId   = jobId;
        this.status  = status;
        this.records = records;
        this.message = message;
    }

    public String getJobId()   { return jobId; }
    public String getStatus()  { return status; }
    public int    getRecords() { return records; }
    public String getMessage() { return message; }
}
