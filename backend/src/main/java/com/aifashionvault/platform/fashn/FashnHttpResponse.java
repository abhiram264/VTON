package com.aifashionvault.platform.fashn;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import tools.jackson.databind.JsonNode;

final class FashnHttpResponse {
    enum Outcome {
        HTTP,
        UNSENT,
        INDETERMINATE
    }

    private final Outcome outcome;
    private final int statusCode;
    private final String creditsUsed;
    private final JsonNode body;
    private final boolean bodyOversized;
    private final Instant retryAfterAt;
    private final Instant dateHeaderAt;

    private FashnHttpResponse(
        Outcome outcome,
        int statusCode,
        String creditsUsed,
        JsonNode body,
        boolean bodyOversized,
        Instant retryAfterAt,
        Instant dateHeaderAt
    ) {
        this.outcome = outcome;
        this.statusCode = statusCode;
        this.creditsUsed = creditsUsed;
        this.body = body;
        this.bodyOversized = bodyOversized;
        this.retryAfterAt = retryAfterAt;
        this.dateHeaderAt = dateHeaderAt;
    }

    static FashnHttpResponse http(int statusCode, String creditsUsed, JsonNode body, boolean bodyOversized) {
        return http(statusCode, creditsUsed, body, bodyOversized, null);
    }

    static FashnHttpResponse http(
        int statusCode,
        String creditsUsed,
        JsonNode body,
        boolean bodyOversized,
        Instant retryAfterAt
    ) {
        return new FashnHttpResponse(Outcome.HTTP, statusCode, creditsUsed, body, bodyOversized, retryAfterAt, null);
    }

    static FashnHttpResponse http(
        int statusCode,
        String creditsUsed,
        JsonNode body,
        boolean bodyOversized,
        Instant retryAfterAt,
        Instant dateHeaderAt
    ) {
        return new FashnHttpResponse(Outcome.HTTP, statusCode, creditsUsed, body, bodyOversized, retryAfterAt, dateHeaderAt);
    }

    static FashnHttpResponse unsent() {
        return new FashnHttpResponse(Outcome.UNSENT, 0, null, null, false, null, null);
    }

    static FashnHttpResponse indeterminate() {
        return new FashnHttpResponse(Outcome.INDETERMINATE, 0, null, null, false, null, null);
    }

    static Instant parseRetryAfter(String value, Instant responseTime) {
        return parseRetryAfter(value, responseTime, null);
    }

    /**
     * Q27: HTTP-date Retry-After values resolve against the response
     * {@code Date} header when the response carried one, else against the
     * backend receipt instant. Invalid, negative, or past guidance is
     * dropped so the retry engine falls back to its computed delay.
     */
    static Instant parseRetryAfter(String value, Instant responseTime, Instant dateHeaderAt) {
        if (value == null || responseTime == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isAsciiDigits(trimmed)) {
            try {
                return responseTime.plusSeconds(Long.parseLong(trimmed));
            } catch (ArithmeticException | NumberFormatException | DateTimeException ex) {
                return null;
            }
        }
        try {
            Instant retryAfter = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Instant anchor = dateHeaderAt == null ? responseTime : dateHeaderAt;
            return retryAfter.isBefore(anchor) ? null : retryAfter;
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static boolean isAsciiDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    Outcome outcome() {
        return outcome;
    }

    int statusCode() {
        return statusCode;
    }

    String creditsUsed() {
        return creditsUsed;
    }

    JsonNode body() {
        return body;
    }

    boolean bodyOversized() {
        return bodyOversized;
    }

    Instant retryAfterAt() {
        return retryAfterAt;
    }

    Instant dateHeaderAt() {
        return dateHeaderAt;
    }

    @Override
    public String toString() {
        return "FashnHttpResponse[" + outcome + "," + statusCode + "]";
    }
}
