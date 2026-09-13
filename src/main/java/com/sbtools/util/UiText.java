package com.sbtools.util;

import java.util.Locale;
import java.util.Set;

/**
 * Sentence-case normalization for short in-app UI labels (sidebar, buttons, tabs).
 */
public final class UiText {

    private static final int LABEL_MAX_LEN = 80;

    private static final Set<String> PRESERVE = Set.of(
            "ASP.NET", "BIOS", "CPU", "CSV", "DHCP", "DNS", "GPU", "IDE", "IP", "IPv4", "IPv6",
            "FAQ", "iTunes", "Microsoft", "MTU", "NPM", "NVMe", "OS", "RAM", "TCP", "USB", "VS", "Wi-Fi",
            "Windows", "Winget"
    );

    private UiText() {
    }

    /**
     * Normalizes a short UI label to sentence case. Idempotent for already-correct labels.
     * Returns the input unchanged for null/empty, multi-line text, or strings longer than {@link #LABEL_MAX_LEN}.
     */
    public static String label(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (text.indexOf('\n') >= 0 || text.length() > LABEL_MAX_LEN) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean firstToken = true;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isDelimiter(c)) {
                out.append(c);
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && !isDelimiter(text.charAt(i))) {
                i++;
            }
            String token = text.substring(start, i);
            out.append(normalizeToken(token, firstToken));
            firstToken = false;
        }
        return out.toString();
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || c == '/' || c == '&';
    }

    private static String normalizeToken(String token, boolean first) {
        if (token.isEmpty()) {
            return token;
        }
        if (first) {
            String preserved = preservedForm(token);
            if (preserved != null) {
                return preserved;
            }
            return capitalizeFirstOnly(token);
        }
        String preserved = preservedForm(token);
        if (preserved != null) {
            return preserved;
        }
        return token.toLowerCase(Locale.ROOT);
    }

    private static String preservedForm(String token) {
        if (token.length() >= 2 && isAllUpperAscii(token)) {
            return token;
        }
        for (String p : PRESERVE) {
            if (p.equalsIgnoreCase(token)) {
                return p;
            }
        }
        return null;
    }

    private static boolean isAllUpperAscii(String token) {
        boolean hasLetter = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                hasLetter = true;
            } else if (c >= 'a' && c <= 'z') {
                return false;
            }
        }
        return hasLetter;
    }

    private static String capitalizeFirstOnly(String token) {
        int firstLetter = -1;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                firstLetter = i;
                break;
            }
        }
        if (firstLetter < 0) {
            return token;
        }
        char lead = token.charAt(firstLetter);
        if (Character.isUpperCase(lead) && firstLetter == 0) {
            return token;
        }
        StringBuilder sb = new StringBuilder(token);
        sb.setCharAt(firstLetter, Character.toUpperCase(lead));
        return sb.toString();
    }
}
