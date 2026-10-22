package cn.local.hongguoswipe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SwipeService extends AccessibilityService {
    public static final String TARGET = "com.phoenix.read";
    public static final String PREFS = "control";
    public static final String WANTED = "wanted";
    public static volatile SwipeService current;
    private static final String TAG = "HGSwipe";
    private static final Pattern COUNTDOWN = Pattern.compile("(\\d{1,3})\\s*(?:秒|[sS])(?:后)?[^\\n]{0,16}(?:滑|继续|跳过)");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SkipGate gate = new SkipGate();
    private SleepController sleep;
    private boolean running, gestureInFlight, wasTarget, wasAd, awaitingExit;
    private boolean monitoring, tickScheduled, receiverRegistered;
    private long pollCount, foregroundChecks;
    private int epoch, completedGestures;
    private long lastGestureAt;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tickScheduled = false;
            if (!monitoring || !running) return;
            pollCount++;
            try { tick(); }
            catch (RuntimeException failure) { gate.reset(); Log.w(TAG, "POLL_FAILED", failure); }
            if (monitoring && running) scheduleTick(400);
        }
    };
    private final Runnable reconcile = new Runnable() {
        @Override public void run() { reconcileForeground(); }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                stopMonitoring("SCREEN_OFF");
                if (sleep != null) sleep.setForeground(false);
            }
            else queueForegroundCheck();
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        current = this;
        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = null;
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;
        setServiceInfo(info);
        sleep = new SleepController(this, handler);
        sleep.restore();
        running = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(WANTED, false);
        Log.i(TAG, "CONNECTED running=" + running);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, filter);
            receiverRegistered = true;
        }
        queueForegroundCheck();
    }

    public boolean isRunning() { return running; }
    public void setRunning(boolean value) {
        running = value; epoch++; gate.reset(); awaitingExit = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WANTED, value).apply();
        Log.i(TAG, value ? "START" : "PAUSE");
        if (!value) stopMonitoring("PAUSED");
        queueForegroundCheck();
    }

    SleepController sleep() { return sleep; }

    String startSleep(int mode, long duration) {
        String error = sleep.start(mode, duration);
        queueForegroundCheck();
        return error;
    }

    private void queueForegroundCheck() {
        handler.removeCallbacks(reconcile);
        if (running || (sleep != null && sleep.wantsForegroundEvents())) handler.postDelayed(reconcile, 100);
    }

    private void reconcileForeground() {
        if (!running && (sleep == null || !sleep.wantsForegroundEvents())) {
            stopMonitoring("PAUSED"); return;
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager lock = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!power.isInteractive() || lock.isKeyguardLocked()) {
            stopMonitoring("SCREEN_OR_LOCK");
            if (sleep != null) sleep.setForeground(false);
            return;
        }
        foregroundChecks++;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean target = false;
        try { target = root != null && isTargetRoot(root); }
        finally { if (root != null) root.recycle(); }
        if (sleep != null) sleep.setForeground(target);
        if (!target) { stopMonitoring("NOT_FOREGROUND_OR_PIP"); return; }
        if (!running) { stopMonitoring("PAUSED"); return; }
        if (!monitoring) {
            monitoring = true; gate.reset();
            Log.i(TAG, "MONITOR_START polls=" + pollCount);
            scheduleTick(0);
        }
    }

    private void scheduleTick(long delay) {
        if (tickScheduled) return;
        tickScheduled = true;
        handler.postDelayed(ticker, delay);
    }

    private void stopMonitoring(String reason) {
        if (monitoring) { epoch++; Log.i(TAG, "MONITOR_STOP reason=" + reason + " polls=" + pollCount); }
        monitoring = false; tickScheduled = false;
        handler.removeCallbacks(ticker);
        wasTarget = false; wasAd = false; awaitingExit = false; gate.reset();
    }

    private boolean isTargetRoot(AccessibilityNodeInfo root) {
        if (!TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
        AccessibilityWindowInfo window = root.getWindow();
        if (window == null) return false;
        try {
            return window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()
                    && (Build.VERSION.SDK_INT < 26 || !window.isInPictureInPictureMode());
        } finally { window.recycle(); }
    }

    private void tick() {
        if (!running) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager lock = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!power.isInteractive() || lock.isKeyguardLocked()) {
            if (sleep != null) sleep.setForeground(false);
            stopMonitoring("SCREEN_OR_LOCK"); return;
        }
        Snapshot screen = readScreen();
        long now = SystemClock.elapsedRealtime();
        if (!screen.target) {
            if (sleep != null) sleep.setForeground(false);
            if (wasTarget) Log.i(TAG, "LEFT_TARGET");
            stopMonitoring("NOT_FOREGROUND_OR_PIP"); return;
        }
        if (!wasTarget) Log.i(TAG, "TARGET bounds=" + screen.bounds.toShortString());
        wasTarget = true;
        if (screen.ad && !wasAd) Log.i(TAG, "AD_ENTER bounds=" + screen.badge.toShortString());
        if (!screen.ad && wasAd) Log.i(TAG, "AD_ABSENT");
        wasAd = screen.ad;
        if (!screen.ad && awaitingExit) {
            Log.i(TAG, "AD_EXIT_AFTER_GESTURE delayMs=" + (now - lastGestureAt));
            awaitingExit = false;
        }
        boolean shouldSwipe = gate.observe(now, screen.ad, screen.ready, screen.countdown);
        if (screen.ad && shouldSwipe && !gestureInFlight) swipe(screen);
    }

    private void swipe(Snapshot previous) {
        Snapshot screen = readScreen();
        if (!running || !screen.target || !screen.ad || !screen.bounds.equals(previous.bounds)) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager lock = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!power.isInteractive() || lock.isKeyguardLocked()) return;
        int requestEpoch = epoch;
        Rect b = screen.bounds;
        if (b.width() < 150 || b.height() < 150) return;
        float x = b.left + b.width() * .5f;
        Path path = new Path();
        path.moveTo(x, b.top + b.height() * .66f);
        path.lineTo(x, b.top + b.height() * .22f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 420)).build();
        gate.attempted(SystemClock.elapsedRealtime());
        gestureInFlight = true;
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                gestureInFlight = false;
                if (!running || requestEpoch != epoch) return;
                completedGestures++;
                lastGestureAt = SystemClock.elapsedRealtime(); awaitingExit = true;
                Log.i(TAG, "GESTURE_COMPLETED total=" + completedGestures);
            }
            @Override public void onCancelled(GestureDescription description) {
                gestureInFlight = false; Log.i(TAG, "GESTURE_CANCELLED");
            }
        }, handler);
        if (!accepted) gestureInFlight = false;
        Log.i(TAG, "SWIPE_REQUEST accepted=" + accepted + " attempt=" + gate.attempts()
                + " bounds=" + b.toShortString());
    }

    private Snapshot readScreen() {
        Snapshot snapshot = new Snapshot();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return snapshot;
        if (!isTargetRoot(root)) {
            root.recycle(); return snapshot;
        }
        snapshot.target = true;
        root.getBoundsInScreen(snapshot.bounds);
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited++ < 900) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    if (!node.isVisibleToUser()) continue;
                    String text = clean(node.getText()), desc = clean(node.getContentDescription());
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                    Rect screen = snapshot.bounds;
                    boolean small = bounds.width() > 0 && bounds.height() > 0
                            && bounds.width() < screen.width() * .38 && bounds.height() < screen.height() * .16;
                    boolean edge = bounds.centerX() > screen.left + screen.width() * .65
                            || bounds.centerX() < screen.left + screen.width() * .35
                            || bounds.centerY() < screen.top + screen.height() * .25
                            || bounds.centerY() > screen.top + screen.height() * .70;
                    if (small && edge && ("广告".equals(text) || "广告".equals(desc))) {
                        snapshot.ad = true; snapshot.badge.set(bounds);
                    }
                    String label = text + " " + desc;
                    if (label.contains("上滑继续观看") || label.contains("上滑跳过")
                            || label.contains("上滑继续看") || label.contains("滑动跳过广告")) snapshot.ready = true;
                    Matcher countdown = COUNTDOWN.matcher(label);
                    while (countdown.find()) snapshot.countdown = Math.max(snapshot.countdown, Integer.parseInt(countdown.group(1)));
                    for (int i = 0, count = Math.min(node.getChildCount(), 120); i < count; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.addLast(child);
                    }
                } finally { node.recycle(); }
            }
        } finally { while (!queue.isEmpty()) queue.removeFirst().recycle(); }
        return snapshot;
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().replace("\u200b", "").trim();
    }
    private boolean targetIsForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        try { return root != null && isTargetRoot(root); }
        finally { if (root != null) root.recycle(); }
    }

    void checkAutomaticSleep() {
        if (sleep == null) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager lock = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (!power.isInteractive() || lock.isKeyguardLocked() || !targetIsForeground()) {
            sleep.setForeground(false); return;
        }
        sleep.completeIfStillReady();
    }

    void finishForSleep(final int token) {
        setRunning(false);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (!power.isInteractive()) { sleep.finished(token, true); return; }
        if (targetIsForeground()) {
            // A dedicated PAUSE key cannot toggle a paused player back on. Only send it while Hongguo is focused.
            try {
                AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
            } catch (RuntimeException failure) { Log.w("HGSleep", "MEDIA_PAUSE_FAILED", failure); }
            // Leave the player before HOME to avoid triggering its automatic picture-in-picture mode.
            global(GLOBAL_ACTION_BACK);
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!sleep.validCompletion(token)) return;
                if (targetIsForeground()) global(GLOBAL_ACTION_BACK);
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!sleep.validCompletion(token)) return;
                        if (targetIsForeground()) global(GLOBAL_ACTION_HOME);
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (sleep.validCompletion(token)) sleep.finished(token,
                                        Build.VERSION.SDK_INT >= 28 && global(GLOBAL_ACTION_LOCK_SCREEN));
                            }
                        }, 350);
                    }
                }, 400);
            }
        }, 400);
    }

    private boolean global(int action) {
        try {
            boolean accepted = performGlobalAction(action);
            Log.i("HGSleep", "GLOBAL action=" + action + " accepted=" + accepted);
            return accepted;
        } catch (RuntimeException failure) { Log.w("HGSleep", "GLOBAL_FAILED action=" + action, failure); return false; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (sleep != null && TARGET.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())
                && (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)) sleep.userActivity();
        queueForegroundCheck();
    }
    @Override public void onInterrupt() { setRunning(false); if (sleep != null) sleep.cancel(); }
    @Override public void onDestroy() {
        epoch++; running = false; handler.removeCallbacksAndMessages(null);
        if (sleep != null) sleep.destroy();
        monitoring = false; tickScheduled = false;
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false; }
        if (current == this) current = null;
        super.onDestroy();
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("HGSwipe running=" + running + " monitoring=" + monitoring
                + " tickScheduled=" + tickScheduled + " polls=" + pollCount
                + " foregroundChecks=" + foregroundChecks + " gestures=" + completedGestures);
        if (sleep != null) writer.println("HGSleep " + sleep.diagnostics());
    }
    private static final class Snapshot {
        boolean target, ad, ready;
        int countdown;
        final Rect bounds = new Rect(), badge = new Rect();
    }
}
