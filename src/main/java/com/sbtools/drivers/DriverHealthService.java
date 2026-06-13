package com.sbtools.drivers;

import com.sbtools.drivers.model.InstalledDriver;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DriverHealthService {

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})");

    public static DriverHealthScore scoreDriver(InstalledDriver driver) {
        int score = 100;
        StringBuilder details = new StringBuilder();

        if (driver.driverVersion() != null && !driver.driverVersion().isEmpty()) {
            int agePenalty = estimateAgePenalty(driver);
            score -= agePenalty;
            details.append("Age penalty: -").append(agePenalty).append(" pts\n");
        }

        String provider = driver.provider() != null ? driver.provider().toLowerCase() : "";
        if (provider.contains("microsoft") || provider.contains("windows")) {
            score -= 0;
            details.append("WHQL: Microsoft signed (0 penalty)\n");
        } else if (provider.contains("advanced micro devices") || provider.contains("nvidia")
                || provider.contains("intel") || provider.contains("realtek")
                || provider.contains("lenovo") || provider.contains("dell")
                || provider.contains("hewlett-packard") || provider.contains("asus")
                || provider.contains("synaptics") || provider.contains("broadcom")
                || provider.contains("qualcomm")) {
            score -= 5;
            details.append("WHQL: OEM signed (-5 pts)\n");
        } else {
            score -= 15;
            details.append("WHQL: Unknown provider (-15 pts)\n");
        }

        if (driver.status() != null && !driver.status().isEmpty() && !"OK".equalsIgnoreCase(driver.status())) {
            score -= 20;
            details.append("Status: ").append(driver.status()).append(" (-20 pts)\n");
        }

        if (driver.hardwareIds() != null && !driver.hardwareIds().isEmpty()) {
            if (driver.hardwareIds().contains("CC_") || driver.hardwareIds().contains("GENERIC")) {
                score -= 15;
                details.append("Generic driver detected (-15 pts)\n");
            }
        }

        return new DriverHealthScore(Math.max(0, Math.min(100, score)), details.toString().trim());
    }

    private static int estimateAgePenalty(InstalledDriver driver) {
        String ver = driver.driverVersion();
        if (ver == null || ver.isEmpty()) return 10;

        String[] parts = ver.split("\\.");
        if (parts.length >= 2) {
            try {
                int major = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
                int minor = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                if (major < 10) return 15;
                if (major < 20) return 10;
                if (major < 30) return 5;
                return 0;
            } catch (NumberFormatException ignored) {}
        }
        return 5;
    }

    public record DriverHealthScore(int score, String details) {
        public String getLabel() {
            if (score >= 80) return "Excellent";
            if (score >= 60) return "Good";
            if (score >= 40) return "Fair";
            return "Poor";
        }

        public String getColorStyle() {
            if (score >= 80) return "-fx-text-fill: #50fa7b; -fx-font-weight: bold;";
            if (score >= 60) return "-fx-text-fill: #f1fa8c; -fx-font-weight: bold;";
            if (score >= 40) return "-fx-text-fill: #ffb86c; -fx-font-weight: bold;";
            return "-fx-text-fill: #ff5555; -fx-font-weight: bold;";
        }
    }
}
