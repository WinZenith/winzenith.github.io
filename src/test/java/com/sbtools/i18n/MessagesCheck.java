package com.sbtools.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lookup, English fallback, pt vs pt-BR, unknown language codes, and bundle parity.
 * Run: java -cp target/classes:target/test-classes com.sbtools.i18n.MessagesCheck
 */
public final class MessagesCheck {

    private static final String ONLY_IN_ENGLISH = "Only in English";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[0-9]+}");

    public static void main(String[] args) throws IOException {
        checkEnglish();
        checkFallback();
        checkPortugueseVariants();
        checkUnknownCode();
        checkKeyParity();
        checkNoDuplicateKeys();
        checkPlaceholders();
        System.out.println("MessagesCheck ok");
    }

    private static void checkEnglish() {
        Messages.setLanguage(AppLanguage.EN);
        String value = Messages.get("Check for updates");
        if (!"Check for updates".equals(value)) {
            throw new AssertionError("english key: " + value);
        }
    }

    private static void checkFallback() {
        Messages.setLanguage(AppLanguage.DE);
        String value = Messages.get(ONLY_IN_ENGLISH);
        if (!ONLY_IN_ENGLISH.equals(value)) {
            throw new AssertionError("fallback: " + value);
        }
        String missing = Messages.get("no.such.key");
        if (!"no.such.key".equals(missing)) {
            throw new AssertionError("missing key: " + missing);
        }
    }

    private static void checkPortugueseVariants() {
        Messages.setLanguage(AppLanguage.PT);
        String pt = Messages.get("Check for updates");
        Messages.setLanguage(AppLanguage.PT_BR);
        String br = Messages.get("Check for updates");
        if (pt.equals(br) || "Check for updates".equals(pt) || "Check for updates".equals(br)) {
            throw new AssertionError("pt/pt-BR not distinct: " + pt + " / " + br);
        }
    }

    private static void checkUnknownCode() {
        if (!"en".equals(AppLanguage.canonical(null))) {
            throw new AssertionError("null");
        }
        if (!"en".equals(AppLanguage.canonical("  "))) {
            throw new AssertionError("blank");
        }
        if (!"en".equals(AppLanguage.canonical("zz"))) {
            throw new AssertionError("unknown");
        }
        if (!"pt-BR".equals(AppLanguage.canonical("pt_BR"))) {
            throw new AssertionError("pt_BR");
        }
        if (!"de".equals(AppLanguage.canonical("DE"))) {
            throw new AssertionError("DE");
        }
    }

    private static void checkKeyParity() throws IOException {
        Set<String> english = loadedKeys(AppLanguage.EN);
        for (AppLanguage language : AppLanguage.values()) {
            if (language == AppLanguage.EN) {
                continue;
            }
            Set<String> localized = loadedKeys(language);
            for (String key : english) {
                if (ONLY_IN_ENGLISH.equals(key)) {
                    continue;
                }
                if (!localized.contains(key)) {
                    throw new AssertionError(language + " missing key: " + key);
                }
            }
            for (String key : localized) {
                if (!english.contains(key)) {
                    throw new AssertionError(language + " extra key: " + key);
                }
            }
        }
    }

    private static void checkNoDuplicateKeys() throws IOException {
        for (AppLanguage language : AppLanguage.values()) {
            int lines = keyLineCount(language);
            int unique = loadedKeys(language).size();
            if (lines != unique) {
                throw new AssertionError(language + " duplicate keys: " + lines + " lines, " + unique + " unique");
            }
        }
    }

    private static void checkPlaceholders() throws IOException {
        Properties english = loadProperties(AppLanguage.EN);
        for (String key : english.stringPropertyNames()) {
            if (ONLY_IN_ENGLISH.equals(key)) {
                continue;
            }
            Set<String> expected = placeholders(english.getProperty(key));
            if (expected.isEmpty()) {
                continue;
            }
            for (AppLanguage language : AppLanguage.values()) {
                if (language == AppLanguage.EN) {
                    continue;
                }
                String value = loadProperties(language).getProperty(key);
                if (value == null) {
                    throw new AssertionError(language + " missing placeholder key: " + key);
                }
                if (!expected.equals(placeholders(value))) {
                    throw new AssertionError(language + " placeholders for " + key + ": " + placeholders(value)
                            + " expected " + expected);
                }
            }
        }
    }

    private static Set<String> loadedKeys(AppLanguage language) throws IOException {
        return loadProperties(language).stringPropertyNames();
    }

    private static Properties loadProperties(AppLanguage language) throws IOException {
        String path = bundlePath(language);
        Properties properties = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("missing bundle: " + path);
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static int keyLineCount(AppLanguage language) throws IOException {
        String path = bundlePath(language);
        try (InputStream in = Messages.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("missing bundle: " + path);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int count = 0;
            for (String line : text.split("\n", -1)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (keyValueSeparatorIndex(line) >= 0) {
                    count++;
                }
            }
            return count;
        }
    }

    /** Index of the unescaped {@code =} between key and value, or {@code -1}. */
    private static int keyValueSeparatorIndex(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    private static Set<String> placeholders(String value) {
        Set<String> tokens = new HashSet<>();
        if (value == null) {
            return tokens;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static String bundlePath(AppLanguage language) {
        String name = switch (language) {
            case EN -> "messages.properties";
            case DE -> "messages_de.properties";
            case ES -> "messages_es.properties";
            case FR -> "messages_fr.properties";
            case IT -> "messages_it.properties";
            case PT -> "messages_pt.properties";
            case PT_BR -> "messages_pt_BR.properties";
        };
        return "/i18n/" + name;
    }

    private MessagesCheck() {
    }
}
