package cn.local.hongguoswipe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

/** The complete app UI is a single toggle button. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button button;
    private Boolean shown;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        int background = Color.rgb(249, 248, 245);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(background);
        button = new Button(this);
        button.setAllCaps(false);
        button.setTextSize(32);
        button.setTypeface(null, Typeface.BOLD);
        button.setElevation(dp(3));
        page.addView(button, new FrameLayout.LayoutParams(dp(250), dp(150), Gravity.CENTER));
        setContentView(page);
        button.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View view) {
            SwipeService service = SwipeService.current;
            if (service != null) {
                service.setRunning(!service.isRunning());
                update();
                return;
            }
            getSharedPreferences(SwipeService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(SwipeService.WANTED, true).apply();
            openPermission();
          }
        });
        update();
    }

    private void openPermission() {
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        details.putExtra("android.intent.extra.COMPONENT_NAME",
                new ComponentName(this, SwipeService.class).flattenToString());
        try { startActivity(details); return; }
        catch (RuntimeException ignored) { /* Some OEMs do not provide a details activity. */ }
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (RuntimeException unavailable) {
            Toast.makeText(this, "请到系统设置中打开无障碍服务", Toast.LENGTH_LONG).show();
        }
    }

    private void update() {
        SwipeService service = SwipeService.current;
        boolean enabled = service != null && service.isRunning();
        if (shown != null && shown == enabled) return;
        shown = enabled;
        button.setText(enabled ? "已开启" : "打开");
        button.setContentDescription(enabled ? "已开启，点击关闭" : "打开");
        button.setTextColor(enabled ? Color.rgb(36, 105, 68) : Color.WHITE);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(30));
        shape.setColor(enabled ? Color.rgb(224, 240, 229) : Color.rgb(37, 39, 42));
        button.setBackground(shape);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
