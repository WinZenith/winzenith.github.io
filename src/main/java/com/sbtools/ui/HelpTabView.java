package com.sbtools.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class HelpTabView extends VBox {

    public HelpTabView() {
        setPadding(new Insets(24));
        setSpacing(16);
        getStyleClass().add("settings-view");

        Label header = new Label("Help & FAQ");
        header.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #f8f8f2;");
        getChildren().add(header);

        Label intro = new Label("Click on any section below to expand it and learn how to use that feature.");
        intro.setStyle("-fx-font-size: 13px; -fx-text-fill: #6272a4;");
        intro.setWrapText(true);
        getChildren().add(intro);

        getChildren().add(createFaqSection("Dashboard",
                "The Dashboard is your system overview hub. Click \"Scan for Issues\" to run a comprehensive check across drivers, software updates, and cleanup opportunities. " +
                "Results appear in a summary table grouped by category, showing how many issues were found in each area. " +
                "You can click on any category row to jump directly to the corresponding tab for more details. " +
                "The Dashboard does not make any changes to your system — it only reports what it finds."));

        getChildren().add(createFaqSection("Drivers",
                "The Drivers tab scans your installed drivers using Windows pnputil and compares them against OEM catalogs from Dell, AMD, ASUS, and Broadcom. " +
                "Click \"Scan Drivers\" to detect available updates. Each result shows the current version, the available version, and a severity indicator (Critical, Recommended, or Optional). " +
                "Select one or more drivers and click \"Install\" to download and install updates. " +
                "Before installing, the app can auto-backup your current drivers and create a system restore point if those options are enabled. " +
                "Use the exclusion list to permanently hide drivers you do not want to update — right-click a driver and select \"Ignore.\" " +
                "The \"Update History\" section shows all previously installed driver updates with dates and statuses."));

        getChildren().add(createFaqSection("Backup/Rollback",
                "This tab has two sub-sections: Driver Backup and System Restore. " +
                "Driver Backup creates a snapshot of your currently installed drivers using pnputil. Click \"Create Backup\" to save a timestamped copy to your configured backup directory. " +
                "You can browse existing backups and restore individual drivers or all drivers from a selected backup. " +
                "System Restore creates Windows restore points that capture your entire system state. Click \"Create Restore Point\" to make one manually. " +
                "The table lists all available restore points with creation dates. Select one and click \"Restore\" to roll back your system. " +
                "Driver backups are safer for targeted rollbacks, while system restore points are useful for recovering from major changes."));

        getChildren().add(createFaqSection("Software Update",
                "This tab uses Windows Package Manager (winget) to scan for available updates to your installed applications. " +
                "Click \"Scan\" to check for updates. Results show the app name, current version, available version, and a severity level (Critical, Important, or Minor). " +
                "Select individual apps or click \"Update All\" to install updates in batch. " +
                "Before each update, the app can create a system restore point automatically if enabled. " +
                "After installation, leftover installer files are cleaned up to save disk space. " +
                "Apps you choose to skip appear in the \"Skipped\" list and won't be suggested again until you unskip them."));

        getChildren().add(createFaqSection("System Information",
                "The System Information tab displays detailed hardware and software data about your computer. " +
                "It starts with an Overview tab showing key specifications at a glance. " +
                "Information is organized into sections: Overview, Operating System, CPU, GPU, RAM, Storage, BIOS, Motherboard, Network Adapters, Audio Devices, Battery, Temperatures, and Other Devices. " +
                "Each section shows key specifications such as model names, clock speeds, memory sizes, and firmware versions. " +
                "Use the search bar on the Others tab to filter devices by name. " +
                "Click \"Export...\" to save system information as a TXT, JSON, or HTML file. " +
                "Click \"Copy All\" to copy the full report to your clipboard for easy sharing. " +
                "A Warnings tab appears if any data could not be collected. " +
                "This tab is read-only and does not modify any system settings."));

        getChildren().add(createFaqSection("Uninstaller",
                "The Uninstaller tab lists all installed applications, including both traditional Desktop/Win32 programs and Windows Store (AppX) apps. " +
                "Use the search bar to find a specific application. Select an app and click \"Uninstall\" for a standard removal, or \"Force Uninstall\" for stubborn applications. " +
                "Select 2+ apps and click \"Uninstall Selected\" to remove them one-by-one — each app still asks for confirmation, mode, and restore point. " +
                "After uninstallation, the app scans for leftover files, folders, and registry entries that were not removed. " +
                "You can review and selectively delete these leftovers: files go to the Recycle Bin by default (recoverable), registry keys are backed up to .reg first. " +
                "Only exact matches are pre-selected; heuristic matches show a badge and stay unchecked. Use \"High-confidence only\" to reset to the safest selection. " +
                "Use \"History\" to audit past uninstalls and \"Export...\" to save the app list as CSV. " +
                "If an entry has no uninstaller, the app offers a winget fallback. " +
                "Be cautious with force uninstall — it may remove shared components that other applications depend on."));

        getChildren().add(createFaqSection("Startup Items/Services",
                "This tab manages programs and services that run automatically when Windows starts. " +
                "Items are organized into three sub-tabs: Startup apps (Registry Run/RunOnce keys + Startup Folder), Scheduled Tasks, and Windows Services. " +
                "Startup Folder items (User and Common) are merged into the Startup apps tab and can be toggled by renaming the shortcut (.disabled). " +
                "Select an item and click \"Enable/Disable\" to prevent it from starting automatically, or \"Delete\" to remove it entirely (a backup is created). " +
                "Use the search bar to quickly find specific items. " +
                "You can back up your current startup configuration and restore it later via \"Backups & Restore\" — useful before making bulk changes. " +
                "Disabling unknown services or HKLM entries requires administrator privileges and can cause system instability, so only modify items you recognize."));

        getChildren().add(createFaqSection("System Cleanup",
                "The System Cleanup tab scans your computer for unnecessary files that consume disk space. " +
                "Click \"Scan\" to analyze 40 categories such as temporary files, browser caches, Windows update leftovers, and log files. " +
                "Each category shows how much space can be reclaimed plus its Risk level (Low/Medium/High), scan status, and duration. Use the search box and risk filter to narrow results. " +
                "Double-click a row (or right-click > View details) to preview what will be cleaned. Select the categories you want to clean and click \"Clean Selected\" (HIGH-risk categories are unchecked by default and ask for extra confirmation). " +
                "Use \"Refresh selected\" to re-scan only selected rows, \"Export...\" to save the scan as CSV, and right-click > Ignore to skip a category in future scans. " +
                "The Registry Defragmentation tool (in a sub-tab) compacts your Windows registry to improve system performance. " +
                "Registry defragmentation requires a restart to complete. " +
                "Always review scan results before cleaning to avoid removing files you still need."));

        getChildren().add(createFaqSection("Duplicate Files",
                "This tab finds duplicate files by comparing file contents with SHA-256 hashes. "
                + "Click Add... to choose folder(s) on any drive (e.g., Documents, Downloads, Photos), then click Scan. "
                + "Results are grouped by identical content: the safest keeper (newest on a non-system drive preferred) is shown as the keeper, "
                + "and older copies are listed in the detail pane where you can uncheck individual files. "
                + "System and app folders on any drive (Windows, Program Files, ProgramData, AppData, WindowsApps, System Volume Information, $Recycle.Bin, Recovery, EFI, Boot) "
                + "are automatically excluded and cannot be added. "
                + "Select groups and per-file copies, then click Clean Selected. You can move files to the Recycle Bin (recommended, recoverable) "
                + "or delete permanently (requires typing DELETE). A System Restore point can be created automatically if enabled in settings. "
                + "Always review the deletable list in the bottom pane before confirming."));

        getChildren().add(createFaqSection("Disk Tools",
                "The Disk Tools tab contains three utilities: Defragmentation, File Shredder, and Free Space Wipe. " +
                "Defragmentation reorganizes fragmented files on your hard drives for faster access. The visualization shows the current fragmentation state with a color-coded grid. " +
                "Note: Defragmentation is only beneficial for traditional HDDs — do not defragment SSDs. " +
                "File Shredder securely deletes files by overwriting them multiple times, making recovery impossible. " +
                "Free Space Wipe overwrites the free space on a drive so that previously deleted files cannot be recovered. " +
                "Both shredding and wiping are irreversible — make sure you have backups before proceeding."));

        getChildren().add(createFaqSection("Browser Extensions",
                "This tab scans all major browsers installed on your system — Chrome, Edge, Firefox, Brave, Opera, and Vivaldi — for installed extensions. " +
                "Use the browser filter dropdown at the top to show extensions from specific browsers only. " +
                "Each extension shows its name, version, description, and current state (enabled or disabled). " +
                "Select an extension and click \"Disable\" to turn it off without uninstalling, or \"Enable\" to reactivate it. " +
                "If a browser is running, a warning will be shown; changes take effect after restarting the browser. " +
                "Ignored extensions are hidden from toggle operations but can be managed via the \"Manage Ignored\" button."));

        getChildren().add(createFaqSection("Network Optimizer",
                "The Network Optimizer has seven sub-tabs. " +
                "Network Adapters lists all network interfaces on your system with their status, speed, and IP configuration. " +
                "Optimization applies TCP/IP tuning presets (Default, Maximum Performance, Maximum Stability, Gaming) to improve network performance. " +
                "Select a preset and click \"Apply\" — changes are applied via PowerShell and take effect immediately. " +
                "DNS & Cache lets you flush the DNS resolver cache, reset the network stack, and set custom DNS server addresses (e.g., Google 8.8.8.8 or Cloudflare 1.1.1.1). " +
                "Adapter Settings displays advanced properties for a selected network adapter (read-only). " +
                "Wi-Fi shows the current wireless connection details and saved profiles, with disconnect and forget options. " +
                "Connection Overview provides the full ipconfig /all output for detailed network configuration. " +
                "Change History tracks the last network operations performed. " +
                "All network changes can be reverted to defaults using the \"Reset\" button."));

        Label contactTitle = new Label("Contact us");
        contactTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #8be9fd; -fx-padding: 0 0 6 0;");
        Label contactBody = new Label("Have a question, found a bug, or want to suggest an improvement? We'd love to hear from you. Send us an email at:");
        contactBody.setStyle("-fx-font-size: 13px; -fx-text-fill: #f8f8f2;");
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
                showAlert("Email address copied to clipboard: winzenith_tools@yahoo.com");
            }
        });
        VBox contactBox = new VBox(4, contactTitle, contactBody, emailLink);
        contactBox.setPadding(new Insets(12));
        contactBox.setStyle("-fx-background-color: #282a36; -fx-background-radius: 6; -fx-border-color: #44475a; -fx-border-radius: 6;");
        getChildren().add(contactBox);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);
    }

    private TitledPane createFaqSection(String title, String content) {
        Label body = new Label(content);
        body.setStyle("-fx-font-size: 13px; -fx-text-fill: #f8f8f2; -fx-padding: 8 0 0 0;");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);

        VBox container = new VBox(body);
        container.setStyle("-fx-background-color: #21222c;");
        container.setPadding(new Insets(4, 8, 8, 8));

        TitledPane pane = new TitledPane(title, container);
        pane.setAnimated(true);
        pane.setExpanded(false);
        pane.setCollapsible(true);
        pane.setStyle(
                "-fx-background-color: #282a36; -fx-background-radius: 6; " +
                "-fx-border-color: #44475a; -fx-border-radius: 6; " +
                "-fx-text-fill: #8be9fd; -fx-font-size: 14px; -fx-font-weight: bold;");
        return pane;
    }

    private void showAlert(String message) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION, message, javafx.scene.control.ButtonType.OK);
        alert.setTitle("WinZenith");
        alert.showAndWait();
    }
}
