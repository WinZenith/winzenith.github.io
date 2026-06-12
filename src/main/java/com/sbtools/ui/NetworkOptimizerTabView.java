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

    public NetworkOptimizerTabView(BooleanProperty busy, BooleanSupplier adminCheck,
                                   SettingsStore settingsStore, AppSettings currentSettings) {
        Label statusLabel = new Label("Ready.");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        adaptersPanel = new AdaptersPanel(service, busy);
        dnsCachePanel = new DnsCachePanel(service, busy, statusLabel);

        tabPane.getTabs().addAll(
                new Tab("Network Adapters", adaptersPanel),
                new Tab("Optimization", new OptimizationPanel(service, busy, settingsStore, currentSettings, statusLabel)),
                new Tab("DNS & Cache", dnsCachePanel),
                new Tab("Connection Overview", new ConnectionOverviewPanel(service, busy))
        );

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && sel.getText().equals("Network Adapters") && adaptersPanel.lookup(".table-view") != null) {
                adaptersPanel.loadAdapters();
            }
            if (sel != null && sel.getText().equals("DNS & Cache")) {
                dnsCachePanel.refreshAdapters();
            }
        });

        setCenter(tabPane);
    }
}
