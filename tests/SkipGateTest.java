import cn.local.hongguoswipe.SkipGate;

public final class SkipGateTest {
    private static void check(boolean expected, boolean actual, String message) {
        if (expected != actual) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        SkipGate gate = new SkipGate();
        for (long t = 0; t < 100000; t += 800) check(false, gate.observe(t, false, true, 0), "No ad must never swipe");
        gate.reset();
        check(false, gate.observe(0, true, false, 0), "New ad must wait");
        check(false, gate.observe(200, true, false, 0), "Reject a transient single-frame badge");
        check(true, gate.observe(400, true, false, 0), "Unknown countdown allows a prompt guarded attempt");
        gate.attempted(400);
        check(false, gate.observe(800, true, false, 0), "Retry must not spam");
        for (int i = 1; i < 20; i++) {
            long now = 400 + i * 2000;
            check(true, gate.observe(now, true, false, 0), "Delayed retry");
            gate.attempted(now);
        }
        check(false, gate.observe(90000, true, false, 0), "Unchanged ad attempts are capped");
        check(false, gate.observe(91000, false, false, 0), "No gesture after ad leaves");
        check(false, gate.observe(94000, false, false, 0), "Confirmed ad exit resets episode");
        check(false, gate.observe(95000, true, false, 0), "Next ad has its own wait");
        gate.reset();
        gate.observe(0, true, false, 12);
        check(false, gate.observe(7000, true, true, 5), "Visible countdown overrides readiness");
        check(false, gate.observe(8000, true, true, 4), "Keep waiting for explicit countdown");
        for (long now = 8800; now < 12350; now += 800)
            check(false, gate.observe(now, true, true, 0), "Cannot cut short the observed countdown");
        check(true, gate.observe(12800, true, true, 0), "Proceed after observed countdown");
        gate.reset();
        check(false, gate.observe(0, true, true, 0), "One ready frame is insufficient");
        check(true, gate.observe(800, true, true, 0), "Stable ready prompt allows earlier swipe");
        gate.reset();
        check(false, gate.observe(0, true, true, 0), "Reset loses readiness");
        check(false, gate.observe(100, true, true, 0), "Duplicate frame is insufficient");
        System.out.println("PASS: non-ad protection, waiting, countdown, retry cap, reset, fresh readiness");
    }
}
