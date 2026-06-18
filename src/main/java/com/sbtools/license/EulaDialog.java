package com.sbtools.license;

import com.sbtools.util.AppInfo;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class EulaDialog extends Dialog<ButtonType> {

    public static final ButtonType ACCEPT = new ButtonType("I Accept", ButtonBar.ButtonData.OK_DONE);

    public EulaDialog() {
        setTitle(AppInfo.DISPLAY_NAME + " - License Agreement");
        setHeaderText("Please read and accept the End User License Agreement");

        String eulaText = loadEulaText();

        TextFlow textFlow = new TextFlow();
        Text text = new Text(eulaText);
        text.setStyle("-fx-font-size: 12px; -fx-fill: #f8f8f2;");
        textFlow.getChildren().add(text);
        textFlow.setStyle("-fx-padding: 10;");

        ScrollPane scrollPane = new ScrollPane(textFlow);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(400);
        scrollPane.setPrefWidth(550);
        scrollPane.setStyle("-fx-background-color: #282a36; -fx-border-color: #44475a;");

        Label consentLabel = new Label("By clicking 'I Accept', you agree to the terms of this Agreement.");
        consentLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #6272a4; -fx-padding: 8 0 0 0;");
        consentLabel.setWrapText(true);

        ButtonType declineBtn = new ButtonType("Decline", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(ACCEPT, declineBtn);

        VBox content = new VBox(10);
        content.setPadding(new Insets(16));
        content.getChildren().addAll(scrollPane, consentLabel);
        getDialogPane().setContent(content);

        Button acceptButton = (Button) getDialogPane().lookupButton(ACCEPT);
        acceptButton.setDefaultButton(true);

        Button declineButton = (Button) getDialogPane().lookupButton(declineBtn);
        declineButton.setCancelButton(true);
    }

    private String loadEulaText() {
        try (InputStream is = getClass().getResourceAsStream("/LICENSE.txt")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return "License Agreement\n\n"
                + "This tool can modify device drivers and system updates. "
                + "Use at your own risk. Incorrect drivers may cause hardware issues. "
                + "Always create backups before updating.\n\n"
                + "THE SOFTWARE IS PROVIDED 'AS IS' WITHOUT WARRANTY OF ANY KIND.\n\n"
                + "By clicking 'I Accept', you agree to the terms of this Agreement.";
    }
}
