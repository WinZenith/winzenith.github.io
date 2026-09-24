package com.sbtools.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * UTF-8 string table. A missing key falls back to the English bundle, then to the key itself.
 */
public final class Messages {

    private static final Map<AppLanguage, Properties> BUNDLES = new EnumMap<>(AppLanguage.class);
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static AppLanguage current = AppLanguage.EN;

    static {
        for (AppLanguage language : AppLanguage.values()) {
            BUNDLES.put(language, load(language));
        }
    }

    private Messages() {
    }

    public static AppLanguage language() {
        return current;
    }

    public static void setLanguage(AppLanguage language) {
        AppLanguage next = language == null ? AppLanguage.EN : language;
        if (next == current) {
            return;
        }
        current = next;
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // one view must not block the others
            }
        }
    }

    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static String get(String key) {
        if (key == null || key.isEmpty()) {
            return key == null ? "" : key;
        }
        String value = lookup(current, key);
        if (value != null) {
            return value;
        }
        if (current != AppLanguage.EN) {
            value = lookup(AppLanguage.EN, key);
            if (value != null) {
                return value;
            }
        }
        return key;
    }

    /** Replaces {@code {0}}, {@code {1}}, ... in the translated pattern. */
    public static String format(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        String out = pattern;
        for (int i = 0; i < args.length; i++) {
            String token = "{" + i + "}";
            out = out.replace(token, args[i] == null ? "" : String.valueOf(args[i]));
        }
        return out;
    }

    private static String lookup(AppLanguage language, String key) {
        Properties properties = BUNDLES.get(language);
        if (properties == null || !properties.containsKey(key)) {
            return null;
        }
        return properties.getProperty(key);
    }

    private static Properties load(AppLanguage language) {
        String name = switch (language) {
            case EN -> "messages.properties";
            case DE -> "messages_de.properties";
            case ES -> "messages_es.properties";
            case FR -> "messages_fr.properties";
            case IT -> "messages_it.properties";
            case PT -> "messages_pt.properties";
            case PT_BR -> "messages_pt_BR.properties";
        };
        Properties properties = new Properties();
        String path = "/i18n/" + name;
        try (InputStream in = Messages.class.getResourceAsStream(path)) {
            if (in != null) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // empty bundle: get() falls back to the key
        }
        return properties;
    }
}
