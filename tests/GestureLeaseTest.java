import cn.local.hongguoswipe.GestureLease;

public final class GestureLeaseTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        GestureLease lease = new GestureLease();
        long first = lease.begin(100);
        check(lease.pending(), "An accepted request is pending");
        check(!lease.expire(2599), "Do not expire a valid request early");
        check(lease.expire(2600) && !lease.pending(), "A lost callback must release the block");
        long second = lease.begin(2700);
        check(!lease.finish(first) && lease.pending(), "Late callback cannot release a newer request");
        check(lease.finish(second) && !lease.pending(), "Current callback releases its own request");
        check(!lease.finish(second), "Duplicate callbacks are ignored");
        long third = lease.begin(3000); lease.clear();
        check(!lease.pending() && !lease.finish(third), "Pause or interrupt invalidates in-flight callbacks");
        check(!lease.expire(999999), "No timeout recovery when no request is pending");
        System.out.println("PASS: lost callback recovery, stale and duplicate callback guards, interruption reset");
    }
}
