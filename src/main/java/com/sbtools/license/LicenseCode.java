package com.sbtools.license;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

public final class LicenseCode {

    public static final String PREFIX = "SBTOOLS-";
    private static final String CHARSET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int DATE_PART_LEN = 6;
    private static final int HMAC_PART_LEN = 14;
    private static final int TOTAL_LEN = DATE_PART_LEN + HMAC_PART_LEN;
    private static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);

    private static final String SECRET_KEY = "WinZenith-2026-LicKey-HMAC-SHA256!";

    private LicenseCode() {
    }

    public static String encode(LocalDate expiryDate) {
        long days = ChronoUnit.DAYS.between(EPOCH, expiryDate);
        String datePart = encodeBase36(days, DATE_PART_LEN);
        String hmac = computeHmac(datePart);
        String fullCode = datePart + hmac;
        return PREFIX + formatWithDashes(fullCode);
    }

    public static ValidationResult validate(String code) {
        if (code == null) {
            return ValidationResult.invalid("License code is empty.");
        }
        String trimmed = code.trim();
        String upper = trimmed.toUpperCase();
        if (upper.startsWith(PREFIX)) {
            trimmed = trimmed.substring(PREFIX.length());
        }
        String cleaned = trimmed.replace("-", "").toUpperCase();
        if (cleaned.length() != TOTAL_LEN) {
            return ValidationResult.invalid("Invalid license code format.");
        }
        String datePart = cleaned.substring(0, DATE_PART_LEN);
        String hmacPart = trimmed.replace("-", "").substring(DATE_PART_LEN);
        String expectedHmac = computeHmac(datePart);
        if (!constantTimeEquals(hmacPart, expectedHmac)) {
            return ValidationResult.invalid("Invalid license code.");
        }
        long days;
        try {
            days = decodeBase36(datePart);
        } catch (NumberFormatException e) {
            return ValidationResult.invalid("Invalid license code.");
        }
        LocalDate expiryDate = EPOCH.plusDays(days);
        if (expiryDate.isBefore(LocalDate.now())) {
            return ValidationResult.expired(expiryDate);
        }
        return ValidationResult.valid(expiryDate);
    }

    private static String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[10];
            System.arraycopy(hash, 0, truncated, 0, 10);
            String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
            return base64.substring(0, HMAC_PART_LEN);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    private static String encodeBase36(long value, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(CHARSET.charAt((int) (value % CHARSET.length())));
            value /= CHARSET.length();
        }
        return sb.reverse().toString();
    }

    private static long decodeBase36(String s) {
        long result = 0;
        for (char c : s.toCharArray()) {
            int index = CHARSET.indexOf(c);
            if (index < 0) {
                throw new NumberFormatException("Invalid character in code: " + c);
            }
            result = result * CHARSET.length() + index;
        }
        return result;
    }

    private static String formatWithDashes(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 5 == 0) {
                sb.append('-');
            }
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    public record ValidationResult(
            boolean valid,
            boolean expired,
            LocalDate expiryDate,
            String errorMessage
    ) {
        static ValidationResult valid(LocalDate expiryDate) {
            return new ValidationResult(true, false, expiryDate, null);
        }

        static ValidationResult expired(LocalDate expiryDate) {
            return new ValidationResult(false, true, expiryDate, "License expired on " + expiryDate);
        }

        static ValidationResult invalid(String message) {
            return new ValidationResult(false, false, null, message);
        }
    }
}
