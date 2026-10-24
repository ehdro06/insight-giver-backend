package com.insightgiver.insight;

public class InsightWriterException extends RuntimeException {

    public InsightWriterException(String message) {
        super(message);
    }

    public InsightWriterException(String message, Throwable cause) {
        super(message, cause);
    }
}
