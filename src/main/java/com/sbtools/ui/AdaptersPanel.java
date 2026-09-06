package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkAdapterRow;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.util.AppExecutors;
import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

class AdaptersPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final ObservableList<NetworkAdapterRow> adapterRows = FXCollections.observableArrayList();
    private final javafx.collections.transformation.FilteredList<NetworkAdapterRow> filteredRows =
            new javafx.collections.transformation.FilteredList<>(adapterRows, r -> true);
    private final TableView<NetworkAdapterRow> adapterTable = new TableView<>(filteredRows);
    private final Label statusLabel = new Label("Ready.");
    private final javafx.scene.control.TextField filterField = new javafx.scene.control.TextField();
    private volatile Future<?> currentTask;
    private final java.util.concurrent.atomic.AtomicBoolean isLoading = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastLoadMillis = 0;

    AdaptersPanel(NetworkOptimizerService service, BooleanProperty busy, BooleanSupplier adminCheck) {
        this.service = service;
        this.busy = busy;
        this.adminCheck = adminCheck != null ? adminCheck : () -> false;
        getChildren().addAll(buildToolbar(), buildTable());
        VBox.setVgrow(adapterTable, Priority.ALWAYS);
        setPadding(new Insets(12, 16, 12, 16));
    }

    // Backward compatible
    AdaptersPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this(service, busy, () -> false);
    }

    void loadAdapters() {
        // 5s TTL cache: tab switches within 5s reuse rows without spawning PowerShell.
        long now = System.currentTimeMillis();
        if (now - lastLoadMillis < 5000 && !adapterRows.isEmpty()) {
            applyFilter();
            statusLabel.setText("Found " + filteredRows.size() + " adapter(s) (cached).");
            return;
        }
        if (!isLoading.compareAndSet(false, true)) {
            statusLabel.setText("Please wait, loading...");
            return;
        }
        if (busy.get()) {
            isLoading.set(false);
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        busy.set(true);
        statusLabel.setText("Loading network adapters...");

        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                List<NetworkAdapterRow> adapters = service.listAdapters();
                lastLoadMillis = System.currentTimeMillis();
                Platform.runLater(() -> {
                    adapterRows.setAll(adapters);
                    applyFilter();
                    statusLabel.setText("Found " + adapters.size() + " adapter(s).");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to load adapters", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed to load adapters.");
                    new Alert(Alert.AlertType.ERROR, "Failed to load adapters:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    isLoading.set(false);
                });
            }
        });
    }

    void dispose() {
        Future<?> t = currentTask;
        if (t != null) t.cancel(true);
        isLoading.set(false);
    }

    private void applyFilter() {
        String q = filterField.getText() != null ? filterField.getText().trim().toLowerCase() : "";
        if (q.isEmpty()) {
            filteredRows.setPredicate(r -> true);
        } else {
            filteredRows.setPredicate(r ->
                    (r.getName() != null && r.getName().toLowerCase().contains(q))
                    || (r.getDescription() != null && r.getDescription().toLowerCase().contains(q))
                    || (r.getStatus() != null && r.getStatus().toLowerCase().contains(q))
                    || (r.getIpAddress() != null && r.getIpAddress().toLowerCase().contains(q)));
        }
    }

    private HBox buildToolbar() {
        Button refreshBtn = UIButton.primary("Refresh");
        Button enableBtn = UIButton.success("Enable");
        Button disableBtn = UIButton.secondary("Disable");
        Button renewIpBtn = UIButton.primary("Renew IP");
        Button exportBtn = UIButton.secondary("Export CSV");

        enableBtn.setDisable(true);
        disableBtn.setDisable(true);
        renewIpBtn.setDisable(true);

        filterField.setPromptText("Filter adapters…");
        filterField.setPrefWidth(160);
        filterField.textProperty().addListener((obs, o, n) -> applyFilter());

        refreshBtn.setOnAction(e -> {
            lastLoadMillis = 0;
            loadAdapters();
        });
        enableBtn.setOnAction(e -> setAdapterState(true));
        disableBtn.setOnAction(e -> setAdapterState(false));
        renewIpBtn.setOnAction(e -> renewSelectedIp());
        exportBtn.setOnAction(e -> exportCsv());

        adapterTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean hasSel = sel != null && !busy.get();
            enableBtn.setDisable(!hasSel);
            disableBtn.setDisable(!hasSel);
            renewIpBtn.setDisable(!hasSel);
        });

        busy.addListener((obs, old, nv) -> {
            refreshBtn.setDisable(nv);
            boolean hasSelection = adapterTable.getSelectionModel().getSelectedItem() != null;
            enableBtn.setDisable(nv || !hasSelection);
            disableBtn.setDisable(nv || !hasSelection);
            renewIpBtn.setDisable(nv || !hasSelection);
        });

        HBox toolbar = new HBox(12, refreshBtn, enableBtn, disableBtn, renewIpBtn, exportBtn, filterField, statusLabel);
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
        ipCol.setPrefWidth(110);

        TableColumn<NetworkAdapterRow, String> dhcpCol = new TableColumn<>("DHCP");
        dhcpCol.setCellValueFactory(c -> c.getValue().dhcpProperty());
        dhcpCol.setPrefWidth(70);

        TableColumn<NetworkAdapterRow, String> gwCol = new TableColumn<>("Gateway");
        gwCol.setCellValueFactory(c -> c.getValue().gatewayProperty());
        gwCol.setPrefWidth(110);

        TableColumn<NetworkAdapterRow, String> dnsCol = new TableColumn<>("DNS");
        dnsCol.setCellValueFactory(c -> c.getValue().dnsServersProperty());
        dnsCol.setPrefWidth(140);

        adapterTable.getColumns().addAll(nameCol, descCol, statusCol, speedCol, macCol, ipCol, dhcpCol, gwCol, dnsCol);
        return adapterTable;
    }

    private boolean requireAdmin() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Administrator privileges required.\n\nRight-click WinZenith.exe → Run as administrator.").showAndWait();
            return false;
        }
        return true;
    }

    private void setAdapterState(boolean enable) {
        NetworkAdapterRow selected = adapterTable.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;
        if (!requireAdmin()) return;
        if (!enable) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Disable adapter '" + selected.getName() + "'?\n\nYou may lose network connectivity until it is re-enabled.",
                    javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
            confirm.setTitle("Confirm Disable");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(javafx.scene.control.ButtonType.NO) != javafx.scene.control.ButtonType.YES) return;
        }

        busy.set(true);
        String action = enable ? "Enable" : "Disable";
        statusLabel.setText(action + " " + selected.getName() + "...");

        AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.setAdapterState(selected.getName(), enable);
                Platform.runLater(() -> {
                    if (result.success()) {
                        statusLabel.setText(result.message());
                    } else {
                        statusLabel.setText("Failed to " + action.toLowerCase() + " adapter.");
                        new Alert(Alert.AlertType.ERROR, result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Failed to " + action.toLowerCase() + " adapter.");
                    new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    lastLoadMillis = 0;
                    // defer refresh after busy released
                    Platform.runLater(this::loadAdapters);
                });
            }
        });
    }

    private void exportCsv() {
        if (filteredRows.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Nothing to export.").showAndWait();
            return;
        }
        AppExecutors.ioPool().submit(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("Name,Description,Status,Speed,MAC,IP,DHCP,Gateway,DNS\n");
                for (NetworkAdapterRow r : filteredRows) {
                    sb.append(csv(r.getName())).append(",").append(csv(r.getDescription())).append(",")
                            .append(csv(r.getStatus())).append(",").append(csv(r.getLinkSpeed())).append(",")
                            .append(csv(r.getMacAddress())).append(",").append(csv(r.getIpAddress())).append(",")
                            .append(csv(r.getDhcp())).append(",").append(csv(r.getGateway())).append(",")
                            .append(csv(r.getDnsServers())).append("\n");
                }
                java.nio.file.Path base = com.sbtools.util.AppPaths.portableBaseDir();
                java.nio.file.Path dir = base != null
                        ? base.resolve(".winzenith").resolve("exports")
                        : java.nio.file.Path.of(System.getProperty("user.home"), ".winzenith", "exports");
                java.nio.file.Files.createDirectories(dir);
                String stamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                java.nio.file.Path out = dir.resolve("adapters-" + stamp + ".csv");
                java.nio.file.Files.writeString(out, sb.toString());
                Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION, "Saved to:\n" + out).showAndWait());
            } catch (Exception e) {
                AppLogger.warning("Adapter export failed: " + e.getMessage());
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Export failed: " + e.getMessage()).showAndWait());
            }
        });
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"");
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }

    private void renewSelectedIp() {
        NetworkAdapterRow selected = adapterTable.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;
        if (!requireAdmin()) return;

        busy.set(true);
        statusLabel.setText("Renewing IP for " + selected.getName() + "...");

        AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.renewIp(selected.getName());
                Platform.runLater(() -> {
                    statusLabel.setText(result.success() ? result.message() : "IP renewal failed.");
                    Alert a = new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : ""));
                    a.showAndWait();
                    if (result.success()) {
                        Platform.runLater(this::loadAdapters);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("IP renewal failed.");
                    new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    lastLoadMillis = 0;
                });
            }
        });
    }
}
