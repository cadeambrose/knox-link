package knoxlink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small sampling profiler for finding what keeps the game busy (and the device hot): every
 * 50 ms it looks at what each Java thread is doing and counts the busiest code paths.
 * Only answered for requests from the device itself (see KnoxServer).
 */
final class Profiler {
    private Profiler() {}

    private static final int DEPTH = 8;

    /** Java thread name -> Linux thread id, filled in by threads that report themselves (see noteThread). */
    static final Map<String, String> TIDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Records the calling thread's Linux thread id under its Java name (once). */
    static void noteThread() {
        String name = Thread.currentThread().getName();
        if (TIDS.containsKey(name)) return;
        try {
            String link = java.nio.file.Files.readSymbolicLink(java.nio.file.Paths.get("/proc/thread-self")).toString();
            TIDS.put(name, link.substring(link.lastIndexOf('/') + 1));
        } catch (Throwable t) {
            TIDS.put(name, "?");
        }
    }

    /** CPU time per Java thread at the start of a sample (thread id -> nanoseconds), or null. */
    private static Map<Long, long[]> cpuSnapshot() {
        try {
            java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
            if (!mx.isThreadCpuTimeSupported()) return null;
            if (!mx.isThreadCpuTimeEnabled()) mx.setThreadCpuTimeEnabled(true);
            Map<Long, long[]> m = new HashMap<>();
            for (long id : mx.getAllThreadIds()) m.put(id, new long[]{mx.getThreadCpuTime(id), mx.getThreadUserTime(id)});
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String cpuTimes(Map<Long, long[]> start, long millis) {
        if (start == null) return "(per-thread CPU time not available)\n";
        try {
            java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
            List<String[]> rows = new ArrayList<>();
            for (java.lang.management.ThreadInfo ti : mx.getThreadInfo(mx.getAllThreadIds())) {
                if (ti == null || !start.containsKey(ti.getThreadId())) continue;
                long cpu = mx.getThreadCpuTime(ti.getThreadId()) - start.get(ti.getThreadId())[0];
                long pct = Math.round(100.0 * cpu / (millis * 1_000_000.0));
                if (pct >= 2) rows.add(new String[]{String.format("%4d", pct), ti.getThreadName() + " #" + ti.getThreadId() + " (" + ti.getThreadState() + ")"});
            }
            rows.sort((a, b) -> b[0].compareTo(a[0]));
            StringBuilder sb = new StringBuilder("CPU % of one core per Java thread:\n");
            for (String[] r : rows) sb.append(r[0]).append("%  ").append(r[1]).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "(per-thread CPU time failed: " + t + ")\n";
        }
    }

    static synchronized String sample(long millis, int perSecond) throws InterruptedException {
        Map<Long, long[]> cpuStart = cpuSnapshot();
        long interval = 1000L / Math.max(1, Math.min(50, perSecond));
        Map<String, Integer> running = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        Map<String, Map<String, Integer>> stacks = new HashMap<>();
        int rounds = 0;
        float fpsSum = 0, fpsLow = Float.MAX_VALUE;
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (t == Thread.currentThread()) continue;
                String name = t.getName() + " #" + t.getId();
                seen.merge(name, 1, Integer::sum);
                StackTraceElement[] st = e.getValue();
                if (t.getState() != Thread.State.RUNNABLE || st.length == 0 || idle(st[0])) continue;
                running.merge(name, 1, Integer::sum);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(DEPTH, st.length); i++) {
                    sb.append("      ").append(st[i].getClassName()).append('.').append(st[i].getMethodName());
                    if (st[i].getLineNumber() > 0) sb.append(':').append(st[i].getLineNumber());
                    sb.append('\n');
                }
                stacks.computeIfAbsent(name, k -> new HashMap<>()).merge(sb.toString(), 1, Integer::sum);
            }
            float fps = zombie.GameWindow.averageFPS;
            fpsSum += fps;
            fpsLow = Math.min(fpsLow, fps);
            rounds++;
            Thread.sleep(interval);
        }
        List<Map.Entry<String, Integer>> threads = new ArrayList<>(running.entrySet());
        threads.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder out = new StringBuilder();
        out.append(rounds).append(" samples over ").append(millis / 1000).append(" s\n");
        out.append(String.format(java.util.Locale.ROOT, "game fps %.0f average, %.0f lowest\n\n", fpsSum / Math.max(1, rounds), fpsLow));
        out.append(cpuTimes(cpuStart, millis)).append('\n');
        out.append("native thread ids: ").append(TIDS).append("\n\n");
        for (Map.Entry<String, Integer> th : threads) {
            int pct = Math.round(100f * th.getValue() / Math.max(1, rounds));
            if (pct < 3) continue;
            out.append(th.getKey()).append("  busy ").append(pct).append("%\n");
            List<Map.Entry<String, Integer>> top = new ArrayList<>(stacks.get(th.getKey()).entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            for (int i = 0; i < Math.min(6, top.size()); i++) {
                out.append("   ").append(Math.round(100f * top.get(i).getValue() / th.getValue())).append("% of it:\n");
                out.append(top.get(i).getKey());
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Waiting in native code that Java still reports as RUNNABLE (sockets, polls, sleeps). */
    private static boolean idle(StackTraceElement top) {
        String m = top.getClassName() + "." + top.getMethodName();
        return m.contains("socketAccept") || m.contains("accept0") || m.contains("SocketDispatcher.read")
                || m.contains("EPoll.wait") || m.contains("Net.poll") || m.contains("NioSocketImpl.timedRead")
                || m.contains("Unsafe.park") || m.contains("Object.wait") || m.contains("Thread.sleep")
                || m.contains("FileDispatcherImpl.read") || m.contains("readBytes");
    }
}
