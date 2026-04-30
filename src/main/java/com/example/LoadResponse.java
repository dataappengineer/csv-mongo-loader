package com.example;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Risposta del servizio di caricamento CSV")
public class LoadResponse {

    @Schema(description = "Esito: SUCCESS, FILE_NOT_FOUND, EMPTY_FILE, ERROR")
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

    public String getStatus()  { return status; }
    public int    getRecords() { return records; }
    public String getMessage() { return message; }
}
