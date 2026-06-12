package com.sbtools.util;

public class FormatUtils {

    public static String formatSize(int sizeKB) {
        if (sizeKB <= 0) return "";
        if (sizeKB >= 1024 * 1024) {
            return String.format("%.2f GB", sizeKB / (1024.0 * 1024.0));
        } else if (sizeKB >= 1024) {
            return String.format("%.2f MB", sizeKB / 1024.0);
        } else {
            return sizeKB + " KB";
        }
    }

    public static String formatDate(String dateStr) {
        if (dateStr == null || dateStr.length() < 8) return "";
        try {
            String year = dateStr.substring(0, 4);
            String month = dateStr.substring(4, 6);
            String day = dateStr.substring(6, 8);
            String time = dateStr.length() >= 14
                    ? " " + dateStr.substring(8, 10) + ":" + dateStr.substring(10, 12) + ":" + dateStr.substring(12, 14)
                    : "";
            return year + "-" + month + "-" + day + time;
        } catch (Exception e) {
            return dateStr;
        }
    }
}
