package knoxlink;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * Server settings, kept in Zomboid/KnoxLink/server.properties so they survive restarts.
 *
 * port  - first port to try (the next few are tried if it is taken)
 * lan   - true: other devices on the network may connect (with the PIN); false: this device only
 * pin   - four digits a phone or tablet has to enter once; generated on first start
 */
final class Config {
    int port = 7878;
    boolean lan = true;
    String pin;

    private final File file;

    private Config(File file) {
        this.file = file;
    }

    static Config load() {
        File dir = new File(Paths.zomboidDir(), "KnoxLink");
        Config c = new Config(new File(dir, "server.properties"));
        Properties p = new Properties();
        if (c.file.isFile()) {
            try (InputStream in = new FileInputStream(c.file)) {
                p.load(in);
            } catch (Exception e) {
                Log.error("could not read " + c.file, e);
            }
        }
        try {
            c.port = Integer.parseInt(p.getProperty("port", "7878").trim());
        } catch (NumberFormatException ignored) {
        }
        c.lan = !"false".equalsIgnoreCase(p.getProperty("lan", "true").trim());
        c.pin = p.getProperty("pin", "").trim();
        if (!c.pin.matches("\\d{4}")) {
            c.pin = newPin();
            c.save();
        }
        return c;
    }

    static String newPin() {
        return String.format("%04d", new SecureRandom().nextInt(10000));
    }

    void save() {
        Properties p = new Properties();
        p.setProperty("port", Integer.toString(port));
        p.setProperty("lan", Boolean.toString(lan));
        p.setProperty("pin", pin);
        try {
            file.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(file)) {
                p.store(out, "Knox Link server settings");
            }
        } catch (Exception e) {
            Log.error("could not write " + file, e);
        }
    }
}
