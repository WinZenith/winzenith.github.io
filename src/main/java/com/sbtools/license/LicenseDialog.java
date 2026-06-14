package com.sbtools.license;

import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;

public class LicenseDialog {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private boolean activated = false;

    public boolean show(LicenseValidator validator) {
        LicenseValidator.LicenseStatus status = validator.check();

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(AppInfo.DISPLAY_NAME + " - License");

        Label statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 14px;");

        var type = status.type();
        if (type == LicenseValidator.LicenseStatus.StatusType.ACTIVE) {
            statusLabel.setText("Status: Active\n"
                    + "Expires: " + status.expiryDate().format(DATE_FMT) + "\n"
                    + "Days remaining: " + status.daysRemaining());
            statusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #50fa7b;");
        } else if (type == LicenseValidator.LicenseStatus.StatusType.EXPIRED) {
            statusLabel.setText("Status: Expired\n"
                    + "Expired on: " + status.expiryDate().format(DATE_FMT));
            statusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #ff5555;");
        } else if (type == LicenseValidator.LicenseStatus.StatusType.NO_LICENSE) {
            statusLabel.setText("Status: No license\n"
                    + "Enter your license code to activate Pro features.");
            statusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #ffb86c;");
        } else if (type == LicenseValidator.LicenseStatus.StatusType.INVALID) {
            statusLabel.setText("Status: Invalid\n" + status.errorMessage());
            statusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #ff5555;");
        } else {
            statusLabel.setText("Status: Unknown");
            statusLabel.setStyle("-fx-font-size: 14px;");
        }

        Label headerLabel = new Label("License Information");
        headerLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #f8f8f2;");

        Label codeLabel = new Label("License Code:");
        codeLabel.setStyle("-fx-font-size: 13px;");
        TextField codeField = new TextField();
        codeField.setPromptText("SBTOOLS-XXXXX-XXXXX-XXXXX-XXXXX");
        codeField.setPrefWidth(320);
        if (!status.type().equals(LicenseValidator.LicenseStatus.StatusType.UNKNOWN)) {
            LicenseStore.LicenseData data = new LicenseStore().load();
            if (!data.isEmpty()) {
                codeField.setText(data.licenseKey());
            }
        }

        HBox codeBox = new HBox(8);
        codeBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(codeField, Priority.ALWAYS);
        codeBox.getChildren().addAll(codeLabel, codeField);

        Label resultLabel = new Label();
        resultLabel.setWrapText(true);
        resultLabel.setStyle("-fx-font-size: 13px;");
        resultLabel.setVisible(false);
        resultLabel.setManaged(false);

        Button activateButton = new Button("Activate");
        activateButton.getStyleClass().addAll("button", "accent");
        activateButton.setPrefWidth(120);

        Button closeButton = new Button("Close");
        closeButton.getStyleClass().addAll("button", "button-outlined");
        closeButton.setPrefWidth(100);
        closeButton.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(12);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(activateButton, closeButton);

        activateButton.setOnAction(e -> {
            String code = codeField.getText().trim();
            if (code.isEmpty()) {
                resultLabel.setText("Please enter a license code.");
                resultLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #ff5555;");
                resultLabel.setVisible(true);
                resultLabel.setManaged(true);
                return;
            }
            LicenseCode.ValidationResult result = LicenseCode.validate(code);
            if (!result.valid() && !result.expired()) {
                resultLabel.setText(result.errorMessage());
                resultLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #ff5555;");
                resultLabel.setVisible(true);
                resultLabel.setManaged(true);
                return;
            }
            try {
                validator.activate(code);
                activated = true;
                AppLogger.info("License activated: " + code);
                resultLabel.setText("License activated! Click Close to continue.");
                resultLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #50fa7b;");
                resultLabel.setVisible(true);
                resultLabel.setManaged(true);
                statusLabel.setText("Status: Active");
                statusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #50fa7b;");
            } catch (Exception ex) {
                AppLogger.error("Failed to activate license", ex);
                resultLabel.setText("Failed: " + ex.getMessage());
                resultLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #ff5555;");
                resultLabel.setVisible(true);
                resultLabel.setManaged(true);
            }
        });

        VBox root = new VBox(12);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #282a36;");
        root.getChildren().addAll(headerLabel, statusLabel, codeBox, resultLabel, buttons);

        Scene scene = new Scene(root, 480, 260);
        dialog.setScene(scene);
        dialog.setResizable(false);
        dialog.showAndWait();

        AppLogger.info("License dialog closed. activated=" + activated);
        return activated;
    }
}
