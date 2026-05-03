package com.abioscase.live.shared.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

public class HttpUtils {

    public static String readBodySafely(InputStream bodyStream) {
        if (bodyStream == null) {
            return "";
        }
        try {
            return new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "unreadable body";
        }
    }

    public static long parseRetryAfterSeconds(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return 1L;
        }
        try {
            return Math.max(1L, Long.parseLong(rawHeader.trim()));
        } catch (NumberFormatException ignored) {
            // Try HTTP-date format
        }
        try {
            Instant retryAt = ZonedDateTime.parse(rawHeader).toInstant();
            long seconds = retryAt.getEpochSecond() - Instant.now().getEpochSecond();
            return Math.max(1L, seconds);
        } catch (DateTimeParseException ignored) {
            return 1L;
        }
    }
}
