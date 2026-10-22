package cn.local.hongguoswipe;

import android.app.AlarmManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.util.Locale;

/** Owned by the accessibility service; no overlay, network or repeating timer poll. */
final class SleepController implements SensorEventListener {
    static final int OFF = 0, TIMER = 1, AUTO = 2;
    static final long MAX_DURATION = 12 * 60 * 60 * 1000L;
    private final SwipeService service;
    private final Handler handler;
    private final SharedPreferences prefs;
    private final SensorManager sensors;
    private final AlarmManager alarms;
    private final SleepDetector detector = new SleepDetector();
    private int mode, generation;
    private long deadline, duration;
    private boolean observing, finishing, targetForeground;
    private long motionSamples;
    private String message = "未开启";
    private AlarmManager.OnAlarmListener alarm;
    private Runnable timer;

    SleepController(SwipeService service, Handler handler) {
        this.service = service; this.handler = handler;
        prefs = service.getSharedPreferences("sleep", Context.MODE_PRIVATE);
        sensors = (SensorManager) service.getSystemService(Context.SENSOR_SERVICE);
        alarms = (AlarmManager) service.getSystemService(Context.ALARM_SERVICE);
    }

    void restore() {
        int savedMode = prefs.getInt("mode", OFF);
        long savedDuration = prefs.getLong("duration", 0);
        long savedDeadline = prefs.getLong("deadline", 0);
        if (savedMode == OFF) return;
        if (prefs.getInt("boot", -1) != boot() || savedDuration <= 0 || savedDuration > MAX_DURATION
                || (savedMode == TIMER && (savedDeadline <= now() || savedDeadline - now() > savedDuration))) {
            cancel(); message = "上次睡眠任务已结束"; return;
        }
        if (savedMode == AUTO && !supportsAuto()) { cancel(); message = "传感器不可用，请使用倒计时"; return; }
        if (Build.VERSION.SDK_INT < 28 || (savedMode != TIMER && savedMode != AUTO)) { cancel(); return; }
        mode = savedMode; duration = savedDuration; deadline = savedDeadline;
        if (mode == TIMER) scheduleTimer();
        Log.i("HGSleep", "RESTORED mode=" + mode);
    }

    String start(int requestedMode, long requestedDuration) {
        if (Build.VERSION.SDK_INT < 28) return "睡眠锁屏需要 Android 9 或以上";
        if (requestedMode != TIMER && requestedMode != AUTO) return "请选择睡眠方式";
        if (requestedDuration < 1000 || requestedDuration > MAX_DURATION) return "请设置 1 秒到 12 小时";
        if (requestedMode == AUTO && requestedDuration < 5 * 60 * 1000L) return "自动检测至少持续 5 分钟";
        if (requestedMode == AUTO && !supportsAuto()) return "手机缺少运动传感器，请使用倒计时";
        cancel(); mode = requestedMode; duration = requestedDuration; deadline = now() + duration;
        message = "";
        prefs.edit().putInt("mode", mode).putLong("duration", duration)
                .putLong("deadline", deadline).putInt("boot", boot()).apply();
        if (mode == TIMER) scheduleTimer();
        else setForeground(targetForeground);
        if (mode == OFF) return message;
        Log.i("HGSleep", "START mode=" + mode + " durationMs=" + duration);
        return null;
    }

    void cancel() {
        generation++; finishing = false; mode = OFF; deadline = 0;
        stopTimer(); stopSensors();
        prefs.edit().putInt("mode", OFF).remove("deadline").remove("duration").remove("boot").apply();
        message = "未开启";
    }

    boolean isActive() { return mode != OFF || finishing; }
    boolean isAutomatic() { return mode == AUTO; }
    boolean wantsForegroundEvents() { return mode == AUTO; }
    boolean validCompletion(int token) { return finishing && generation == token; }

    void finished(int token, boolean locked) {
        if (!validCompletion(token)) return;
        finishing = false;
        message = locked ? "睡眠已结束" : "系统未执行锁屏，请检查无障碍服务";
        Log.i("HGSleep", "FINISHED locked=" + locked);
    }

    String status() {
        if (finishing) return "正在结束播放…";
        if (mode == TIMER) return "倒计时  " + clock(Math.max(0, deadline - now()));
        if (mode == AUTO) {
            if (!observing) return "自动检测已开启，等待红果前台";
            return "体动检测  " + clock(detector.remaining(now(), duration));
        }
        return message;
    }

    String diagnostics() {
        return status() + " sensors=" + observing + " motionSamples=" + motionSamples;
    }

    void setForeground(boolean target) {
        targetForeground = target;
        if (mode != AUTO || !target) { stopSensors(); return; }
        if (observing) return;
        detector.reset();
        boolean motion = false;
        try {
            motion = sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), 200000, handler);
        } catch (RuntimeException failure) { Log.w("HGSleep", "SENSOR_UNAVAILABLE", failure); }
        if (!motion) {
            cancel(); message = "传感器启动失败，请使用倒计时"; return;
        }
        observing = true;
        Log.i("HGSleep", "SENSORS_START");
    }

    void userActivity() { if (observing) detector.activity(); }

    private void scheduleTimer() {
        final int token = generation;
        timer = new Runnable() {
            @Override public void run() {
                if (token != generation || mode != TIMER) return;
                if (now() < deadline) { handler.postDelayed(this, deadline - now()); return; }
                complete();
            }
        };
        handler.postDelayed(timer, Math.max(0, deadline - now()));
        alarm = new AlarmManager.OnAlarmListener() {
            @Override public void onAlarm() { if (token == generation && mode == TIMER) timer.run(); }
        };
        try {
            // The listener overload requires no exact-alarm permission. Bound service owns its lifetime.
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, "Hongguo sleep", alarm, handler);
        } catch (RuntimeException failure) {
            Log.w("HGSleep", "ALARM_UNAVAILABLE; keeping handler deadline", failure);
        }
    }

    private void stopTimer() {
        if (timer != null) handler.removeCallbacks(timer);
        if (alarm != null) {
            try { alarms.cancel(alarm); } catch (RuntimeException ignored) { }
        }
        timer = null; alarm = null;
    }

    private void stopSensors() {
        if (sensors != null) sensors.unregisterListener(this);
        if (observing) Log.i("HGSleep", "SENSORS_STOP");
        observing = false; detector.reset();
    }

    private boolean supportsAuto() {
        return sensors != null && sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!observing || mode != AUTO) return;
        long time = now();
        if (Math.abs(time - event.timestamp / 1000000L) > 5000) { detector.reset(); return; }
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            motionSamples++;
            detector.acceleration(time, event.values[0], event.values[1], event.values[2]);
            if (detector.remaining(time, duration) == 0) service.checkAutomaticSleep();
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    void completeIfStillReady() {
        if (mode == AUTO && observing && detector.remaining(now(), duration) == 0) complete();
    }

    private void complete() {
        cancel(); finishing = true;
        Log.i("HGSleep", "EXPIRED");
        service.finishForSleep(generation);
    }

    void destroy() { generation++; finishing = false; stopTimer(); stopSensors(); }
    private long now() { return SystemClock.elapsedRealtime(); }
    private int boot() { return Settings.Global.getInt(service.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    static String clock(long millis) {
        long total = (millis + 999) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60);
    }
}
