package com.sbtools.drivers;

import com.sbtools.drivers.model.InstalledDriver;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class DriverHealthService {

    public static DriverHealthScore scoreDriver(InstalledDriver driver) {
        int score = 100;
        StringBuilder details = new StringBuilder();

        if (driver.releaseDate() != null) {
            int agePenalty = estimateAgePenaltyFromDate(driver.releaseDate());
            if (agePenalty > 0) {
                score -= agePenalty;
                details.append("Age penalty: -").append(agePenalty).append(" pts\n");
            }
        } else {
            details.append("Driver age: unknown (no release date)\n");
        }

        Boolean signed = driver.signed();
        if (signed == null) {
            score -= 5;
            details.append("Signature: unknown (-5 pts)\n");
        } else if (signed) {
            details.append("Windows reports driver as signed\n");
        } else {
            score -= 25;
            details.append("Windows reports driver as unsigned (-25 pts)\n");
        }

        if (driver.status() != null && !driver.status().isEmpty() && !"OK".equalsIgnoreCase(driver.status())) {
            score -= 20;
            details.append("Status: ").append(driver.status()).append(" (-20 pts)\n");
        }

        if (driver.hardwareIds() != null && !driver.hardwareIds().isEmpty()) {
            if (driver.hardwareIds().contains("GENERIC")) {
                score -= 15;
                details.append("Generic driver detected (-15 pts)\n");
            }
        }

        return new DriverHealthScore(Math.max(0, Math.min(100, score)), details.toString().trim());
    }

    private static int estimateAgePenaltyFromDate(LocalDate releaseDate) {
        long daysOld = ChronoUnit.DAYS.between(releaseDate, LocalDate.now());
        if (daysOld < 0) return 0;
        if (daysOld <= 90) return 0;
        if (daysOld <= 180) return 5;
        if (daysOld <= 365) return 10;
        if (daysOld <= 730) return 15;
        return 25;
    }

    public record DriverHealthScore(int score, String details) {
        public String getLabel() {
            if (score >= 80) return "Excellent";
            if (score >= 60) return "Good";
            if (score >= 40) return "Fair";
            return "Poor";
        }

        public String getColorStyle() {
            if (score >= 80) return "-fx-text-fill: #50fa7b;";
            if (score >= 60) return "-fx-text-fill: #8be9fd;";
            if (score >= 40) return "-fx-text-fill: #ffb86c;";
            return "-fx-text-fill: #ff5555;";
        }
    }
}
