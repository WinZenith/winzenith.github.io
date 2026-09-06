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
                "The Dashboard is your system overview hub. Click \"Scan for issues\" to check Outdated Drivers, Software Updates, and System Cleanup opportunities in parallel. " +
                "Results appear in summary cards plus a table (Category | Issues Found | Size | Source | Status). " +
                "Click a category row to jump directly to that tab. Use \"Stop\" to cancel and \"Retry\" if a category times out or fails. Your last scan is restored on open. " +
                "The Dashboard is read-only — it never changes your system. It honors excluded drivers and skipped software."));

        getChildren().add(createFaqSection("Drivers",
                "The Drivers tab scans installed devices and compares them against catalogs for Nvidia, AMD, Intel, Realtek, Broadcom, Qualcomm, Synaptics, Lenovo, Dell, HP, ASUS and Windows Update. " +
                "Click \"Scan\" to detect updates. Each outdated row shows Device, Current vs Available version, Severity (Critical / Important / Recommended / Optional / Unknown, plus REBOOT and ISSUE badges) and Health (Excellent / Good / Fair / Poor). " +
                "Tick rows and click \"Update Selected\" or \"Update All\", or use per-row \"Update\". Use per-row \"Ignore\" and \"Ignored\" to hide drivers, \"History\" to audit installs, \"Details\" / \"Compare\" for full info, \"Refresh Catalog\" for the latest catalog, and \"Search...\" to filter. \"Backup\" backs up all drivers. \"Stop\" / \"Stop Install\" / \"Stop Backup\" cancel the running operation. " +
                "Admin rights are required. Before install the app runs pre-checks, can auto-backup drivers and create a restore point if enabled, blocks pre-release / untrusted / unsigned packages, and may ask for a manual vendor download. " +
                "Drivers awaiting restart stay listed with a REBOOT badge until you reboot."));

        getChildren().add(createFaqSection("Backup/Rollback",
                "This tab has three sub-tabs. \"Rollback drivers\" lists driver backups created automatically before driver updates. " +
                "Use \"Refresh\", \"Search backups...\", \"Details\", \"Open Folder\", \"Verify\" and \"Repair\" to manage them. Select a row and click \"Revert\" to restore that driver version (admin required, restart may be needed). " +
                "\"Delete\" removes one backup; \"Delete All\" removes all (two-step confirmation — rollback is then impossible). Nothing is deleted automatically. " +
                "\"System restore\" is read-only: click \"Scan\" to list points, \"Create new restore point\" to create one (admin required), and \"Launch restore point\" to open the Windows System Restore wizard — the app never restores or deletes points itself. " +
                "\"Registry backup\" backs up startup-related registry areas: click \"Backup Now\", pick core / extended areas plus optional full-hive export, then \"Restore Selected\" (merges .reg files; .hiv files need manual reg restore) or \"Delete Backup\"."));

        getChildren().add(createFaqSection("Software Update",
                "This tab uses winget plus Windows Update to scan for app and Windows updates. Click \"Scan\" to check (\"Stop scan\" cancels). " +
                "Results show Program, Current / Available Version, Source (winget / WindowsUpdate), Size and Status (no severity levels). " +
                "Use Search, Source filter and \"Failed only\" to narrow results, then \"Select All\" / \"Deselect All\" and \"Update Selected\", or per-row \"Update\". Failed items can be retried with \"Retry Failed\" (max 3 attempts). " +
                "If enabled, the app offers one System Restore Point before the batch. A reboot requirement stops the remaining batch — reboot and re-scan. " +
                "After a successful install you are prompted to delete detected installer files from Downloads to save space. " +
                "Per-row \"Ignore\" or \"Ignored List\" hides apps until unignored. \"History\" audits OK / Failed installs."));

        getChildren().add(createFaqSection("System Information",
                "The System Information tab displays detailed hardware and software data. Click \"Load System Info\" to query, \"Refresh\" for latest, \"Cancel\" to stop. Your last snapshot is shown instantly on open. " +
                "Information is organized into tabs shown only when data exists: Overview, CPU, GPU, RAM, OS, Storage, Motherboard, Network, Audio, Battery, Temperatures, Others, USB Devices, Monitors, Printers, plus Warnings (with collection timings) if anything failed. " +
                "Others uses a category list; Network, Audio, USB, Monitors and Printers each have their own search box (e.g. \"Search adapters...\"). " +
                "Click \"Export...\" to save as TXT, JSON, or HTML, \"Copy All\" for the full report or \"Copy Tab\" for the visible tab. " +
                "A banner appears when not running as admin (temperatures / NVMe may be unavailable). " +
                "This tab is read-only and does not modify any system settings."));

        getChildren().add(createFaqSection("Uninstaller",
                "The Uninstaller tab lists installed apps. Toggle \"Desktop Apps\" / \"Windows Store Apps\", click \"Scan\" (\"Cancel\" stops it), and use \"Search apps...\" to filter. " +
                "Select an app and click \"Uninstall\" (runs the vendor uninstaller, Interactive or Silent), or \"Force Uninstall\" as a last resort without it. " +
                "Select 2+ apps and click \"Uninstall Selected (N)\" to remove them one-by-one — each app still asks for confirmation, mode, and restore point. " +
                "After uninstallation, the app scans for leftover files, folders, and registry entries. Review them in the leftovers dialog: files go to the Recycle Bin by default (recoverable), registry keys are backed up to .reg first (checked by default). " +
                "Only exact matches are pre-selected; heuristic matches show a badge and stay unchecked. Use \"High-confidence only\" to reset to the safest selection, \"Select All\" / \"Deselect All\" / \"Export List...\" as needed. PATH entries are shown read-only. " +
                "Use \"History\" to audit past uninstalls, \"Export...\" to save the app list as CSV, and \"Open Folder\" / \"Copy\" for details. " +
                "If an entry has no uninstaller, the app offers a winget fallback (with confirmation). " +
                "Be cautious with force uninstall — it kills processes and deletes files/registry/Start Menu entries and cannot be undone."));

        getChildren().add(createFaqSection("Startup Items/Services",
                "This tab manages programs and services that run automatically when Windows starts. Items are organized into \"Startup apps\" (Registry Run/RunOnce + Startup Folder), \"Scheduled tasks\", and \"Windows services\" (with counts). " +
                "Startup Folder items (User and Common) are merged into Startup apps and toggled by renaming the shortcut (.disabled). Services cannot be deleted, only toggled. " +
                "Click \"Scan\" (\"Stop\" cancels), then select an item and click \"Enable/Disable\" or \"Delete\". \"Delete\" always creates a backup first; toggling does not. Disabling a critical system service asks for extra confirmation. " +
                "Use per-tab search plus \"Status:\" / \"Impact:\" filters, \"Select high-impact\" to select heavy enabled items, and right-click / double-click for \"Open file location\", \"Copy command\", \"Show details\" and online search. " +
                "The footer shows total estimated boot delay (enabled items only) and last boot time. Use \"Backups & Restore\" to restore or permanently delete backups, and \"Export CSV\" for the visible tab. " +
                "Modifying HKLM / Common Startup, system tasks or services requires administrator rights. Only modify items you recognize."));

        getChildren().add(createFaqSection("System Cleanup",
                "The System Cleanup tab scans for unnecessary files. Click \"Scan\" (\"Cancel\" stops it) to analyze 40 categories such as temporary files, browser caches, Windows update leftovers, and log files. " +
                "Each category shows reclaimable Size / Count plus Risk (Low / Medium / High), Status, and Took time. Use Search and the Risk filter to narrow results. " +
                "Double-click a row (or right-click > \"View details...\") to preview what will be cleaned. Select categories and click \"Clean Selected\" (HIGH-risk categories start unchecked and ask for extra confirmation; iTunes backups / Docker / Windows.old need a second irreversible-delete confirm). " +
                "Use \"Refresh selected\" to re-scan only selected rows, \"Presets...\" for Safe / High Impact / Privacy / Maintenance / Dev Tools selections, \"Export...\" to save the scan as CSV, \"History\" for past sessions, and right-click > \"Ignore category in future scans\" to skip a category. " +
                "If Registry is selected you are offered a .reg backup; a System Restore point can be created automatically if enabled. Some categories require admin. " +
                "There is no registry-defragmentation tool. Always review results before cleaning."));

        getChildren().add(createFaqSection("Duplicate Files",
                "This tab finds duplicate files by comparing contents with SHA-256 hashes (after fast CRC32 and sample-hash pruning). "
                + "Click \"Add...\" to choose folder(s) on any drive (e.g., Documents, Downloads, Photos) — or drag folders onto the list — then click \"Scan\" (\"Stop\" cancels). "
                + "Use \"Min size:\" and \"Types:\" filters to speed up large scans (they apply on the next scan), and \"Keep:\" to choose which copy is kept per group "
                + "(Newest, Oldest, or Shortest path; the safest non-system location always wins first). \"Search:\" filters results, \"Auto-select\" / \"Select All\" / \"Deselect All\" change selection, "
                + "and \"Export CSV...\" saves results. "
                + "Results are grouped by identical content: the keeper is shown as the keeper, "
                + "and other copies are listed in the detail pane where you can tick individual files or click \"Keep instead\" to swap the keeper (plus \"Open\"). "
                + "System and app folders on any drive (Windows, Program Files, ProgramData, AppData, WindowsApps, System Volume Information, $Recycle.Bin, Recovery, EFI, Boot) "
                + "are automatically excluded and cannot be added; scanning an entire system drive is blocked. "
                + "Select groups and per-file copies, then click \"Clean Selected\" (admin required). You can move files to the Recycle Bin (recommended, recoverable) "
                + "or delete permanently (requires typing DELETE). A System Restore point can be created automatically if enabled in settings. "
                + "Scan folders and filters are remembered between runs. "
                + "Always review the deletable list in the bottom pane before confirming."));

        getChildren().add(createFaqSection("Disk Tools",
                "The Disk Tools tab has four inner tabs: Defrag, Disk Health, Benchmark and Secure Erase. " +
                "Defrag: click \"Refresh\", select drives, click \"Analyze Selected\", then \"Intelligent Defrag\" (Mode Auto / Quick / Deep, Filter All / HDD / SSD). Auto runs ReTrim on SSDs and full defrag on HDDs — SSDs are never defragmented. A color-coded grid visualizes fragmentation. Admin required. " +
                "Disk Health: click \"Refresh\" for SMART data (model, temperature, power-on hours, SSD wear, reallocated / pending sectors, host reads/writes). " +
                "Benchmark: pick a drive and Size (32-256 MB), click \"Start Benchmark\" for sequential read/write, 1MB / 4K IOPS and latency (\"Stop\" cancels; needs size + 100 MB free). " +
                "Secure Erase: \"Secure File / Folder Deletion\" (Browse / Add Files, Overwrite Quick 1-pass / Standard 3-pass / Deep 7-pass, \"Secure Delete\" / \"Secure Delete Folder\" / \"Delete All\"), \"Recycle Bin Cleanup\" (\"Refresh\" + \"Secure Wipe Recycle Bin\"), and \"Free Space Wiping\" (\"Start\" / \"Stop\", 1 GB reserve kept, SSD / Unknown types blocked, system drive needs double confirmation, admin required). " +
                "Shredding and wiping are irreversible — in-use files can be scheduled for deletion on next reboot."));

        getChildren().add(createFaqSection("Browser Extensions",
                "This tab scans installed browsers — Chrome, Chrome Canary, Edge, Edge Beta, Edge Dev, Edge Canary, Firefox, Brave, Opera, Opera GX and Vivaldi (plus extra browsers from browser-catalog.json) — for installed extensions. " +
                "Click \"Scan All Browsers\" for a full parallel scan with determinate progress, or pick a browser in the Browser filter and click \"Rescan Browser\" for a fast single-browser refresh. Use \"Cancel\" to stop a running scan or toggle. " +
                "Filter by Browser, Status (Enabled / Disabled / Ignored), Profile (Default, Profile 1, ...) and free-text search (name, description, ID, permissions, version). Tick \"Auto-scan on open\" to scan automatically. " +
                "Each extension shows its name, version, profile, install date, description, and current state (enabled / disabled / ignored). Double-click a row (or right-click > \"View Details\") for the full detail dialog. " +
                "Select rows and click \"Enable\" / \"Disable\" to toggle without uninstalling (browsers must stay closed; the app blocks the toggle while they run, re-checks mid-batch, and verifies the write). " +
                "Right-click offers \"Open Extension Folder\", \"Copy Extension ID\" / \"Copy Profile Path\" / \"Copy Store URL\", \"Open Store Page\", and \"Ignore\" / \"Unignore\". Use \"Select All\" / \"Deselect All\" for the filtered view. " +
                "Use \"Export...\" to save the filtered list as CSV/JSON, \"Restore Backup...\" to roll back a Preferences / extensions.json backup created during a toggle, and \"Manage Ignored\" to review ignored items. " +
                "Changes take effect after restarting the browser. Only store-installed extensions are scanned; unpacked developer-mode extensions are not."));

        getChildren().add(createFaqSection("Network Optimizer",
                "The Network Optimizer has seven sub-tabs (admin required for changes; a \"Reboot required\" banner appears after stack / Winsock resets). "
                + "\"Network Adapters\" lists interfaces with status, speed, IP, DHCP, gateway and DNS; use Filter, \"Refresh\", \"Enable\" / \"Disable\", \"Renew IP\" and \"Export CSV\". "
                + "\"Optimization\" applies TCP/IP presets (Default, Maximum Performance, Maximum Stability, Gaming). "
                + "Use \"Preview Changes\" to diff current vs intended values and \"Show Current TCP/IP Settings\" to inspect; a snapshot is captured automatically before each \"Apply\" (plus optional \"Create system restore point\"), "
                + "and \"Snapshots...\" shows guided restore info (read-only+ mode restores via \"Reset to Defaults\"). "
                + "\"DNS & Cache\": \"Flush DNS Cache\", \"Reset Network Stack\" / \"Reset Winsock\" (reboot banner), per-adapter DNS with \"Apply DNS\" / \"Reset to DHCP\" (presets incl. Google / Cloudflare / OpenDNS / Quad9 + IPv6), "
                + "plus opt-in diagnostics that run only on click: \"Benchmark DNS Now\", \"Probe MTU\" (suggestion only), \"Run Speed Test\", \"Ping\" / \"Traceroute\" (with \"Cancel\" and ping-history chart). "
                + "\"Adapter Settings\" is a read-only enriched view with filter, \"Refresh Adapters\" / \"Refresh Properties\" and \"Export CSV\". "
                + "\"Wi-Fi\" shows current connection + signal history (\"Refresh\"), read-only Nearby Networks survey (\"Scan Nearby Networks\"), and saved profiles with \"Refresh\" / \"Disconnect\" / \"Forget\" plus \"Enable / Disable Wi-Fi\". "
                + "\"Connection Overview\" (read-only) offers ipconfig /all, route print, arp -a and netstat -ano with \"Refresh\", \"Copy to Clipboard\" and \"Save to File...\". "
                + "\"Change History\" (last 100 ops) supports text + OK / Failed filter, details pane, \"Refresh\" / \"Clear History\" / \"Export CSV\" and \"View Snapshots...\"."));

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
