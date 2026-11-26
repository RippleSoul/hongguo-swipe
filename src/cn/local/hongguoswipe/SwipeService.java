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
import java.util.ArrayList;
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
    private final SkipGate returnGate = new SkipGate();
    private final GestureLease gesture = new GestureLease();
    private final ArrayDeque<String> recentActions = new ArrayDeque<>();
    private SleepController sleep;
    private boolean running, wasTarget, wasAd, wasReturn, awaitingExit;
    private boolean monitoring, tickScheduled, receiverRegistered, reconcileScheduled;
    private long pollCount, foregroundChecks;
    private int epoch, completedGestures, transientRetries, interrupts, gestureTimeouts;
    private String lastStop = "INITIAL", lastGestureKind = "none";
    private long lastGestureAt;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tickScheduled = false;
            if (!monitoring || !running) return;
            pollCount++;
            try { tick(); }
            catch (RuntimeException failure) { gate.reset(); returnGate.reset(); Log.w(TAG, "POLL_FAILED", failure); }
            if (monitoring && running) scheduleTick(400);
        }
    };
    private final Runnable reconcile = new Runnable() {
        @Override public void run() {
            reconcileScheduled = false;
            try { reconcileForeground(); }
            catch (RuntimeException failure) {
                Log.w(TAG, "FOREGROUND_FAILED", failure); retryTransientWindow();
            }
        }
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
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
        handler.removeCallbacksAndMessages(null);
        monitoring = tickScheduled = reconcileScheduled = false;
        wasTarget = wasAd = wasReturn = awaitingExit = false;
        epoch++; gesture.clear(); gate.reset(); returnGate.reset(); transientRetries = 0;
        if (sleep != null) sleep.destroy();
        sleep = new SleepController(this, handler);
        sleep.restore();
        running = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(WANTED, false);
        trace("CONNECTED wanted=" + running);
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
        running = value; epoch++; gesture.clear(); gate.reset(); returnGate.reset(); awaitingExit = false;
        transientRetries = 0;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(WANTED, value).apply();
        Log.i(TAG, value ? "START" : "PAUSE");
        trace(value ? "START" : "PAUSE");
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
        // Keep the earliest pending check: a stream of events must not postpone it forever.
        if (reconcileScheduled || (!running && (sleep == null || !sleep.wantsForegroundEvents()))) return;
        reconcileScheduled = true;
        handler.postDelayed(reconcile, 100);
    }

    private void retryTransientWindow() {
        if (transientRetries >= 5 || reconcileScheduled
                || (!running && (sleep == null || !sleep.wantsForegroundEvents()))) return;
        long delay = Math.min(2000, 200L << transientRetries++);
        reconcileScheduled = true; handler.postDelayed(reconcile, delay);
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
        boolean transientWindow = root == null;
        if (root != null && TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()))
            transientWindow = true;
        try { target = root != null && isTargetRoot(root); }
        finally { if (root != null) root.recycle(); }
        if (sleep != null) sleep.setForeground(target);
        if (!target) {
            stopMonitoring("NOT_FOREGROUND_OR_PIP");
            if (transientWindow) retryTransientWindow();
            else transientRetries = 0;
            return;
        }
        transientRetries = 0;
        if (!running) { stopMonitoring("PAUSED"); return; }
        if (!monitoring) {
            monitoring = true; gate.reset(); returnGate.reset();
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
        if (monitoring || !reason.equals(lastStop)) trace("STOP " + reason);
        lastStop = reason;
        if (monitoring) { epoch++; Log.i(TAG, "MONITOR_STOP reason=" + reason + " polls=" + pollCount); }
        gesture.clear();
        monitoring = false; tickScheduled = false;
        handler.removeCallbacks(ticker);
        wasTarget = false; wasAd = false; wasReturn = false; awaitingExit = false; gate.reset(); returnGate.reset();
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
            stopMonitoring("NOT_FOREGROUND_OR_PIP");
            queueForegroundCheck(); return;
        }
        if (!wasTarget) Log.i(TAG, "TARGET bounds=" + screen.bounds.toShortString());
        wasTarget = true;
        if (screen.ad && !wasAd) Log.i(TAG, "AD_ENTER bounds=" + screen.badge.toShortString());
        if (!screen.ad && wasAd) Log.i(TAG, "AD_ABSENT");
        wasAd = screen.ad;
        if (screen.continuation && !wasReturn) {
            Log.i(TAG, "RETURN_PROMPT source=" + screen.promptSource);
            trace("RETURN_PROMPT " + screen.promptSource);
        }
        wasReturn = screen.continuation;
        if (!screen.ad && !screen.continuation && awaitingExit) {
            Log.i(TAG, "PAGE_EXIT_AFTER_GESTURE kind=" + lastGestureKind + " delayMs=" + (now - lastGestureAt));
            trace("PAGE_EXIT " + lastGestureKind);
            awaitingExit = false;
        }
        if (gesture.expire(now)) { gestureTimeouts++; trace("GESTURE_TIMEOUT_RECOVERED"); Log.w(TAG, "GESTURE_TIMEOUT_RECOVERED"); }
        boolean adSwipe = gate.observe(now, screen.ad, screen.ready, screen.countdown);
        boolean returnSwipe = returnGate.observe(now, !screen.ad && screen.continuation, true, screen.countdown);
        if ((adSwipe || returnSwipe) && !gesture.pending()) swipe(screen);
    }

    private void swipe(Snapshot previous) {
        Snapshot screen = readScreen();
        boolean sameKind = previous.ad ? screen.ad : !screen.ad && screen.continuation;
        if (!running || !screen.target || !sameKind || !screen.bounds.equals(previous.bounds)) return;
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
        GestureDescription request = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 420)).build();
        final SkipGate selectedGate = screen.ad ? gate : returnGate;
        final String kind = screen.ad ? "ad" : screen.promptSource;
        selectedGate.attempted(SystemClock.elapsedRealtime());
        final long token = gesture.begin(SystemClock.elapsedRealtime());
        boolean accepted;
        try { accepted = dispatchGesture(request, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                if (!gesture.finish(token)) return;
                if (!running || requestEpoch != epoch) return;
                completedGestures++;
                trace("COMPLETED " + kind + " token=" + token);
                lastGestureAt = SystemClock.elapsedRealtime(); lastGestureKind = kind; awaitingExit = true;
                Log.i(TAG, "GESTURE_COMPLETED total=" + completedGestures + " kind=" + kind);
            }
            @Override public void onCancelled(GestureDescription description) {
                if (gesture.finish(token)) { trace("CANCELLED " + kind + " token=" + token); Log.i(TAG, "GESTURE_CANCELLED"); }
            }
        }, handler); }
        catch (RuntimeException failure) { gesture.finish(token); trace("REQUEST_FAILED " + kind); Log.w(TAG, "GESTURE_REQUEST_FAILED", failure); return; }
        if (!accepted) gesture.finish(token);
        trace("REQUEST " + kind + " accepted=" + accepted + " token=" + token);
        Log.i(TAG, "SWIPE_REQUEST accepted=" + accepted + " attempt=" + selectedGate.attempts() + " kind=" + kind
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
        ArrayList<Rect> images = new ArrayList<>();
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
                    if ((ReturnPrompt.text(text) || ReturnPrompt.text(desc)) && bounds.height() > 0
                            && bounds.height() < screen.height() * .15
                            && bounds.centerY() > screen.top + screen.height() * .70) {
                        snapshot.continuation = true; snapshot.promptSource = "return_text";
                    }
                    String id = clean(node.getViewIdResourceName());
                    if ((TARGET + ":id/ttlive_player_render_view").equals(id)
                            && "android.view.TextureView".contentEquals(node.getClassName() == null ? "" : node.getClassName()))
                        snapshot.liveVideo.set(bounds);
                    if (id.isEmpty() && text.isEmpty() && desc.isEmpty() && images.size() < 32
                            && "android.widget.ImageView".contentEquals(node.getClassName() == null ? "" : node.getClassName()))
                        images.add(new Rect(bounds));
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
        if (!snapshot.continuation && !snapshot.liveVideo.isEmpty()) {
            Rect s = snapshot.bounds, v = snapshot.liveVideo;
            for (Rect a : images) {
                if (ReturnPrompt.liveBar(s.width(), s.height(), v.left-s.left, v.top-s.top,
                        v.right-s.left, v.bottom-s.top, a.left-s.left, a.top-s.top, a.right-s.left, a.bottom-s.top)) {
                    snapshot.continuation = true; snapshot.promptSource = "live_return_bar"; break;
                }
            }
        }
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
        if (!monitoring && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) transientRetries = 0;
        if (sleep != null && TARGET.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())
                && (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)) sleep.userActivity();
        queueForegroundCheck();
    }
    @Override public void onInterrupt() {
        trace("INTERRUPT wanted=" + running);
        interrupts++; stopMonitoring("INTERRUPTED"); transientRetries = 0;
        // An interrupt asks to stop current feedback. It is not the user's off switch.
        queueForegroundCheck();
    }
    @Override public void onDestroy() {
        epoch++; running = false; gesture.clear(); handler.removeCallbacksAndMessages(null);
        if (sleep != null) sleep.destroy();
        monitoring = false; tickScheduled = false; reconcileScheduled = false;
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false; }
        if (current == this) current = null;
        super.onDestroy();
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("HGSwipe running=" + running + " monitoring=" + monitoring
                + " tickScheduled=" + tickScheduled + " polls=" + pollCount
                + " foregroundChecks=" + foregroundChecks + " gestures=" + completedGestures
                + " interrupts=" + interrupts + " gestureTimeouts=" + gestureTimeouts + " lastStop=" + lastStop
                + " lastGestureKind=" + lastGestureKind);
        try {
            Snapshot s = readScreen();
            writer.println("HGPage target=" + s.target + " ad=" + s.ad + " return=" + s.continuation
                    + " source=" + s.promptSource + " countdown=" + s.countdown + " gesturePending=" + gesture.pending());
        } catch (RuntimeException failure) { writer.println("HGPage temporarily unavailable"); }
        if (sleep != null) writer.println("HGSleep " + sleep.diagnostics());
        for (String action : recentActions) writer.println("HGTrace " + action);
    }
    private void trace(String action) {
        if (recentActions.size() >= 12) recentActions.removeFirst();
        recentActions.addLast(SystemClock.elapsedRealtime() + " " + action);
    }
    private static final class Snapshot {
        boolean target, ad, ready, continuation;
        String promptSource = "none";
        int countdown;
        final Rect bounds = new Rect(), badge = new Rect(), liveVideo = new Rect();
    }
}
