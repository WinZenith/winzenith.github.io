package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import javafx.beans.property.BooleanProperty;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;

import java.util.function.BooleanSupplier;

public class NetworkOptimizerTabView extends BorderPane {

    private final NetworkOptimizerService service = new NetworkOptimizerService();
    private final AdaptersPanel adaptersPanel;
    private final OptimizationPanel optimizationPanel;
    private final DnsCachePanel dnsCachePanel;
    private final AdapterSettingsPanel adapterSettingsPanel;
    private final WiFiPanel wiFiPanel;
    private final ConnectionMonitorPanel connectionMonitorPanel;
    private final ConnectionOverviewPanel connectionOverviewPanel;
    private final ChangeLogPanel changeLogPanel;

    public NetworkOptimizerTabView(BooleanProperty busy, BooleanSupplier adminCheck,
                                   SettingsStore settingsStore, AppSettings currentSettings) {
        Label statusLabel = new Label("Ready.");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        adaptersPanel = new AdaptersPanel(service, busy);
        optimizationPanel = new OptimizationPanel(service, busy, settingsStore, currentSettings, statusLabel);
        dnsCachePanel = new DnsCachePanel(service, busy, statusLabel);
        adapterSettingsPanel = new AdapterSettingsPanel(service, busy);
        wiFiPanel = new WiFiPanel(service, busy);
        connectionMonitorPanel = new ConnectionMonitorPanel(service, busy);
        connectionOverviewPanel = new ConnectionOverviewPanel(service, busy);
        changeLogPanel = new ChangeLogPanel(service, busy);

        Tab adaptersTab = new Tab("Network Adapters", adaptersPanel);
        Tab optimizationTab = new Tab("Optimization", optimizationPanel);
        Tab dnsTab = new Tab("DNS & Cache", dnsCachePanel);
        Tab adapterSettingsTab = new Tab("Adapter Settings", adapterSettingsPanel);
        Tab wifiTab = new Tab("Wi-Fi", wiFiPanel);
        Tab connectionMonitorTab = new Tab("Connection Monitor", connectionMonitorPanel);
        Tab connectionOverviewTab = new Tab("Connection Overview", connectionOverviewPanel);
        Tab changeHistoryTab = new Tab("Change History", changeLogPanel);

        tabPane.getTabs().addAll(
                adaptersTab, optimizationTab, dnsTab, adapterSettingsTab,
                wifiTab, connectionMonitorTab, connectionOverviewTab, changeHistoryTab
        );

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null) return;
            if (sel == adaptersTab && adaptersPanel.lookup(".table-view") != null) {
                adaptersPanel.loadAdapters();
            } else if (sel == dnsTab) {
                dnsCachePanel.refreshAdapters();
            } else if (sel == adapterSettingsTab) {
                adapterSettingsPanel.refreshAdapters();
            } else if (sel == wifiTab) {
                wiFiPanel.loadCurrentInfo();
                wiFiPanel.loadProfiles();
            } else if (sel == connectionMonitorTab) {
                connectionMonitorPanel.loadConnections();
            } else if (sel == connectionOverviewTab) {
                connectionOverviewPanel.loadOverview();
            } else if (sel == changeHistoryTab) {
                changeLogPanel.loadEntries();
            }
        });

        setCenter(tabPane);
    }
}
