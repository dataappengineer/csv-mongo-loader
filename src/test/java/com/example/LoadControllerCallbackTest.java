package com.example;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica che il callback asincrono invii il report COMPLETO (tutti i campi) con
 * autenticazione Basic Auth, usando un server HTTP locale usa-e-getta.
 */
class LoadControllerCallbackTest {

    @Test
    void sendCallback_postsFullReportWithBasicAuth() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> bodyRef = new AtomicReference<>();
        AtomicReference<String> authRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        server.createContext("/cb", exchange -> {
            authRef.set(exchange.getRequestHeaders().getFirst("Authorization"));
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            latch.countDown();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/cb";

            LoadResponse r = new LoadResponse("SUCCESS", 93, null);
            r.setRecordsRead(100);
            r.setRecordsInserted(93);
            r.setRecordsSkipped(5);
            r.setRecordsDuplicati(2);

            // service non usato da sendCallback
            LoadController controller = new LoadController(null);
            controller.sendCallback(url, "user", "secret", "id57", r);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "callback non ricevuto");

            String body = bodyRef.get();
            assertTrue(body.contains("\"jobId\":\"id57\""), body);
            assertTrue(body.contains("\"status\":\"SUCCESS\""), body);
            assertTrue(body.contains("\"records\":93"), body);
            assertTrue(body.contains("\"recordsRead\":100"), body);
            assertTrue(body.contains("\"recordsInserted\":93"), body);
            assertTrue(body.contains("\"recordsSkipped\":5"), body);
            assertTrue(body.contains("\"recordsDuplicati\":2"), body);
            assertTrue(body.contains("\"errors\":[]"), body);

            String expectedAuth = "Basic " + Base64.getEncoder()
                    .encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expectedAuth, authRef.get());
        } finally {
            server.stop(0);
        }
    }
}
