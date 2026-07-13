package com.example;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api")
@Tag(name = "CSV Loader", description = "Caricamento di file CSV su MongoDB con modalita' TI, IA e IU")
public class LoadController {

    private static final Logger LOG = Logger.getLogger(LoadController.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MongoCSVLoader service;

    public LoadController(MongoCSVLoader service) {
        this.service = service;
    }

    @PostMapping("/load")
    @Operation(
        summary = "Carica un file CSV su MongoDB",
        description = "Legge il file CSV dal percorso indicato e lo carica nella collezione MongoDB " +
            "secondo la modalita' specificata (TI = Truncate Insert, IA = Insert Append, " +
            "IU = Insert Update/Upsert). Scrive il log, rinomina il file e crea la vista _RAW. " +
            "Se callbackUrl e' valorizzato risponde 202 immediatamente ed esegue il caricamento " +
            "in background, notificando il risultato via POST (Basic Auth) all'URL indicato."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Operazione sincrona completata. Verificare il campo status nel body.",
            content = @Content(schema = @Schema(implementation = LoadResponse.class))),
        @ApiResponse(responseCode = "202",
            description = "Modalita' asincrona: elaborazione avviata in background.",
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
        // La presenza della PK NON e' validata qui: la chiave puo' essere dichiarata
        // con ;PK nella riga 1 del CSV (che il controller non legge) oppure con il
        // parametro chiaveUpsert della chiamata. La verifica e' delegata al loader, che
        // legge l'header e risponde 200 + status=ERROR se manca (come per gli altri
        // errori rilevati leggendo il file).

        // Modalita' asincrona: se callbackUrl e' valorizzato
        if (!isBlank(request.getCallbackUrl())) {
            if (isBlank(request.getCallbackUser()) || isBlank(request.getCallbackPassword())) {
                return ResponseEntity.badRequest()
                        .body(new LoadResponse("ERROR", 0,
                                "callbackUser e callbackPassword sono obbligatori quando callbackUrl e' valorizzato"));
            }
            String jobId = request.getJobId() != null ? request.getJobId() : "";
            Thread t = new Thread(() -> {
                LoadResponse result;
                try {
                    result = service.load(request);
                } catch (Exception e) {
                    LOG.severe("Async load [" + jobId + "] FAILED: " + e.getMessage());
                    result = new LoadResponse("ERROR", 0, e.getMessage());
                }
                sendCallback(request.getCallbackUrl(), request.getCallbackUser(),
                        request.getCallbackPassword(), jobId, result);
            });
            t.setDaemon(true);
            t.start();
            LOG.info("Async load avviato [jobId=" + jobId + "] callback=" + request.getCallbackUrl());
            return ResponseEntity.status(202)
                    .body(new LoadResponse(jobId, "ACCEPTED", 0, "Elaborazione asincrona avviata"));
        }

        // Modalita' sincrona (comportamento originale)
        return ResponseEntity.ok(service.load(request));
    }

    void sendCallback(String url, String user, String password, String jobId, LoadResponse result) {
        try {
            result.setJobId(jobId);
            String body = MAPPER.writeValueAsString(result); // report completo; escaping gestito da Jackson

            String credentials = Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + credentials)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            LOG.info("Callback [jobId=" + jobId + "] → HTTP " + response.statusCode());
        } catch (Exception e) {
            LOG.severe("Callback [jobId=" + jobId + "] FAILED: " + e.getMessage());
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
