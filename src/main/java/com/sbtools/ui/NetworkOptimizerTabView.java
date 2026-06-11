package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkAdapterRow;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.netoptimizer.OptimizationPreset;
import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.BooleanSupplier;

public class NetworkOptimizerTabView extends BorderPane {

    private final NetworkOptimizerService service = new NetworkOptimizerService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<NetworkAdapterRow> adapterRows = FXCollections.observableArrayList();
    private final TableView<NetworkAdapterRow> adapterTable = new TableView<>(adapterRows);
    private final Label statusLabel = new Label("Ready.");

    public NetworkOptimizerTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getTabs().addAll(
                new Tab("Network Adapters", buildAdaptersTab()),
                new Tab("Optimization", buildOptimizationTab()),
                new Tab("DNS & Cache", buildDnsTab()),
                new Tab("Connection Overview", buildOverviewTab())
        );

        setCenter(tabPane);
    }

    private VBox buildAdaptersTab() {
        Button refreshBtn = UIButton.primary("Refresh");
        Button enableBtn = UIButton.success("Enable");
        Button disableBtn = UIButton.secondary("Disable");
        Button renewIpBtn = UIButton.primary("Renew IP");

        enableBtn.setDisable(true);
        disableBtn.setDisable(true);
        renewIpBtn.setDisable(true);

        refreshBtn.setOnAction(e -> loadAdapters());
        enableBtn.setOnAction(e -> setAdapterState(true));
        disableBtn.setOnAction(e -> setAdapterState(false));
        renewIpBtn.setOnAction(e -> renewSelectedIp());

        HBox toolbar = new HBox(12, refreshBtn, enableBtn, disableBtn, renewIpBtn, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(12, 16, 12, 16));
        toolbar.getStyleClass().add("toolbar");

        buildAdapterTable();

        adapterTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean hasSel = sel != null && !busy.get();
            enableBtn.setDisable(!hasSel);
            disableBtn.setDisable(!hasSel);
            renewIpBtn.setDisable(!hasSel);
        });

        busy.addListener((obs, old, nv) -> {
            boolean notBusy = !nv;
            refreshBtn.setDisable(nv);
            enableBtn.setDisable(notBusy || adapterTable.getSelectionModel().getSelectedItem() == null);
            disableBtn.setDisable(notBusy || adapterTable.getSelectionModel().getSelectedItem() == null);
            renewIpBtn.setDisable(notBusy || adapterTable.getSelectionModel().getSelectedItem() == null);
        });

        VBox center = new VBox(8, adapterTable);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(adapterTable, Priority.ALWAYS);

        return new VBox(toolbar, center);
    }

    private void buildAdapterTable() {
        adapterTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<NetworkAdapterRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        nameCol.setPrefWidth(150);

        TableColumn<NetworkAdapterRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setPrefWidth(90);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else {
                    setText(item);
                    if ("Up".equalsIgnoreCase(item)) setStyle("-fx-text-fill: #50fa7b; -fx-font-weight: bold;");
                    else setStyle("-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                }
            }
        });

        TableColumn<NetworkAdapterRow, String> speedCol = new TableColumn<>("Speed");
        speedCol.setCellValueFactory(c -> c.getValue().linkSpeedProperty());
        speedCol.setPrefWidth(100);

        TableColumn<NetworkAdapterRow, String> macCol = new TableColumn<>("MAC Address");
        macCol.setCellValueFactory(c -> c.getValue().macAddressProperty());
        macCol.setPrefWidth(140);

        TableColumn<NetworkAdapterRow, String> ipCol = new TableColumn<>("IP Address");
        ipCol.setCellValueFactory(c -> c.getValue().ipAddressProperty());
        ipCol.setPrefWidth(140);

        adapterTable.getColumns().addAll(nameCol, statusCol, speedCol, macCol, ipCol);
    }

    private VBox buildOptimizationTab() {
        ToggleGroup group = new ToggleGroup();
        VBox presetBox = new VBox(8);
        presetBox.setPadding(new Insets(12, 16, 12, 16));

        Label header = new Label("Select Optimization Preset:");
        header.getStyleClass().addAll("label", "large");
        presetBox.getChildren().add(header);

        Label descLabel = new Label("Choose a preset and click Apply.");
        descLabel.setWrapText(true);
        descLabel.setPrefWidth(500);

        for (OptimizationPreset preset : OptimizationPreset.values()) {
            RadioButton rb = new RadioButton(preset.getDisplayName());
            rb.setToggleGroup(group);
            rb.setUserData(preset);
            if (preset == OptimizationPreset.DEFAULT) rb.setSelected(true);
            rb.setOnAction(e -> descLabel.setText(preset.getDescription()));
            presetBox.getChildren().add(rb);
        }

        presetBox.getChildren().add(descLabel);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.setPrefWidth(300);

        Button applyBtn = UIButton.primary("Apply");
        Button resetBtn = UIButton.secondary("Reset to Defaults");

        applyBtn.setOnAction(e -> {
            RadioButton selected = (RadioButton) group.getSelectedToggle();
            if (selected == null) return;
            OptimizationPreset preset = (OptimizationPreset) selected.getUserData();
            applyOptimization(preset, progressBar);
        });

        resetBtn.setOnAction(e -> {
            applyOptimization(OptimizationPreset.DEFAULT, progressBar);
            group.selectToggle(group.getToggles().get(0));
        });

        HBox btnBox = new HBox(12, applyBtn, resetBtn, progressBar);
        btnBox.setAlignment(Pos.CENTER_LEFT);
        btnBox.setPadding(new Insets(12, 16, 12, 16));

        return new VBox(presetBox, btnBox);
    }

    private VBox buildDnsTab() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(12, 16, 12, 16));
        Label header = new Label("DNS & Network Utilities");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        Button flushDnsBtn = UIButton.primary("Flush DNS Cache");
        Button resetStackBtn = UIButton.secondary("Reset Network Stack");
        Button resetWinsockBtn = UIButton.secondary("Reset Winsock");

        flushDnsBtn.setOnAction(e -> {
            if (busy.get()) return;
            busy.set(true);
            statusLabel.setText("Flushing DNS...");
            new Thread(() -> {
                boolean ok = service.flushDnsCache();
                Platform.runLater(() -> {
                    statusLabel.setText(ok ? "DNS cache flushed." : "Flush failed.");
                    new Alert(ok ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            ok ? "DNS cache flushed successfully." : "Failed to flush DNS cache.").showAndWait();
                    busy.set(false);
                });
            }, "net-flush-dns").start();
        });

        resetStackBtn.setOnAction(e -> {
            if (busy.get()) return;
            busy.set(true);
            statusLabel.setText("Resetting network stack...");
            Alert warn = new Alert(Alert.AlertType.WARNING,
                    "Resetting the network stack requires a system reboot. Continue?");
            if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                busy.set(false);
                return;
            }
            new Thread(() -> {
                boolean ok = service.resetNetworkStack();
                Platform.runLater(() -> {
                    statusLabel.setText(ok ? "Network stack reset. Reboot required." : "Reset failed.");
                    new Alert(ok ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            ok ? "Network stack reset. Please reboot your computer." : "Failed to reset network stack.").showAndWait();
                    busy.set(false);
                });
            }, "net-reset-stack").start();
        });

        resetWinsockBtn.setOnAction(e -> {
            if (busy.get()) return;
            busy.set(true);
            statusLabel.setText("Resetting Winsock...");
            Alert warn = new Alert(Alert.AlertType.WARNING,
                    "Resetting Winsock may require a reboot. Continue?");
            if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                busy.set(false);
                return;
            }
            new Thread(() -> {
                boolean ok = service.resetWinsock();
                Platform.runLater(() -> {
                    statusLabel.setText(ok ? "Winsock reset. Reboot recommended." : "Reset failed.");
                    new Alert(ok ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            ok ? "Winsock reset successfully. Rebooting is recommended." : "Failed to reset Winsock.").showAndWait();
                    busy.set(false);
                });
            }, "net-reset-winsock").start();
        });

        content.getChildren().addAll(flushDnsBtn, resetStackBtn, resetWinsockBtn);
        return content;
    }

    private VBox buildOverviewTab() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(12, 16, 12, 16));

        Label header = new Label("Connection Overview");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        TextArea outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        Button refreshBtn = UIButton.primary("Refresh");
        refreshBtn.setOnAction(e -> {
            if (busy.get()) return;
            busy.set(true);
            outputArea.setText("Loading network information...");
            new Thread(() -> {
                String info = service.getIpConfigAll();
                Platform.runLater(() -> {
                    outputArea.setText(info);
                    busy.set(false);
                });
            }, "net-overview").start();
        });

        content.getChildren().addAll(refreshBtn, outputArea);
        return content;
    }

    private void loadAdapters() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Loading network adapters...");

        new Thread(() -> {
            try {
                List<NetworkAdapterRow> adapters = service.listAdapters();
                Platform.runLater(() -> {
                    adapterRows.setAll(adapters);
                    statusLabel.setText("Found " + adapters.size() + " adapter(s).");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to load adapters", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed to load adapters.");
                    new Alert(Alert.AlertType.ERROR, "Failed to load adapters:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "net-load-adapters").start();
    }

    private void setAdapterState(boolean enable) {
        NetworkAdapterRow selected = adapterTable.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;

        busy.set(true);
        String action = enable ? "Enable" : "Disable";
        statusLabel.setText(action + " " + selected.getName() + "...");

        new Thread(() -> {
            boolean ok = service.setAdapterState(selected.getName(), enable);
            Platform.runLater(() -> {
                if (ok) {
                    statusLabel.setText(action + "d " + selected.getName());
                    loadAdapters();
                } else {
                    statusLabel.setText("Failed to " + action.toLowerCase() + " adapter.");
                    new Alert(Alert.AlertType.ERROR, "Failed to " + action.toLowerCase() + " adapter.").showAndWait();
                }
                busy.set(false);
            });
        }, "net-set-adapter-state").start();
    }

    private void renewSelectedIp() {
        NetworkAdapterRow selected = adapterTable.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;

        busy.set(true);
        statusLabel.setText("Renewing IP for " + selected.getName() + "...");

        new Thread(() -> {
            boolean ok = service.renewIp(selected.getName());
            Platform.runLater(() -> {
                if (ok) {
                    statusLabel.setText("IP renewed for " + selected.getName());
                    new Alert(Alert.AlertType.INFORMATION, "IP address renewed.").showAndWait();
                } else {
                    statusLabel.setText("IP renewal failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to renew IP address.").showAndWait();
                }
                busy.set(false);
            });
        }, "net-renew-ip").start();
    }

    private void applyOptimization(OptimizationPreset preset, ProgressBar progressBar) {
        if (busy.get()) return;
        busy.set(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText("Applying " + preset.getDisplayName() + "...");

        new Thread(() -> {
            boolean ok = service.applyOptimization(preset);
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                if (ok) {
                    statusLabel.setText("Optimization applied: " + preset.getDisplayName());
                    new Alert(Alert.AlertType.INFORMATION, preset.getDisplayName() + " applied successfully.").showAndWait();
                } else {
                    statusLabel.setText("Optimization failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to apply " + preset.getDisplayName() + ".").showAndWait();
                }
                busy.set(false);
            });
        }, "net-optimize-apply").start();
    }

}
