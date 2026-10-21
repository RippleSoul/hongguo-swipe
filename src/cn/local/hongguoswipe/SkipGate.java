package cn.local.hongguoswipe;

/** No video timer: the ad badge must remain present before every attempt. */
public final class SkipGate {
    private long seenAt = -1;
    private long absentAt = -1;
    private long lastAttempt = -1;
    private long lastReady = -1;
    private int readyFrames;
    private int attempts;
    private long blockedUntil;

    public void reset() {
        seenAt = absentAt = lastAttempt = lastReady = -1;
        readyFrames = attempts = 0;
        blockedUntil = 0;
    }

    public boolean observe(long now, boolean ad, boolean ready, int countdownSeconds) {
        if (!ad) {
            readyFrames = 0;
            lastReady = -1;
            if (absentAt < 0) absentAt = now;
            if (now - absentAt >= 1800) reset();
            return false;
        }
        absentAt = -1;
        if (seenAt < 0) seenAt = now;
        if (countdownSeconds > 0) {
            blockedUntil = Math.max(blockedUntil, now + Math.min(countdownSeconds, 120) * 1000L + 350);
        }
        if (!ready) {
            readyFrames = 0;
            lastReady = -1;
            return now - seenAt >= 350 && now >= blockedUntil && attempts < 20
                    && (lastAttempt < 0 || now - lastAttempt >= 1600);
        }
        if (lastReady < 0 || now - lastReady > 2200) readyFrames = 0;
        if (lastReady >= 0 && now - lastReady < 300) return false;
        lastReady = now;
        readyFrames++;
        return readyFrames >= 2 && now - seenAt >= 350 && now >= blockedUntil && attempts < 20
                && (lastAttempt < 0 || now - lastAttempt >= 1600);
    }

    public void attempted(long now) {
        lastAttempt = now;
        attempts++;
        readyFrames = 0;
        lastReady = -1;
    }

    public int attempts() { return attempts; }
}
