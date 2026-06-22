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
    private final DnsCachePanel dnsCachePanel;
    private final AdapterSettingsPanel adapterSettingsPanel;
    private final WiFiPanel wiFiPanel;
    private final ConnectionMonitorPanel connectionMonitorPanel;
    private final ChangeLogPanel changeLogPanel;

    public NetworkOptimizerTabView(BooleanProperty busy, BooleanSupplier adminCheck,
                                   SettingsStore settingsStore, AppSettings currentSettings) {
        Label statusLabel = new Label("Ready.");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        adaptersPanel = new AdaptersPanel(service, busy);
        dnsCachePanel = new DnsCachePanel(service, busy, statusLabel);
        adapterSettingsPanel = new AdapterSettingsPanel(service, busy);
        wiFiPanel = new WiFiPanel(service, busy);
        connectionMonitorPanel = new ConnectionMonitorPanel(service, busy);
        changeLogPanel = new ChangeLogPanel(service, busy);

        tabPane.getTabs().addAll(
                new Tab("Network Adapters", adaptersPanel),
                new Tab("Optimization", new OptimizationPanel(service, busy, settingsStore, currentSettings, statusLabel)),
                new Tab("DNS & Cache", dnsCachePanel),
                new Tab("Adapter Settings", adapterSettingsPanel),
                new Tab("Wi-Fi", wiFiPanel),
                new Tab("Connection Monitor", connectionMonitorPanel),
                new Tab("Connection Overview", new ConnectionOverviewPanel(service, busy)),
                new Tab("Change History", changeLogPanel)
        );

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && sel.getText().equals("Network Adapters") && adaptersPanel.lookup(".table-view") != null) {
                adaptersPanel.loadAdapters();
            }
            if (sel != null && sel.getText().equals("DNS & Cache")) {
                dnsCachePanel.refreshAdapters();
            }
            if (sel != null && sel.getText().equals("Adapter Settings")) {
                adapterSettingsPanel.refreshAdapters();
            }
            if (sel != null && sel.getText().equals("Wi-Fi")) {
                wiFiPanel.loadCurrentInfo();
                wiFiPanel.loadProfiles();
            }
            if (sel != null && sel.getText().equals("Connection Monitor")) {
                connectionMonitorPanel.loadConnections();
            }
            if (sel != null && sel.getText().equals("Change History")) {
                changeLogPanel.loadEntries();
            }
        });

        setCenter(tabPane);
    }
}
