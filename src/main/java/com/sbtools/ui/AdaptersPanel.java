package com.sbtools.ui;

import com.sbtools.netoptimizer.AdapterStatistics;
import com.sbtools.netoptimizer.NetworkAdapterRow;
import com.sbtools.netoptimizer.NetworkOptimizerService;
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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

class AdaptersPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final ObservableList<NetworkAdapterRow> adapterRows = FXCollections.observableArrayList();
    private final TableView<NetworkAdapterRow> adapterTable = new TableView<>(adapterRows);
    private final Label statusLabel = new Label("Ready.");

    AdaptersPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        getChildren().addAll(buildToolbar(), buildTable());
        VBox.setVgrow(adapterTable, Priority.ALWAYS);
        setPadding(new Insets(12, 16, 12, 16));
    }

    void loadAdapters() {
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

    private HBox buildToolbar() {
        Button refreshBtn = UIButton.primary("Refresh");
        Button enableBtn = UIButton.success("Enable");
        Button disableBtn = UIButton.secondary("Disable");
        Button renewIpBtn = UIButton.primary("Renew IP");
        Button statsBtn = UIButton.secondary("Statistics");

        enableBtn.setDisable(true);
        disableBtn.setDisable(true);
        renewIpBtn.setDisable(true);
        statsBtn.setDisable(true);

        refreshBtn.setOnAction(e -> loadAdapters());
        enableBtn.setOnAction(e -> setAdapterState(true));
        disableBtn.setOnAction(e -> setAdapterState(false));
        renewIpBtn.setOnAction(e -> renewSelectedIp());
        statsBtn.setOnAction(e -> showStatistics());

        adapterTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean hasSel = sel != null && !busy.get();
            enableBtn.setDisable(!hasSel);
            disableBtn.setDisable(!hasSel);
            renewIpBtn.setDisable(!hasSel);
            statsBtn.setDisable(!hasSel);
        });

        busy.addListener((obs, old, nv) -> {
            boolean notBusy = !nv;
            refreshBtn.setDisable(nv);
            enableBtn.setDisable(notBusy || adapterTable.getSelectionModel().getSelectedItem() == null);
            disableBtn.setDisable(notBusy || adapterTable.getSelectionModel().getSelectedItem() == null);
            renewIpBtn.setDisable(notBusy || adapterTable.getSelectionModel().getSelectedItem() == null);
            statsBtn.setDisable(notBusy || adapterTable.getSelectionModel().getSelectedItem() == null);
        });

        HBox toolbar = new HBox(12, refreshBtn, enableBtn, disableBtn, renewIpBtn, statsBtn, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(12, 16, 12, 16));
        toolbar.getStyleClass().add("toolbar");
        return toolbar;
    }

    private TableView<NetworkAdapterRow> buildTable() {
        adapterTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<NetworkAdapterRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        nameCol.setPrefWidth(140);

        TableColumn<NetworkAdapterRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> c.getValue().descriptionProperty());
        descCol.setPrefWidth(180);

        TableColumn<NetworkAdapterRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setPrefWidth(80);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else {
                    setText(item);
                    if ("Up".equalsIgnoreCase(item))
                        setStyle("-fx-text-fill: #50fa7b; -fx-font-weight: bold;");
                    else
                        setStyle("-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                }
            }
        });

        TableColumn<NetworkAdapterRow, String> speedCol = new TableColumn<>("Speed");
        speedCol.setCellValueFactory(c -> c.getValue().linkSpeedProperty());
        speedCol.setPrefWidth(100);

        TableColumn<NetworkAdapterRow, String> macCol = new TableColumn<>("MAC Address");
        macCol.setCellValueFactory(c -> c.getValue().macAddressProperty());
        macCol.setPrefWidth(130);

        TableColumn<NetworkAdapterRow, String> ipCol = new TableColumn<>("IP Address");
        ipCol.setCellValueFactory(c -> c.getValue().ipAddressProperty());
        ipCol.setPrefWidth(130);

        adapterTable.getColumns().addAll(nameCol, descCol, statusCol, speedCol, macCol, ipCol);
        return adapterTable;
    }

    private void setAdapterState(boolean enable) {
        NetworkAdapterRow selected = adapterTable.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;

        busy.set(true);
        String action = enable ? "Enable" : "Disable";
        statusLabel.setText(action + " " + selected.getName() + "...");

        new Thread(() -> {
            var result = service.setAdapterState(selected.getName(), enable);
            Platform.runLater(() -> {
                if (result.success()) {
                    statusLabel.setText(result.message());
                    loadAdapters();
                } else {
                    statusLabel.setText("Failed to " + action.toLowerCase() + " adapter.");
                    new Alert(Alert.AlertType.ERROR, result.message()).showAndWait();
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
            var result = service.renewIp(selected.getName());
            Platform.runLater(() -> {
                statusLabel.setText(result.success() ? result.message() : "IP renewal failed.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                busy.set(false);
            });
        }, "net-renew-ip").start();
    }

    private void showStatistics() {
        NetworkAdapterRow selected = adapterTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Adapter Statistics");
        dialog.setHeaderText("Statistics for " + selected.getName());

        Label content = new Label("Loading statistics...");
        content.setStyle("-fx-font-family: 'Consolas', monospace; -fx-padding: 16;");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);

        Timer refreshTimer = new Timer(true);
        refreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    refreshTimer.cancel();
                    return;
                }
                AdapterStatistics stats = service.getAdapterStatistics(selected.getName());
                Platform.runLater(() -> content.setText(formatStats(stats)));
            }
        }, 0, 5000);

        dialog.setOnCloseRequest(e -> refreshTimer.cancel());
        dialog.show();
    }

    private String formatStats(AdapterStatistics s) {
        return String.format(
                "Bytes Sent:     %s\n" +
                "Bytes Received: %s\n" +
                "Unicast Sent:   %d\n" +
                "Unicast Recv:   %d\n" +
                "Multicast Sent: %d\n" +
                "Multicast Recv: %d\n" +
                "Broadcast Sent: %d\n" +
                "Broadcast Recv: %d\n" +
                "Discarded:      %d\n" +
                "Errors:         %d",
                AdapterStatistics.formatBytes(s.bytesSent()),
                AdapterStatistics.formatBytes(s.bytesReceived()),
                s.unicastPacketsSent(),
                s.unicastPacketsReceived(),
                s.multicastPacketsSent(),
                s.multicastPacketsReceived(),
                s.broadcastPacketsSent(),
                s.broadcastPacketsReceived(),
                s.discardedPackets(),
                s.errorPackets()
        );
    }
}
