package knoxlink;

/** Console logging with a fixed prefix, so lines are easy to find in console.txt. */
final class Log {
    private Log() {}

    static void info(String msg) {
        System.out.println("[KnoxLink] " + msg);
    }

    static void error(String msg, Throwable t) {
        System.out.println("[KnoxLink] ERROR " + msg + (t == null ? "" : ": " + t));
        if (t != null) t.printStackTrace(System.out);
    }
}
