package com.thegamecellar.recommendationservice.exception;

public class ServiceCommunicationException extends RuntimeException {

    public ServiceCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
