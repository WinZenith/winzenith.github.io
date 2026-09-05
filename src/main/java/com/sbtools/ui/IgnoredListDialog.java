package com.sbtools.ui;

import com.sbtools.util.AppInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

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

        ObservableList<String> observable = FXCollections.observableArrayList(items == null ? List.of() : items);
        javafx.collections.transformation.FilteredList<String> filtered =
                new javafx.collections.transformation.FilteredList<>(observable, s -> true);

        TextField searchField = new TextField();
        searchField.setPromptText("Filter ignored items...");
        searchField.textProperty().addListener((obs, o, n) -> {
            String q = n == null ? "" : n.trim().toLowerCase();
            filtered.setPredicate(s -> {
                if (q.isEmpty()) return true;
                return s != null && s.toLowerCase().contains(q);
            });
        });

        ListView<String> listView = new ListView<>();
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    int t = item.lastIndexOf('\t');
                    setText(t >= 0 ? item.substring(0, t) : item);
                    // Full "name \t id" as tooltip so the identifier stays visible without extra calls.
                    setTooltip(new Tooltip(item.replace('\t', ' ')));
                }
            }
        });
        listView.setItems(filtered);
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

        VBox layout = new VBox(10, new Label("Skipped items:"), searchField, listView, removeBtn);
        layout.setPadding(new Insets(10));
        layout.setPrefWidth(500);

        dialog.getDialogPane().setContent(layout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }
}
