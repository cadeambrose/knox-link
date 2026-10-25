package knoxlink;

import java.io.File;

import zombie.ZomboidFileSystem;

/** Where the game keeps things. Wrapped so a renamed game method only breaks one place. */
final class Paths {
    private Paths() {}

    /** The user's Zomboid folder (honours -cachedir). */
    static File zomboidDir() {
        String dev = System.getProperty("knoxlink.home");
        if (dev != null) return new File(dev);
        try {
            return new File(ZomboidFileSystem.instance.getCacheDir());
        } catch (Throwable t) {
            return new File(System.getProperty("user.home"), "Zomboid");
        }
    }

    /** A file under the game's media folder, as the game itself would resolve it. */
    static File media(String relative) {
        String dev = System.getProperty("knoxlink.media");
        if (dev != null) return new File(dev, relative);
        try {
            String p = ZomboidFileSystem.instance.getString("media/" + relative);
            if (p != null) {
                File f = new File(p);
                if (f.exists()) return f;
            }
        } catch (Throwable ignored) {
        }
        try {
            return new File(ZomboidFileSystem.instance.getMediaRootPath(), relative);
        } catch (Throwable t) {
            return new File("media", relative);
        }
    }
}
