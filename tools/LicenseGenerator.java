import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Scanner;

public class LicenseGenerator {

    private static final String CHARSET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int DATE_PART_LEN = 6;
    private static final int HMAC_PART_LEN = 14;
    private static final int TOTAL_LEN = DATE_PART_LEN + HMAC_PART_LEN;
    private static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);
    private static final String SECRET_KEY = "WinZenith-2026-LicKey-HMAC-SHA256!";

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=========================================");
        System.out.println("  WinZenith License Key Generator");
        System.out.println("=========================================");
        System.out.println();

        if (args.length == 1) {
            // CLI mode: java LicenseGenerator 2027-06-14
            LocalDate expiry = LocalDate.parse(args[0]);
            System.out.println("Generated key: " + encode(expiry));
            System.out.println("Expires: " + expiry);
            return;
        }

        // Interactive mode
        while (true) {
            System.out.println("Options:");
            System.out.println("  1. Generate key with expiry date");
            System.out.println("  2. Generate key with duration (months)");
            System.out.println("  3. Validate an existing key");
            System.out.println("  4. Exit");
            System.out.print("\nChoice: ");

            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1" -> {
                    System.out.print("Enter expiry date (YYYY-MM-DD): ");
                    String dateStr = scanner.nextLine().trim();
                    try {
                        LocalDate expiry = LocalDate.parse(dateStr);
                        String key = encode(expiry);
                        System.out.println();
                        System.out.println("License Key:  " + key);
                        System.out.println("Expires:      " + expiry);
                        System.out.println("Days from now: " + ChronoUnit.DAYS.between(LocalDate.now(), expiry));
                        System.out.println();
                    } catch (Exception e) {
                        System.out.println("Invalid date format. Use YYYY-MM-DD.");
                    }
                }
                case "2" -> {
                    System.out.print("Enter duration in months: ");
                    String monthsStr = scanner.nextLine().trim();
                    try {
                        int months = Integer.parseInt(monthsStr);
                        LocalDate expiry = LocalDate.now().plusMonths(months);
                        String key = encode(expiry);
                        System.out.println();
                        System.out.println("License Key:  " + key);
                        System.out.println("Expires:      " + expiry);
                        System.out.println("Duration:     " + months + " months");
                        System.out.println();
                    } catch (Exception e) {
                        System.out.println("Invalid number.");
                    }
                }
                case "3" -> {
                    System.out.print("Enter license key to validate: ");
                    String key = scanner.nextLine().trim();
                    System.out.println();
                    var result = validate(key);
                    if (result.valid) {
                        System.out.println("VALID - Expires: " + result.expiryDate);
                        System.out.println("Days remaining: " + result.daysRemaining);
                    } else if (result.expired) {
                        System.out.println("EXPIRED on: " + result.expiryDate);
                    } else {
                        System.out.println("INVALID - " + result.error);
                    }
                    System.out.println();
                }
                case "4" -> {
                    System.out.println("Bye!");
                    return;
                }
                default -> System.out.println("Invalid choice.");
            }
        }
    }

    private static String encode(LocalDate expiryDate) {
        long days = ChronoUnit.DAYS.between(EPOCH, expiryDate);
        String datePart = encodeBase36(days, DATE_PART_LEN);
        String hmac = computeHmac(datePart);
        String fullCode = datePart + hmac;
        return "SBTOOLS-" + formatWithDashes(fullCode);
    }

    private static ValidationResult validate(String code) {
        if (code == null) return new ValidationResult(false, false, null, 0, "Empty code");
        String cleaned = code.trim().toUpperCase();
        if (cleaned.startsWith("SBTOOLS-")) cleaned = cleaned.substring(8);
        cleaned = cleaned.replace("-", "");
        if (cleaned.length() != TOTAL_LEN) return new ValidationResult(false, false, null, 0, "Invalid length");

        String datePart = cleaned.substring(0, DATE_PART_LEN);
        String hmacPart = cleaned.substring(DATE_PART_LEN);
        String expectedHmac = computeHmac(datePart);

        if (!hmacPart.equals(expectedHmac)) return new ValidationResult(false, false, null, 0, "HMAC mismatch");

        long days;
        try { days = decodeBase36(datePart); } catch (Exception e) {
            return new ValidationResult(false, false, null, 0, "Invalid date encoding");
        }
        LocalDate expiryDate = EPOCH.plusDays(days);
        long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);

        if (expiryDate.isBefore(LocalDate.now())) {
            return new ValidationResult(false, true, expiryDate, 0, null);
        }
        return new ValidationResult(true, false, expiryDate, daysRemaining, null);
    }

    private static String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[10];
            System.arraycopy(hash, 0, truncated, 0, 10);
            String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
            return base64.substring(0, HMAC_PART_LEN);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String encodeBase36(long value, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(CHARSET.charAt((int) (value % CHARSET.length())));
            value /= CHARSET.length();
        }
        return sb.reverse().toString();
    }

    private static long decodeBase36(String s) {
        long result = 0;
        for (char c : s.toCharArray()) {
            int index = CHARSET.indexOf(c);
            if (index < 0) throw new NumberFormatException("Invalid char: " + c);
            result = result * CHARSET.length() + index;
        }
        return result;
    }

    private static String formatWithDashes(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 5 == 0) sb.append('-');
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    record ValidationResult(boolean valid, boolean expired, LocalDate expiryDate, long daysRemaining, String error) {}
}
