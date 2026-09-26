package com.sbtools.ui;

import com.sbtools.i18n.Messages;
import javafx.geometry.Insets;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class HelpTabView extends VBox {

    private static final String[] SECTIONS = {
            "Dashboard",
            "Drivers",
            "Backup/rollback",
            "Software update",
            "System information",
            "Uninstaller",
            "Startup items/services",
            "System cleanup",
            "Disk tools",
            "Browser extensions",
            "Network optimizer"
    };

    public HelpTabView() {
        setPadding(new Insets(24));
        setSpacing(16);
        getStyleClass().add("settings-view");
        Messages.addListener(this::rebuild);
        rebuild();
    }

    private void rebuild() {
        getChildren().clear();

        Label header = new TrLabel();
        I18n.assign(header, "Help & FAQ");
        header.getStyleClass().addAll("label", "large", "theme-body-text");
        header.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        getChildren().add(header);

        Label intro = new TrLabel();
        I18n.assign(intro, "help.intro");
        intro.setStyle("-fx-font-size: 13px; -fx-text-fill: #6272a4;");
        intro.setWrapText(true);
        getChildren().add(intro);

        for (int i = 0; i < SECTIONS.length; i++) {
            getChildren().add(createFaqSection(SECTIONS[i], "help.section." + i));
        }

        Label contactTitle = new TrLabel();
        I18n.assign(contactTitle, "Contact us");
        contactTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #8be9fd; -fx-padding: 0 0 6 0;");
        Label contactBody = new TrLabel();
        I18n.assign(contactBody, "help.contact");
        contactBody.getStyleClass().add("theme-body-text");
        contactBody.setStyle("-fx-font-size: 13px;");
        contactBody.setWrapText(true);
        Hyperlink emailLink = new Hyperlink("winzenith_tools@yahoo.com");
        emailLink.setStyle("-fx-font-size: 13px; -fx-text-fill: #50fa7b;");
        emailLink.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().mail(new java.net.URI("mailto:winzenith_tools@yahoo.com"));
            } catch (Exception ex) {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString("winzenith_tools@yahoo.com");
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                showAlert(I18n.t("help.email.copied"));
            }
        });
        VBox contactBox = new VBox(4, contactTitle, contactBody, emailLink);
        contactBox.setPadding(new Insets(12));
        contactBox.getStyleClass().add("theme-surface");
        contactBox.setStyle("-fx-background-radius: 6; -fx-border-radius: 6;");
        getChildren().add(contactBox);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);
    }

    private TitledPane createFaqSection(String titleKey, String bodyKey) {
        Label body = new TrLabel();
        I18n.assign(body, bodyKey);
        body.getStyleClass().add("theme-body-text");
        body.setStyle("-fx-font-size: 13px; -fx-padding: 8 0 0 0;");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);

        VBox container = new VBox(body);
        container.getStyleClass().add("theme-surface-deep");
        container.setPadding(new Insets(4, 8, 8, 8));

        TitledPane pane = new TitledPane();
        I18n.assign(pane, titleKey);
        pane.setContent(container);
        pane.setAnimated(true);
        pane.setExpanded(false);
        pane.setCollapsible(true);
        pane.getStyleClass().add("theme-surface");
        pane.setStyle(
                "-fx-background-radius: 6; -fx-border-radius: 6; " +
                "-fx-text-fill: #8be9fd; -fx-font-size: 14px; -fx-font-weight: bold;");
        return pane;
    }

    private void showAlert(String message) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION, message, javafx.scene.control.ButtonType.OK);
        alert.setTitle(I18n.t("WinZenith"));
        alert.showAndWait();
    }
}
