package com.sbtools.cleaner;

import com.sbtools.util.AppInfo;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * Read-only preview for a single cleanup category.
 * Shows risk, admin requirement, scanned size, and target locations.
 * Manual-flow only: no auto-clean actions here.
 */
public class CleanupCategoryDetailDialog extends Dialog<ButtonType> {

    public CleanupCategoryDetailDialog(CleanupRow row) {
        setTitle(AppInfo.DISPLAY_NAME + " - Category Details");
        setHeaderText(row.getCategory().getDisplayName());
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        VBox box = new VBox(8);
        box.setPadding(new Insets(12));

        CleanupCategory cat = row.getCategory();
        boolean requiresAdmin = false;
        java.util.List<String> targets = java.util.Collections.emptyList();
        try {
            CleanerExtension ext = CleanerRegistry.get(cat);
            if (ext != null) {
                requiresAdmin = ext.requiresAdmin();
                targets = ext.describeTargets();
            }
        } catch (Exception ignored) {}

        Label riskLabel = new Label("Risk: " + cat.getRiskLevel().getDisplayName()
                + " — " + cat.getRiskLevel().getDescription());
        riskLabel.setWrapText(true);

        Label descLabel = new Label("What it cleans: " + cat.getDescription());
        descLabel.setWrapText(true);

        StringBuilder scanInfo = new StringBuilder();
        scanInfo.append("Scanned: ").append(row.sizeOrCountTextProperty().get());
        scanInfo.append(" | Status: ").append(row.getScanStatus().getDisplayText());
        if (row.getScanDurationMs() > 0) {
            scanInfo.append(" | Took: ").append(row.getScanDurationMs()).append(" ms");
        }
        if (requiresAdmin) {
            scanInfo.append(" | Requires administrator");
        }
        if (row.getErrorMessage() != null && !row.getErrorMessage().isBlank()) {
            scanInfo.append("\nNote: ").append(row.getErrorMessage());
        }
        Label scanLabel = new Label(scanInfo.toString());
        scanLabel.setWrapText(true);

        box.getChildren().addAll(riskLabel, descLabel, scanLabel);

        if (targets != null && !targets.isEmpty()) {
            Label targetsHeader = new Label("Target locations (preview only, nothing is deleted from this dialog):");
            targetsHeader.setWrapText(true);
            TextArea targetsArea = new TextArea(String.join("\n", targets));
            targetsArea.setEditable(false);
            targetsArea.setPrefRowCount(Math.min(6, targets.size() + 1));
            targetsArea.setWrapText(true);
            box.getChildren().addAll(targetsHeader, targetsArea);
        } else {
            Label hint = new Label("Tip: run Scan, then use Clean Selected to remove files. "
                    + "HIGH-risk categories ask for extra confirmation.");
            hint.setWrapText(true);
            box.getChildren().add(hint);
        }

        getDialogPane().setContent(box);
        getDialogPane().setPrefWidth(480);
    }
}
