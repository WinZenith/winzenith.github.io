package com.sbtools.ui;

/**
 * Run: java -cp target/classes:target/test-classes com.sbtools.ui.AppThemeCheck
 */
public final class AppThemeCheck {

    public static void main(String[] args) {
        if (AppTheme.fromCode(null) != AppTheme.DRACULA) {
            throw new AssertionError("null theme");
        }
        if (AppTheme.fromCode("  ") != AppTheme.DRACULA) {
            throw new AssertionError("blank theme");
        }
        if (AppTheme.fromCode("zz") != AppTheme.DRACULA) {
            throw new AssertionError("unknown theme");
        }
        if (AppTheme.fromCode("primer-light") != AppTheme.DRACULA) {
            throw new AssertionError("removed theme falls back to dracula");
        }
        if (AppTheme.fromCode("nord-light") != AppTheme.NORD_LIGHT) {
            throw new AssertionError("nord-light");
        }
        if (!"dracula".equals(AppTheme.canonical(null))) {
            throw new AssertionError("canonical null");
        }
        if (!"nord-light".equals(AppTheme.canonical("NORD-LIGHT"))) {
            throw new AssertionError("canonical nord");
        }
        if (!AppTheme.NORD_LIGHT.isLight() || AppTheme.DRACULA.isLight()) {
            throw new AssertionError("isLight");
        }
        System.out.println("AppThemeCheck ok");
    }
}
