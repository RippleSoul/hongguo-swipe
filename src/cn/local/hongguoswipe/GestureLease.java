package cn.local.hongguoswipe;

/** A missing callback must never block all later gestures; stale callbacks are ignored. */
public final class GestureLease {
    private static final long TIMEOUT = 2500;
    private long token, until;
    private boolean pending;

    public long begin(long now) { token++; pending = true; until = now + TIMEOUT; return token; }
    public boolean expire(long now) {
        if (!pending || now < until) return false;
        clear(); return true;
    }
    public boolean pending() { return pending; }
    public boolean finish(long request) {
        if (!pending || request != token) return false;
        pending = false; return true;
    }
    public void clear() { token++; pending = false; until = 0; }
}
