package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.function.BooleanSupplier;

public class NetworkOptimizerTabView extends BorderPane {

    private final NetworkOptimizerService service = new NetworkOptimizerService();
    private final BooleanSupplier adminCheck;
    private final AdaptersPanel adaptersPanel;
    private final OptimizationPanel optimizationPanel;
    private final DnsCachePanel dnsCachePanel;
    private final AdapterSettingsPanel adapterSettingsPanel;
    private final WiFiPanel wiFiPanel;
    private final ConnectionOverviewPanel connectionOverviewPanel;
    private final ChangeLogPanel changeLogPanel;
    private final Label adminWarningLabel = new Label("Not running as Administrator — network changes (optimize, DNS, adapter enable/disable, reset, WoWlan forget) will fail. Right-click WinZenith.exe → Run as administrator.");
    private final Label rebootLabel = new Label();
    private final javafx.scene.control.Button rebootClearBtn = new javafx.scene.control.Button("Dismiss");

    public NetworkOptimizerTabView(BooleanProperty busy, BooleanSupplier adminCheck,
                                   SettingsStore settingsStore, AppSettings currentSettings) {
        this(busy, adminCheck, settingsStore, currentSettings, null);
    }

    public NetworkOptimizerTabView(BooleanProperty busy, BooleanSupplier adminCheck,
                                   SettingsStore settingsStore, AppSettings currentSettings,
                                   java.util.function.Consumer<AppSettings> onSettingsSaved) {
        this.adminCheck = adminCheck != null ? adminCheck : () -> false;
        Label statusLabel = new Label("Ready.");

        adminWarningLabel.setWrapText(true);
        adminWarningLabel.setMaxWidth(Double.MAX_VALUE);
        adminWarningLabel.setStyle("-fx-padding: 6 16; -fx-background-color: #3d2e1a; -fx-text-fill: #ffb86c; -fx-font-size: 11px;");
        adminWarningLabel.getStyleClass().addAll("label", "text-muted");
        updateAdminWarning();

        rebootLabel.setWrapText(true);
        rebootLabel.setMaxWidth(Double.MAX_VALUE);
        rebootLabel.setStyle("-fx-padding: 6 16; -fx-background-color: #44272a; -fx-text-fill: #ff9d9d; -fx-font-size: 11px;");
        rebootClearBtn.setOnAction(e -> {
            try { service.clearRebootRequired(); } catch (Exception ignored) {}
            updateRebootBanner();
        });
        updateRebootBanner();

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        adaptersPanel = new AdaptersPanel(service, busy, this.adminCheck);
        optimizationPanel = new OptimizationPanel(service, busy, settingsStore, currentSettings, statusLabel, onSettingsSaved, this.adminCheck);
        dnsCachePanel = new DnsCachePanel(service, busy, statusLabel, this.adminCheck);
        adapterSettingsPanel = new AdapterSettingsPanel(service, busy);
        wiFiPanel = new WiFiPanel(service, busy, this.adminCheck);
        connectionOverviewPanel = new ConnectionOverviewPanel(service, busy);
        changeLogPanel = new ChangeLogPanel(service, busy);

        Tab adaptersTab = new Tab("Network Adapters", adaptersPanel);
        Tab optimizationTab = new Tab("Optimization", optimizationPanel);
        Tab dnsTab = new Tab("DNS & Cache", dnsCachePanel);
        Tab adapterSettingsTab = new Tab("Adapter Settings", adapterSettingsPanel);
        Tab wifiTab = new Tab("Wi-Fi", wiFiPanel);
        Tab connectionOverviewTab = new Tab("Connection Overview", connectionOverviewPanel);
        Tab changeHistoryTab = new Tab("Change History", changeLogPanel);

        tabPane.getTabs().addAll(
                adaptersTab, optimizationTab, dnsTab, adapterSettingsTab,
                wifiTab, connectionOverviewTab, changeHistoryTab
        );

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null) return;
            updateRebootBanner();
            if (sel == adaptersTab) {
                adaptersPanel.loadAdapters();
            } else if (sel == optimizationTab) {
                optimizationPanel.refreshPresetSelection();
                updateAdminWarning();
            } else if (sel == dnsTab) {
                dnsCachePanel.refreshAdapters();
                updateAdminWarning();
            } else if (sel == adapterSettingsTab) {
                adapterSettingsPanel.refreshAdapters();
            } else if (sel == wifiTab) {
                wiFiPanel.loadCurrentInfo();
                // loadProfiles() previously collided with busy flag; now safe with separate busy
                Platform.runLater(wiFiPanel::loadProfiles);
            } else if (sel == connectionOverviewTab) {
                connectionOverviewPanel.loadOverview();
            } else if (sel == changeHistoryTab) {
                changeLogPanel.loadEntries();
            }
        });

        javafx.scene.layout.HBox rebootBox = new javafx.scene.layout.HBox(8, rebootLabel, rebootClearBtn);
        rebootBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        rebootBox.setStyle("-fx-padding: 0 8 0 0; -fx-background-color: #44272a;");
        rebootBox.managedProperty().bind(rebootLabel.managedProperty());
        rebootBox.visibleProperty().bind(rebootLabel.visibleProperty());
        VBox topContainer = new VBox(adminWarningLabel, rebootBox, tabPane);
        VBox.setVgrow(tabPane, javafx.scene.layout.Priority.ALWAYS);
        setCenter(topContainer);

        // Initial load after scene is ready
        Platform.runLater(() -> {
            adaptersPanel.loadAdapters();
            updateRebootBanner();
        });
    }

    private void updateAdminWarning() {
        boolean isWin = AppPaths.isWindows();
        boolean isAdmin = false;
        try { isAdmin = adminCheck.getAsBoolean(); } catch (Exception ignored) {}
        boolean show = isWin && !isAdmin;
        adminWarningLabel.setVisible(show);
        adminWarningLabel.setManaged(show);
    }

    private void updateRebootBanner() {
        boolean required = false;
        String reason = "";
        try {
            required = service.isRebootRequired();
            reason = service.rebootReason();
        } catch (Exception ignored) {}
        final boolean show = required;
        final String text = "Reboot required"
                + (reason != null && !reason.isBlank() ? " — " + reason : "")
                + ". TCP/Winsock changes may not fully apply until restart.";
        Platform.runLater(() -> {
            rebootLabel.setText(text);
            rebootLabel.setVisible(show);
            rebootLabel.setManaged(show);
            rebootClearBtn.setVisible(show);
            rebootClearBtn.setManaged(show);
        });
    }

    /** Allows child panels to refresh the reboot banner after reset operations. */
    public void refreshRebootBanner() {
        updateRebootBanner();
    }

    public void dispose() {
        if (adaptersPanel != null) adaptersPanel.dispose();
        if (optimizationPanel != null) optimizationPanel.dispose();
        if (dnsCachePanel != null) dnsCachePanel.dispose();
        if (adapterSettingsPanel != null) adapterSettingsPanel.dispose();
        if (wiFiPanel != null) wiFiPanel.dispose();
        if (connectionOverviewPanel != null) connectionOverviewPanel.dispose();
        if (changeLogPanel != null) changeLogPanel.dispose();
    }
}
