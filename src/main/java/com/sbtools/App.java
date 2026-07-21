package com.sbtools;

import com.sbtools.license.EulaDialog;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.ui.*;
import com.sbtools.update.UpdateChecker;

import atlantafx.base.theme.Dracula;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import com.sbtools.util.*;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.sbtools.util.ProcessManager;

public class App extends Application {

    private final SettingsStore settingsStore = new SettingsStore();
    private final UpdateChecker updateChecker = new UpdateChecker();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    private static final String[] TAB_NAMES = {
            "Dashboard", "Drivers", "Backup/Rollback", "Software Update",
            "System Information", "Uninstaller", "Startup items/services",
            "System cleanup", "Duplicate Files", "Disk Tools",
            "Browser Extensions", "Network Optimizer"
    };

    private BorderPane root;
    private VBox sidebar;
    private Node[] tabViews;
    private UIButton[] tabButtons;
    private UIButton helpBtn;
    private UIButton updateBtn;
    private HelpTabView helpTab;
    private Image logoImage;
    private int selectedTab = 0;
    private AppSettings appSettings;
    private volatile boolean checkingForUpdate = false;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new Dracula().getUserAgentStylesheet());

        AppLogger.init();
        DataMigration.migrateIfNeeded();
        AppSettings settings = settingsStore.load();

        if (!settings.eulaAccepted()) {
            showEula(settings);
        }

        boolean admin = AdminCheck.isRunningAsAdmin();
        if (!admin && AppPaths.isWindows()) {
            AppLogger.info("Requesting administrator privileges...");
            try {
                AdminCheck.requestElevation();
            } catch (IOException ex) {
                AppLogger.warning("Failed to request elevation: " + ex.getMessage());
            }
            Platform.exit();
            return;
        }

        logoImage = new Image(getClass().getResourceAsStream("/logo-ico.png"));

        appSettings = settings;
        tabViews = new Node[TAB_NAMES.length];

        root = new BorderPane();
        sidebar = new VBox(6);
        sidebar.setPadding(new Insets(16));
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_LEFT);

        helpTab = new HelpTabView();

        buildSidebar();

        root.setLeft(sidebar);
        root.setCenter(createTab(0));

        if (!AppPaths.isWindows()) {
            new Alert(Alert.AlertType.WARNING,
                    AppInfo.DISPLAY_NAME + " is designed for Windows only.").showAndWait();
        }

        Scene scene = new Scene(root, 960, 600);
        scene.getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());
        stage.setTitle(AppInfo.DISPLAY_NAME);
        stage.getIcons().add(logoImage);
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();

        if (settings.autoCheckForUpdates() && com.sbtools.util.AppInfo.isPackaged()) {
            updateChecker.checkForUpdateAsync(() -> {
                UpdateChecker.UpdateResult result = updateChecker.getCachedResult();
                if (result.isUpdateAvailable()) {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                            "A new version v" + result.latestVersion() + " is available.\nDo you want to download it?",
                            ButtonType.YES, ButtonType.NO);
                    confirm.setTitle("Update Available");
                    confirm.setHeaderText("Update Available");

                    if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                        downloadAndExtract(result);
                    }
                }
            });
        }
    }

    private void buildSidebar() {
        sidebar.getChildren().clear();

        ImageView logoView = new ImageView(logoImage);
        logoView.setFitHeight(48);
        logoView.setFitWidth(48);
        logoView.setPreserveRatio(true);

        Label appTitle = new Label("WinZenith");
        appTitle.getStyleClass().addAll("label", "large");
        appTitle.setStyle("-fx-text-fill: #2AE061; -fx-font-size: 20px; -fx-font-weight: bold; -fx-font-family: 'Segoe UI', 'Orbitron', sans-serif; -fx-letter-spacing: 1px; -fx-padding: 8 0 4 0;");

        Separator sep = new Separator();
        sep.setStyle("-fx-padding: 4 0 4 0;");

        tabButtons = new UIButton[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            tabButtons[i] = createTabButton(TAB_NAMES[i]);
            final int idx = i;
            tabButtons[i].setOnAction(e -> {
                selectedTab = idx;
                selectTab(tabButtons[idx]);
                root.setCenter(createTab(idx));
            });
        }

        helpBtn = UIButton.secondary("\u2753 Help");
        helpBtn.setOnAction(e -> {
            selectTab(helpBtn);
            root.setCenter(helpTab);
        });

        sidebar.getChildren().addAll(
                logoView, appTitle, sep
        );
        sidebar.getChildren().addAll(tabButtons);
        Label versionLabel = new Label("v" + AppInfo.getVersion());
        versionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #6272a4; -fx-padding: 4 0 0 0;");

        updateBtn = UIButton.secondary("\u2B50 Check for Updates");
        updateBtn.setOnAction(e -> {
            if (checkingForUpdate) return;
            checkingForUpdate = true;
            updateBtn.setDisable(true);
            updateBtn.setText("Checking...");

            updateChecker.checkForUpdateAsync(() -> {
                checkingForUpdate = false;
                updateBtn.setDisable(false);
                updateBtn.setText("\u2B50 Check for Updates");

                UpdateChecker.UpdateResult result = updateChecker.getCachedResult();

                if (result.isUpdateAvailable()) {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                            "A new version v" + result.latestVersion() + " is available.\nDo you want to download it?",
                            ButtonType.YES, ButtonType.NO);
                    confirm.setTitle("Update Available");
                    confirm.setHeaderText("Update Available");

                    if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                        downloadAndExtract(result);
                    }
                } else if (result.isUnknown()) {
                    Alert warn = new Alert(Alert.AlertType.WARNING,
                            "Could not check for updates.\nPlease check your internet connection and try again.");
                    warn.setTitle("Update Check Failed");
                    warn.setHeaderText(null);
                    warn.showAndWait();
                } else {
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            "Your version is up to date.");
                    info.setTitle("No Updates");
                    info.setHeaderText(null);
                    info.showAndWait();
                }
            });
        });

        sidebar.getChildren().addAll(
                new Separator(),
                helpBtn,
                updateBtn,
                versionLabel
        );

        if (tabButtons.length > 0) {
            selectTab(tabButtons[selectedTab]);
        }
    }

    private UIButton createTabButton(String name) {
        if ("Dashboard".equals(name)) {
            return UIButton.primary(name);
        }
        return UIButton.secondary(name);
    }

    private Node createTab(int index) {
        if (tabViews[index] != null) {
            return tabViews[index];
        }
        tabViews[index] = switch (index) {
            case 0 -> new DashboardTabView(busy, AdminCheck::isRunningAsAdmin);
            case 1 -> new DriversTabView(busy, AdminCheck::isRunningAsAdmin);
            case 2 -> new BackupRestoreTabView(busy, AdminCheck::isRunningAsAdmin);
            case 3 -> new SoftwareUpdatesTabView(busy, AdminCheck::isRunningAsAdmin);
            case 4 -> new SystemInfoTabView(busy, AdminCheck::isRunningAsAdmin);
            case 5 -> new UninstallerTabView(busy, AdminCheck::isRunningAsAdmin);
            case 6 -> new StartupTabView(busy, AdminCheck::isRunningAsAdmin);
            case 7 -> new CleanerTabView(busy, AdminCheck::isRunningAsAdmin, settingsStore);
            case 8 -> new DuplicateFilesTabView(AdminCheck::isRunningAsAdmin);
            case 9 -> new DiskToolsTabView(AdminCheck::isRunningAsAdmin);
            case 10 -> new BrowserExtensionsTabView(AdminCheck::isRunningAsAdmin, settingsStore);
            case 11 -> new NetworkOptimizerTabView(busy, AdminCheck::isRunningAsAdmin, settingsStore, appSettings,
                    updatedSettings -> this.appSettings = updatedSettings);
            default -> throw new IllegalArgumentException("Unknown tab index: " + index);
        };
        return tabViews[index];
    }

    private void selectTab(UIButton selected) {
        for (UIButton btn : tabButtons) {
            btn.setStyleType(UIButton.ButtonStyle.SECONDARY);
        }
        helpBtn.setStyleType(UIButton.ButtonStyle.SECONDARY);
        selected.setStyleType(UIButton.ButtonStyle.PRIMARY);
    }

    private void downloadAndExtract(UpdateChecker.UpdateResult result) {
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle("Downloading Update");
        progressDialog.setHeaderText("Downloading v" + result.latestVersion() + "...");
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(360);
        Label statusLabel = new Label("Connecting...");

        VBox box = new VBox(12, progressBar, statusLabel);
        box.setPadding(new Insets(20));
        box.setMinWidth(400);
        progressDialog.getDialogPane().setContent(box);

        AtomicBoolean cancelled = new AtomicBoolean(false);

        progressDialog.getDialogPane().getButtonTypes().stream()
                .filter(bt -> bt.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE)
                .findFirst()
                .ifPresent(bt -> {
                    Button cancelBtn = (Button) progressDialog.getDialogPane().lookupButton(bt);
                    cancelBtn.setOnAction(e -> {
                        cancelled.set(true);
                        progressDialog.close();
                    });
                });

        progressDialog.setOnCloseRequest(e -> {
            cancelled.set(true);
        });

        Thread t = new Thread(() -> {
            Path tempDir = null;
            HttpURLConnection conn = null;
            try {
                String urlStr = result.downloadUrl();
                if (urlStr == null || urlStr.isBlank()) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Download URL unavailable.");
                        progressDialog.getDialogPane().getButtonTypes().clear();
                        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
                    });
                    return;
                }

                Platform.runLater(() -> statusLabel.setText("Downloading..."));

                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", AppInfo.DISPLAY_NAME + "/" + AppInfo.getVersion());
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int contentLength = conn.getContentLength();
                String raw = conn.getURL().getFile();
                String filename = raw.substring(raw.lastIndexOf('/') + 1);
                if (filename == null || filename.isBlank()) filename = "update.zip";

                tempDir = Files.createTempDirectory("WinZenith-update-");
                Path zipPath = tempDir.resolve(filename);

                try (InputStream in = conn.getInputStream();
                     java.io.OutputStream out = Files.newOutputStream(zipPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long total = 0;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelled.get()) {
                            Platform.runLater(() -> {
                                statusLabel.setText("Download cancelled.");
                                progressDialog.close();
                            });
                            return;
                        }
                        out.write(buffer, 0, read);
                        total += read;
                        if (contentLength > 0) {
                            double prog = Math.min(1.0, (double) total / contentLength);
                            final double p = prog;
                            Platform.runLater(() -> progressBar.setProgress(p));
                        } else {
                            Platform.runLater(() -> progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS));
                        }
                    }
                    if (contentLength > 0 && total != contentLength) {
                        throw new IOException("Download incomplete: expected " + contentLength
                                + " bytes but received " + total + " bytes.");
                    }
                }

                if (cancelled.get()) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Download cancelled.");
                        progressDialog.close();
                    });
                    return;
                }

                Platform.runLater(() -> statusLabel.setText("Extracting..."));

                Path appDir = getAppDirectory();
                String folderName = "WinZenith-v" + result.latestVersion();
                Path extractDir = appDir.resolve(folderName);
                Files.createDirectories(extractDir);

                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (cancelled.get()) {
                            Platform.runLater(() -> {
                                statusLabel.setText("Extraction cancelled.");
                                progressDialog.close();
                            });
                            return;
                        }
                        Path target = extractDir.resolve(entry.getName()).normalize();
                        if (!target.startsWith(extractDir)) {
                            throw new IOException("Zip entry path outside target directory: " + entry.getName());
                        }
                        if (entry.isDirectory()) {
                            Files.createDirectories(target);
                        } else {
                            Files.createDirectories(target.getParent());
                            try (java.io.OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = zis.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                }
                            }
                        }
                        zis.closeEntry();
                    }
                }

                Path finalExe = extractDir.resolve("WinZenith.exe");
                boolean hasExe = Files.exists(finalExe);

                Platform.runLater(() -> {
                    progressDialog.close();

                    String message = "Update downloaded and extracted to:\n" + extractDir + "\n\n"
                            + "Please close WinZenith and run the new version."
                            + (hasExe ? "\n\nWinZenith.exe is ready in that folder." : "");

                    Alert done = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
                    done.setTitle("Update Ready");
                    done.setHeaderText("Update Downloaded");

                    ButtonType openFolderBtn = new ButtonType("Open Folder", ButtonBar.ButtonData.OTHER);
                    done.getDialogPane().getButtonTypes().add(1, openFolderBtn);

                    done.showAndWait().ifPresent(btn -> {
                        if (btn == openFolderBtn) {
                            try {
                                if (Desktop.isDesktopSupported()) {
                                    Desktop.getDesktop().open(extractDir.toFile());
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                });

                if (tempDir != null) {
                    cleanupTempDir(tempDir);
                }

            } catch (Exception ex) {
                if (tempDir != null) {
                    cleanupTempDir(tempDir);
                }
                Platform.runLater(() -> {
                    statusLabel.setText("Failed: " + ex.getMessage());
                    progressDialog.getDialogPane().getButtonTypes().clear();
                    progressDialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
                });
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, "UpdateDownloader");
        t.setDaemon(true);
        t.start();

        progressDialog.showAndWait();
    }

    private Path getAppDirectory() {
        try {
            String command = ProcessHandle.current().info().command().orElse(null);
            if (command != null) {
                Path exePath = Path.of(command);
                if (Files.exists(exePath)) {
                    return exePath.getParent();
                }
            }
        } catch (Exception ignored) {}

        try {
            java.security.CodeSource cs = App.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                Path codePath = Path.of(cs.getLocation().toURI());
                if (Files.isDirectory(codePath)) {
                    return codePath;
                }
                return codePath.getParent();
            }
        } catch (Exception ignored) {}

        return Path.of(System.getProperty("user.home"));
    }

    private static void cleanupTempDir(Path tempDir) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
                Files.walk(tempDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }, "TempDirCleanup");
        t.setDaemon(true);
        t.start();
    }

    private void showEula(AppSettings settings) {
        EulaDialog eula = new EulaDialog();
        if (eula.showAndWait().orElse(null) != EulaDialog.ACCEPT) {
            Platform.exit();
            return;
        }
        try {
            settingsStore.save(settings.toBuilder().eulaAccepted(true).build());
        } catch (IOException e) {
            AppLogger.error("Failed to save EULA acceptance", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void stop() throws Exception {
        // Ensure any tracked child processes are terminated when the app stops
        try {
            AppLogger.info("Application stopping; shutting down tracked processes...");
        } catch (Throwable ignored) {}
        if (tabViews != null) {
            for (Node view : tabViews) {
                if (view == null) continue;
                if (view instanceof DashboardTabView dtv) {
                    dtv.dispose();
                } else if (view instanceof DriversTabView dtv2) {
                    dtv2.dispose();
                } else if (view instanceof SoftwareUpdatesTabView suv) {
                    suv.dispose();
                } else if (view instanceof UninstallerTabView utv) {
                    utv.dispose();
                } else if (view instanceof StartupTabView stv) {
                    stv.dispose();
                } else if (view instanceof SystemInfoTabView sitv) {
                    sitv.dispose();
                } else if (view instanceof BrowserExtensionsTabView betv) {
                    betv.dispose();
                } else if (view instanceof NetworkOptimizerTabView notv) {
                    notv.dispose();
                } else if (view instanceof DuplicateFilesTabView dftv) {
                    dftv.dispose();
                }
            }
        }
        ProcessManager.shutdownAll();
        AppExecutors.shutdown();
        super.stop();
    }
}
