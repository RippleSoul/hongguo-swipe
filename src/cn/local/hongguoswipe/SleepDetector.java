package cn.local.hongguoswipe;

/** A phone-motion heuristic. It cannot distinguish sleep from quiet wakefulness. */
public final class SleepDetector {
    private static final float MOVEMENT_SQUARED = .45f * .45f;
    private long lastAcceleration = -1, quietSince = -1;
    private float anchorX, anchorY, anchorZ;
    public void reset() { lastAcceleration = -1; quietSince = -1; }

    public void activity() { quietSince = -1; }

    public void acceleration(long now, float x, float y, float z) {
        if (!finite(x) || !finite(y) || !finite(z)) {
            lastAcceleration = -1; quietSince = -1; return;
        }
        float dx = x - anchorX, dy = y - anchorY, dz = z - anchorZ;
        // Compare with a fixed anchor, so slow rotations cannot evade the threshold.
        if (lastAcceleration < 0 || now < lastAcceleration || now - lastAcceleration > 5000
                || dx * dx + dy * dy + dz * dz > MOVEMENT_SQUARED) {
            quietSince = -1; anchorX = x; anchorY = y; anchorZ = z;
        }
        lastAcceleration = now;
        if (quietSince < 0) quietSince = now;
    }

    public long remaining(long now, long duration) {
        if (quietSince < 0 || now < lastAcceleration || now - lastAcceleration > 5000)
            return duration;
        return Math.max(0, duration - (now - quietSince));
    }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
}
