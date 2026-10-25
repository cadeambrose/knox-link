package knoxlink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The device's own temperatures (chip and battery), where the system lets apps read them: Android
 * and Linux handhelds expose them under /sys. Elsewhere (a Windows PC) there's simply nothing to show.
 */
final class Device {
    private static final long EVERY_MS = 2000;
    private static List<File> cpu, gpu;
    private static long nextAt;
    private static Map<String, Object> last = new LinkedHashMap<>();

    private Device() {}

    /** {cpu=°C, gpu=°C, bat=°C}, whichever can be read; refreshed at most every 2 seconds. */
    static synchronized Map<String, Object> temps() {
        long now = System.currentTimeMillis();
        if (now < nextAt) return last;
        nextAt = now + EVERY_MS;
        if (cpu == null) scan();
        Map<String, Object> m = new LinkedHashMap<>();
        double c = hottest(cpu), g = hottest(gpu);
        if (c > 0) m.put("cpu", Math.round(c));
        if (g > 0) m.put("gpu", Math.round(g));
        double b = read(new File("/sys/class/power_supply/battery/temp"));
        if (b > 0) m.put("bat", Math.round(b > 200 ? b / 10.0 : b));   // tenths of a degree
        last = m;
        return m;
    }

    private static void scan() {
        cpu = new ArrayList<>();
        gpu = new ArrayList<>();
        File[] zones = new File("/sys/class/thermal").listFiles((d, n) -> n.startsWith("thermal_zone"));
        if (zones == null) return;
        for (File z : zones) {
            String type;
            try {
                type = new String(Files.readAllBytes(new File(z, "type").toPath()), StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                continue;
            }
            File temp = new File(z, "temp");
            double v = read(temp);
            if (v <= 0 || v > 150) continue;
            // Qualcomm names: cpu-1-9 (a core), cpuss-0 (a cluster), gpuss-4; other chips: cpu_therm, gpu-thermal...
            if (type.startsWith("cpu") || type.contains("cpu-") || type.contains("cpu_")) cpu.add(temp);
            else if (type.startsWith("gpu") || type.contains("gpu")) gpu.add(temp);
        }
    }

    private static double hottest(List<File> files) {
        double max = 0;
        if (files == null) return 0;
        for (File f : files) max = Math.max(max, read(f));
        return max;
    }

    /** A sysfs temperature in °C (they're usually millidegrees), or 0. */
    private static double read(File f) {
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim();
            double v = Double.parseDouble(s);
            if (v > 1000) v /= 1000.0;   // millidegrees
            return v > 0 && v < 1500 ? v : 0;   // tenths (battery) are scaled by the caller
        } catch (Exception e) {
            return 0;
        }
    }
}
