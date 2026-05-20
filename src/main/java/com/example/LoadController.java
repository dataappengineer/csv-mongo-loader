package com.example;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Tag(name = "CSV Loader", description = "Caricamento di file CSV su MongoDB con modalita' TI, IA e IU")
public class LoadController {

    private final MongoCSVLoader service;

    public LoadController(MongoCSVLoader service) {
        this.service = service;
    }

    @PostMapping("/load")
    @Operation(
        summary = "Carica un file CSV su MongoDB",
        description = "Legge il file CSV dal percorso indicato e lo carica nella collezione MongoDB " +
            "secondo la modalita' specificata (TI = Truncate Insert, IA = Insert Append, " +
            "IU = Insert Update/Upsert). Scrive il log, rinomina il file e crea la vista _RAW."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Operazione completata. Verificare il campo status nel body.",
            content = @Content(schema = @Schema(implementation = LoadResponse.class))),
        @ApiResponse(responseCode = "400",
            description = "Parametri obbligatori mancanti o non validi.")
    })
    public ResponseEntity<LoadResponse> load(@RequestBody LoadRequest request) {

        // Validazione campi obbligatori
        if (isBlank(request.getMongoUri())     || isBlank(request.getDatabase())
                || isBlank(request.getCollezione()) || isBlank(request.getCsvPath())
                || isBlank(request.getSeparatore()) || isBlank(request.getEnclosure())
                || isBlank(request.getModo())        || isBlank(request.getLogCollezione())) {
            return ResponseEntity.badRequest().body(new LoadResponse("ERROR", 0,
                    "Tutti i campi obbligatori devono essere valorizzati: " +
                    "mongoUri, database, collezione, csvPath, separatore, enclosure, modo, logCollezione"));
        }

        String modo = request.getModo().toUpperCase();
        if (!modo.equals("TI") && !modo.equals("IA") && !modo.equals("IU")) {
            return ResponseEntity.badRequest()
                    .body(new LoadResponse("ERROR", 0, "Il campo modo deve essere TI, IA o IU"));
        }
        if (modo.equals("IU") && (request.getChiaveUpsert() == null || request.getChiaveUpsert().isEmpty())) {
            return ResponseEntity.badRequest()
                    .body(new LoadResponse("ERROR", 0,
                            "Il campo chiaveUpsert e' obbligatorio per la modalita' IU"));
        }

        return ResponseEntity.ok(service.load(request));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
