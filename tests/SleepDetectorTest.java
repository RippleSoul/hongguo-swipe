import cn.local.hongguoswipe.SleepDetector;

public final class SleepDetectorTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void still(SleepDetector detector, long from, long through, float x) {
        for (long t = from; t <= through; t += 200) detector.acceleration(t, x, 0, 9.81f);
    }
    public static void main(String[] args) {
        long duration = 300000;
        SleepDetector detector = new SleepDetector();
        check(detector.remaining(0, duration) == duration, "No samples must not imply stillness");
        still(detector, 0, duration - 200, 0);
        check(detector.remaining(duration - 200, duration) > 0, "Must wait the whole interval");
        detector.acceleration(duration, 0, 0, 9.81f);
        check(detector.remaining(duration, duration) == 0, "Stillness can trigger after the interval without any light reading");
        detector.reset(); still(detector, 0, duration - 200, 0);
        detector.acceleration(duration, 1, 0, 9.81f);
        check(detector.remaining(duration, duration) == duration, "Movement at deadline postpones sleep");
        still(detector, duration + 200, 2 * duration, 1);
        check(detector.remaining(2 * duration, duration) == 0, "Can become still after moving");
        detector.activity(); detector.acceleration(2 * duration + 200, 1, 0, 9.81f);
        check(detector.remaining(2 * duration + 200, duration) == duration, "A user click resets evidence");
        check(detector.remaining(2 * duration + 6000, duration) == duration, "Missing motion samples cannot trigger");
        detector.acceleration(2 * duration + 6200, 1, 0, 9.81f);
        check(detector.remaining(2 * duration + 6200, duration) == duration, "A sensor gap requires a new interval");
        detector.reset();
        for (int i = 0; i <= 10; i++) detector.acceleration(i * 200, i * .1f, 0, 9.81f);
        check(detector.remaining(2000, duration) == duration, "Slow cumulative movement cannot evade detection");
        detector.acceleration(2200, Float.NaN, 0, 9.81f);
        check(detector.remaining(2200, duration) == duration, "Invalid motion must not trigger");
        detector.acceleration(2400, 1, 0, 9.81f);
        detector.acceleration(2600, 1, Float.POSITIVE_INFINITY, 9.81f);
        check(detector.remaining(2600, duration) == duration, "Infinite motion must not trigger");
        detector.reset();
        check(detector.remaining(duration * 4, duration) == duration, "Pause/foreground changes discard evidence");
        System.out.println("PASS: full interval, no light requirement, movement/click reset, slow rotation, missing/invalid samples, pause");
    }
}
