package com.sbtools.ui;

import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Reusable dialog for managing an ignored/skipped items list.
 * Used by both SoftwareUpdatesTabView and DriversTabView.
 */
public class IgnoredListDialog {

    /**
     * Shows the ignored list dialog.
     *
     * @param title       dialog title
     * @param items       the current list of ignored entries (format: "displayName\tid")
     * @param onSaved     callback invoked with (updatedList) after saving; receives the full new list
     * @param displayName lambda to extract display text from a stored entry (before the \t)
     */
    public static void show(String title,
                            List<String> items,
                            BiConsumer<List<String>, List<String>> onSaved) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(AppInfo.DISPLAY_NAME);
        dialog.setHeaderText(title);

        ObservableList<String> observable = FXCollections.observableArrayList(items);

        ListView<String> listView = new ListView<>();
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    int t = item.lastIndexOf('\t');
                    setText(t >= 0 ? item.substring(0, t) : item);
                }
            }
        });
        listView.setItems(observable);
        listView.setPrefHeight(300);

        Button removeBtn = new Button("Remove Selected");
        removeBtn.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                observable.remove(selected);
                if (onSaved != null) {
                    onSaved.accept(new ArrayList<>(observable), null);
                }
            }
        });

        VBox layout = new VBox(10, new Label("Skipped items:"), listView, removeBtn);
        layout.setPadding(new Insets(10));
        layout.setPrefWidth(500);

        dialog.getDialogPane().setContent(layout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    /**
     * Convenience overload: saves directly via SettingsStore.
     */
    public static void showAndSave(String title, List<String> currentItems, SettingsStore store) {
        show(title, currentItems, (updated, ignored) -> {
            try {
                AppSettings current = store.load();
                AppSettings updated_ = new AppSettings(
                        current.autoBackupDrivers(),
                        current.createSystemRestorePoint(),
                        current.eulaAccepted(),
                        current.excludedDriverIds(),
                        updated,
                        current.networkOptimizationPreset(),
                        current.downloadDirectory(),
                        current.licenseKey(),
                        current.minimizeToTray(),
                        current.startMinimized(),
                        current.scanOnStartup(),
                        current.notifyOnDriverUpdate(),
                        current.backupDirectory(),
                        current.powerShellPath(),
                        current.windowWidth(),
                        current.windowHeight(),
                        current.windowMaximized(),
                        current.autoCheckForUpdates()
                );
                store.save(updated_);
            } catch (IOException ex) {
                // ignore
            }
        });
    }
}
