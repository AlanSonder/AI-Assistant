package com.alan.aicommon.exception;

public class TranslationException extends RuntimeException {

    private String errorCode;

    public TranslationException(String message) {
        super(message);
        this.errorCode = "TRANSLATION_ERROR";
    }

    public TranslationException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "TRANSLATION_ERROR";
    }

    public String getErrorCode() {
        return errorCode;
    }
}
