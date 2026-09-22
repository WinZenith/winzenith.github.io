package com.sbtools.defrag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks the Disk tools critical fixes. Run as a main class. */
public final class DiskToolsCriticalFixCheck {

    public static void main(String[] args) throws Exception {
        checkPolicy();
        checkSmartFixture();
        checkMediaTypes();
        System.out.println("DiskToolsCriticalFixCheck ok");
    }

    private static void checkPolicy() {
        check(!DiskOpPolicy.confirmedHdd(null), "null is not HDD");
        check(!DiskOpPolicy.confirmedHdd(""), "blank is not HDD");
        check(!DiskOpPolicy.confirmedHdd("Unknown"), "unknown is not HDD");
        check(!DiskOpPolicy.confirmedHdd("Unspecified"), "unspecified is not HDD");
        check(!DiskOpPolicy.confirmedHdd("SSD"), "SSD is not HDD");
        check(DiskOpPolicy.confirmedHdd("HDD"), "HDD is confirmed");
        check(DiskOpPolicy.confirmedHdd(" hdd "), "HDD trim/case");
        check(DiskOpPolicy.cancelledDefragMessage(1, 3).equals(
                "Intelligent Defrag cancelled (1 of 3 finished)."), "cancel message counts finished drives");
    }

    private static void checkSmartFixture() throws Exception {
        byte[] smart = new byte[512];
        smart[0] = 0x10;
        smart[1] = 0x00;
        putAttr(smart, 2, 5, 3);
        putAttr(smart, 14, 197, 12);
        putAttr(smart, 26, 198, 1);
        Path fixture = Files.createTempFile("wz-smart-", ".bin");
        try {
            Files.write(fixture, smart);
            String out = runScript("disk-health.ps1", "-SelfTest", "-FixturePath", fixture.toString());
            check(out.contains("\"reallocated\":3"), "reallocated from offset 2: " + out);
            check(out.contains("\"pending\":12"), "pending from raw+5: " + out);
            check(out.contains("\"uncorrectable\":1"), "uncorrectable: " + out);
            check(out.contains("\"health\":\"Critical\""), "pending>10 is Critical: " + out);
            check(out.contains("\"nvmeMedia\":\"Caution\""), "media errors are Caution: " + out);
            check(out.contains("\"nvmeWarn\":\"Critical\""), "critical warning is Critical: " + out);
            check(out.contains("\"nvmeOk\":\"Healthy\""), "clean NVMe stays Healthy: " + out);
            check(out.contains("\"nvmeSmartFail\":\"Critical\""), "failed SMART is Critical: " + out);
            check(out.contains("\"nvmeKeep\":\"Critical\""), "media errors must not downgrade Critical: " + out);
            check(out.contains("\"mediaSsd\":\"SSD\""), "spindle 0 is SSD: " + out);
            check(out.contains("\"mediaHdd\":\"HDD\""), "spindle 7200 is HDD: " + out);
        } finally {
            Files.deleteIfExists(fixture);
        }
    }

    private static void checkMediaTypes() throws Exception {
        String out = runScript("get-drives.ps1", "-SelfTest");
        check(out.contains("MEDIA_OK"), "media classification: " + out);
    }

    private static void putAttr(byte[] buf, int at, int id, int raw) {
        buf[at] = (byte) id;
        buf[at + 3] = 100;
        buf[at + 4] = 100;
        buf[at + 5] = (byte) (raw & 0xff);
        buf[at + 6] = (byte) ((raw >> 8) & 0xff);
    }

    private static String runScript(String name, String... args) throws Exception {
        Path script = Path.of("src", "main", "resources", "powershell", name);
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("Run from the project root. Missing " + script.toAbsolutePath());
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-NoLogo");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-File");
        cmd.add(script.toAbsolutePath().toString());
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) throw new AssertionError(name + " exited " + code + ": " + out);
        return out;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
