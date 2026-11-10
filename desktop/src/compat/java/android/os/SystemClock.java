package android.os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Android's SystemClock for desktop builds. elapsedRealtime is time since boot including time spent
 * suspended, which Linux reports in /proc/uptime. Where that can't be read, the JVM's monotonic clock
 * is used instead, from the start: mixing the two would make the clock jump. Either way it never goes
 * backwards.
 */
public final class SystemClock {

    private static final Path UPTIME = Path.of("/proc/uptime");
    private static final long ORIGIN_NANOS = System.nanoTime();
    private static final boolean USE_UPTIME = readUptimeMillis() >= 0;
    private static long lastReturned = 0L;

    private SystemClock() {
    }

    private static long readUptimeMillis() {
        try {
            String text = Files.readString(UPTIME, StandardCharsets.US_ASCII).trim();
            int space = text.indexOf(' ');
            String seconds = space > 0 ? text.substring(0, space) : text;
            return (long) (Double.parseDouble(seconds) * 1000.0);
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    public static synchronized long elapsedRealtime() {
        long now = USE_UPTIME ? readUptimeMillis() : (System.nanoTime() - ORIGIN_NANOS) / 1_000_000L;
        if (now < lastReturned) now = lastReturned;
        lastReturned = now;
        return now;
    }

    public static long uptimeMillis() {
        return elapsedRealtime();
    }
}
