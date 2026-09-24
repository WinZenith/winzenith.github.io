package com.sbtools.ui;

import com.sbtools.i18n.Messages;
import com.sbtools.util.UiText;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks visible controls and rewrites their text when {@link Messages#setLanguage} runs.
 * The stored source is the English string (already sentence-cased when the control used {@link UiText}).
 */
public final class I18n {

    static final String WRITING = "i18n.writing";
    private static final String CASE = "i18n.case";
    private static final String INSTALLED = "i18n.installed";
    private static final String SRC = "i18n.src";
    private static final String ARGS = "i18n.args";

    private static final List<WeakReference<Labeled>> LABELED = new CopyOnWriteArrayList<>();
    private static final List<WeakReference<Tab>> TABS = new CopyOnWriteArrayList<>();
    private static final List<WeakReference<TableColumn<?, ?>>> COLUMNS = new CopyOnWriteArrayList<>();
    private static final List<WeakReference<MenuItem>> MENUS = new CopyOnWriteArrayList<>();
    private static final List<WeakReference<TextInputControl>> PROMPTS = new CopyOnWriteArrayList<>();
    private static final List<WeakReference<Tooltip>> TIPS = new CopyOnWriteArrayList<>();
    private static final List<WeakReference<ComboBox<String>>> COMBOS = new CopyOnWriteArrayList<>();

    static {
        Messages.addListener(I18n::refreshLater);
    }

    private I18n() {
    }

    /**
     * Later {@code setText} calls are English source strings. {@code sentenceCase} matches {@link UiText#label}.
     */
    public static void install(Labeled node, boolean sentenceCase) {
        if (node == null || node.getProperties().containsKey(INSTALLED)) {
            return;
        }
        node.getProperties().put(INSTALLED, Boolean.TRUE);
        node.getProperties().put(CASE, sentenceCase);
        node.textProperty().addListener((obs, prev, value) -> onLabeledText(node, value));
    }

    public static void installMenu(MenuItem item) {
        if (item == null || item.getProperties().containsKey(INSTALLED)) {
            return;
        }
        item.getProperties().put(INSTALLED, Boolean.TRUE);
        item.textProperty().addListener((obs, prev, value) -> {
            if (Boolean.TRUE.equals(item.getProperties().get(WRITING))) {
                return;
            }
            if (value == null) {
                item.getProperties().remove(SRC);
                return;
            }
            item.getProperties().put(SRC, value);
            String translated = Messages.get(value);
            if (!translated.equals(value)) {
                item.getProperties().put(WRITING, Boolean.TRUE);
                try {
                    item.setText(translated);
                } finally {
                    item.getProperties().remove(WRITING);
                }
            }
            remember(MENUS, item);
        });
    }

    private static void onLabeledText(Labeled node, String value) {
        if (Boolean.TRUE.equals(node.getProperties().get(WRITING))) {
            return;
        }
        if (value == null) {
            node.getProperties().remove(SRC);
            node.getProperties().remove(ARGS);
            return;
        }
        boolean sentenceCase = Boolean.TRUE.equals(node.getProperties().get(CASE));
        String key = sentenceCase ? UiText.label(value) : value;
        node.getProperties().put(SRC, key);
        node.getProperties().remove(ARGS);
        String translated = Messages.get(key);
        if (!translated.equals(value)) {
            write(node, translated);
        }
        remember(LABELED, node);
    }
    public static String t(String english) {
        return Messages.get(english);
    }

    /** Sentence-case like {@link UiText#label}, then translate. */
    public static String ui(String english) {
        if (english == null) {
            return null;
        }
        return Messages.get(UiText.label(english));
    }

    public static String f(String english, Object... args) {
        return Messages.format(english, args);
    }

    public static void assign(Labeled node, String english) {
        assign(node, english, (Object[]) null);
    }

    public static void assign(Labeled node, String english, Object... args) {
        if (node == null || isWriting(node)) {
            return;
        }
        if (english == null) {
            node.getProperties().remove(SRC);
            node.getProperties().remove(ARGS);
            write(node, null);
            return;
        }
        node.getProperties().put(SRC, english);
        if (args != null && args.length > 0) {
            node.getProperties().put(ARGS, args);
        } else {
            node.getProperties().remove(ARGS);
        }
        write(node, render(english, args));
        remember(LABELED, node);
    }

    public static void tab(Tab tab, String english) {
        if (tab == null) {
            return;
        }
        tab.getProperties().put(SRC, english);
        tab.setText(Messages.get(english));
        remember(TABS, tab);
    }

    public static void column(TableColumn<?, ?> column, String english) {
        if (column == null) {
            return;
        }
        column.getProperties().put(SRC, english);
        column.setText(Messages.get(english));
        remember(COLUMNS, column);
    }

    public static void menu(MenuItem item, String english) {
        if (item == null || Boolean.TRUE.equals(item.getProperties().get(WRITING))) {
            return;
        }
        item.getProperties().put(SRC, english);
        item.getProperties().put(WRITING, Boolean.TRUE);
        try {
            item.setText(english == null ? null : Messages.get(english));
        } finally {
            item.getProperties().remove(WRITING);
        }
        remember(MENUS, item);
    }

    public static void prompt(TextInputControl input, String english) {
        if (input == null) {
            return;
        }
        input.getProperties().put(SRC, english);
        input.setPromptText(Messages.get(english));
        remember(PROMPTS, input);
    }

    public static Tooltip tooltip(String english) {
        Tooltip tooltip = new Tooltip(Messages.get(english));
        tooltip.getProperties().put(SRC, english);
        remember(TIPS, tooltip);
        return tooltip;
    }

    /**
     * Combo values stay the English token used by filters. Only the visible cell text is translated.
     */
    public static void combo(ComboBox<String> box) {
        if (box == null) {
            return;
        }
        box.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Messages.get(item));
            }
        });
        box.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Messages.get(item));
            }
        });
        remember(COMBOS, box);
    }

    private static boolean isWriting(Labeled node) {
        return Boolean.TRUE.equals(node.getProperties().get(WRITING));
    }

    private static void write(Labeled node, String value) {
        node.getProperties().put(WRITING, Boolean.TRUE);
        try {
            node.setText(value);
        } finally {
            node.getProperties().remove(WRITING);
        }
    }

    private static String render(String english, Object[] args) {
        if (args != null && args.length > 0) {
            return Messages.format(english, args);
        }
        return Messages.get(english);
    }

    private static <T> void remember(List<WeakReference<T>> list, T node) {
        for (WeakReference<T> ref : list) {
            if (ref.get() == node) {
                return;
            }
        }
        list.add(new WeakReference<>(node));
    }

    private static void refreshLater() {
        if (Platform.isFxApplicationThread()) {
            refresh();
        } else {
            Platform.runLater(I18n::refresh);
        }
    }

    private static void refresh() {
        refreshLabeled();
        for (WeakReference<Tab> ref : TABS) {
            Tab tab = ref.get();
            if (tab != null) {
                Object src = tab.getProperties().get(SRC);
                if (src instanceof String key) {
                    tab.setText(Messages.get(key));
                }
            }
        }
        for (WeakReference<TableColumn<?, ?>> ref : COLUMNS) {
            TableColumn<?, ?> column = ref.get();
            if (column != null) {
                Object src = column.getProperties().get(SRC);
                if (src instanceof String key) {
                    column.setText(Messages.get(key));
                }
            }
        }
        for (WeakReference<MenuItem> ref : MENUS) {
            MenuItem item = ref.get();
            if (item != null) {
                Object src = item.getProperties().get(SRC);
                if (src instanceof String key) {
                    menu(item, key);
                }
            }
        }
        for (WeakReference<TextInputControl> ref : PROMPTS) {
            TextInputControl input = ref.get();
            if (input != null) {
                Object src = input.getProperties().get(SRC);
                if (src instanceof String key) {
                    input.setPromptText(Messages.get(key));
                }
            }
        }
        for (WeakReference<Tooltip> ref : TIPS) {
            Tooltip tooltip = ref.get();
            if (tooltip != null) {
                Object src = tooltip.getProperties().get(SRC);
                if (src instanceof String key) {
                    tooltip.setText(Messages.get(key));
                }
            }
        }
        for (WeakReference<ComboBox<String>> ref : COMBOS) {
            ComboBox<String> box = ref.get();
            if (box != null) {
                combo(box);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void refreshLabeled() {
        for (WeakReference<Labeled> ref : LABELED) {
            Labeled node = ref.get();
            if (node == null) {
                continue;
            }
            Object src = node.getProperties().get(SRC);
            if (!(src instanceof String key)) {
                continue;
            }
            Object raw = node.getProperties().get(ARGS);
            Object[] args = raw instanceof Object[] array ? array : null;
            write(node, render(key, args));
        }
    }
}
