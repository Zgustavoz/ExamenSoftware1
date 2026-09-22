package com.diagramas.platform.support.notification;

import java.util.Map;

/** Envío de push (FCM en producción). */
public interface PushSender {

    /** El token FCM ya no es válido (UNREGISTERED): hay que borrarlo del usuario. */
    class InvalidTokenException extends Exception {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    void send(String token, String title, String body, Map<String, String> data) throws InvalidTokenException, Exception;
}
