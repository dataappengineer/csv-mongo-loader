package com.example;

/**
 * Errore di trasformazione/validazione di un singolo valore.
 * Il messaggio descrive il motivo; il contesto di riga/colonna viene aggiunto
 * dal chiamante (loop di caricamento).
 */
public class TransformException extends Exception {

    public TransformException(String message) {
        super(message);
    }

    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
