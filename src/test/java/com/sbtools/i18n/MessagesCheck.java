package com.sbtools.i18n;

/**
 * Lookup, English fallback, pt vs pt-BR, and unknown language codes.
 * Run: java -cp target/classes com.sbtools.i18n.MessagesCheck
 */
public final class MessagesCheck {

    public static void main(String[] args) {
        checkEnglish();
        checkFallback();
        checkPortugueseVariants();
        checkUnknownCode();
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
        String value = Messages.get("Only in English");
        if (!"Only in English".equals(value)) {
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

    private MessagesCheck() {
    }
}
