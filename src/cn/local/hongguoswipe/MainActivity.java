package cn.local.hongguoswipe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int BG = 0xfffbf9f6, INK = 0xff242c30, RED = 0xffeb6051;
    private static final int MUTED = 0xff777e80, SOFT = 0xfff1eeea, ROSE = 0xffffe9e3;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root, drawer, panel, content;
    private TextView title, status, durationLabel;
    private PowerButton home;
    private Button timerChoice, autoChoice, start, cancel;
    private Button[] presets;
    private EditText minutes, seconds, autoMinutes;
    private LinearLayout timeRow, autoRow;
    private NavRow homeNav, sleepNav;
    private boolean sleepPage, drawerOpen;
    private int selectedMode = SleepController.TIMER, pendingMode;
    private int minuteValue = 30, secondValue, automaticValue = 15;
    private long pendingDuration, pendingUntil;
    private Boolean shown;
    private SharedPreferences prefs;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("sleep", MODE_PRIVATE);
        selectedMode = prefs.getInt("choice", SleepController.TIMER);
        if (prefs.getInt("mode", 0) != 0) selectedMode = prefs.getInt("mode", SleepController.TIMER);
        minuteValue = prefs.getInt("minutes", 30); secondValue = prefs.getInt("seconds", 0);
        automaticValue = Math.max(15, prefs.getInt("auto_minutes", 15));
        if (saved != null) {
            sleepPage = saved.getBoolean("page"); selectedMode = saved.getInt("choice", selectedMode);
            minuteValue = saved.getInt("minutes", minuteValue); secondValue = saved.getInt("seconds", secondValue);
            automaticValue = Math.max(15, saved.getInt("auto_minutes", automaticValue));
            pendingMode = saved.getInt("pending"); pendingDuration = saved.getLong("pending_duration");
            pendingUntil = saved.getLong("pending_until");
        }
        buildPage();
    }

    private void buildPage() {
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = new FrameLayout(this); root.setBackgroundColor(BG); root.setFocusableInTouchMode(true);
        content = new FrameLayout(this);
        FrameLayout.LayoutParams body = new FrameLayout.LayoutParams(-1, -1); body.topMargin = dp(64);
        root.addView(content, body);
        FrameLayout toolbar = new FrameLayout(this);
        root.addView(toolbar, new FrameLayout.LayoutParams(-1, dp(64)));
        title = text(sleepPage ? "睡眠" : "红果助手", 21, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams heading = new FrameLayout.LayoutParams(-1, -1); heading.leftMargin = heading.rightMargin = dp(72);
        toolbar.addView(title, heading);
        View menu = new LineIcon(0, INK); menu.setContentDescription("打开菜单"); menu.setFocusable(true);
        menu.setBackground(ripple(0, 22));
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        menuParams.leftMargin = dp(12); toolbar.addView(menu, menuParams);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openDrawer(); }
        });
        buildDrawer(); setContentView(root); showContent();
    }

    private void buildDrawer() {
        drawer = new FrameLayout(this); drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this); shade.setBackgroundColor(0x52000000);
        drawer.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        shade.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closeDrawer(); } });
        panel = new FrameLayout(this); panel.setClickable(true);
        GradientDrawable surface = shape(BG, 0);
        surface.setCornerRadii(new float[]{0,0,dp(24),dp(24),dp(24),dp(24),0,0}); panel.setBackground(surface);
        int width = Math.min(dp(280), (int) (getResources().getDisplayMetrics().widthPixels * .82f));
        drawer.addView(panel, new FrameLayout.LayoutParams(width, -1, Gravity.LEFT));
        LinearLayout items = new LinearLayout(this); items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(20), dp(28), dp(20), dp(20)); panel.addView(items, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView fruit = new ImageView(this); fruit.setImageResource(R.drawable.ic_fruit);
        brand.addView(fruit, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = text("红果助手", 23, INK); name.setTypeface(Typeface.create("sans-serif-medium", 0));
        brand.addView(name); items.addView(brand, new LinearLayout.LayoutParams(-1, dp(72)));
        homeNav = new NavRow("首页", 1, false); sleepNav = new NavRow("睡眠", 2, true);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(56)); row.topMargin = dp(24);
        items.addView(homeNav, row);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, dp(56)); second.topMargin = dp(8);
        items.addView(sleepNav, second);
    }

    private void openDrawer() {
        if (drawerOpen) return;
        hideKeyboard(); drawerOpen = true; drawer.setVisibility(View.VISIBLE);
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        panel.setTranslationX(-panel.getLayoutParams().width); panel.animate().translationX(0).setDuration(220).start();
    }
    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        panel.animate().translationX(-panel.getWidth()).setDuration(180).withEndAction(new Runnable() {
            @Override public void run() {
                if (!drawerOpen) drawer.setVisibility(View.GONE);
                content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
        }).start();
    }

    private void showContent() {
        content.removeAllViews(); shown = null; home = null; status = null;
        minutes = null; seconds = null; autoMinutes = null; presets = null;
        title.setText(sleepPage ? "睡眠" : "红果助手");
        homeNav.select(!sleepPage); sleepNav.select(sleepPage);
        if (!sleepPage) {
            home = new PowerButton();
            int diameter = Math.max(dp(144), Math.min(dp(260), Math.min(
                    getResources().getDisplayMetrics().widthPixels - dp(64),
                    getResources().getDisplayMetrics().heightPixels - dp(180))));
            content.addView(home, new FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER));
            home.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    SwipeService service = SwipeService.current;
                    if (service != null) service.setRunning(!service.isRunning());
                    else {
                        getSharedPreferences(SwipeService.PREFS, MODE_PRIVATE).edit().putBoolean(SwipeService.WANTED, true).apply();
                        openPermission();
                    }
                    update();
                }
            });
        } else buildSleepPage();
        root.requestFocus(); update();
    }

    private void buildSleepPage() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL); column.setPadding(dp(24), dp(20), dp(24), dp(28));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        LinearLayout choices = new LinearLayout(this); choices.setPadding(dp(4), dp(4), dp(4), dp(4));
        choices.setBackground(shape(SOFT, 28)); column.addView(choices, new LinearLayout.LayoutParams(-1, dp(54)));
        timerChoice = action("倒计时", 0, INK); autoChoice = action("自动检测", 0, INK);
        choices.addView(timerChoice, new LinearLayout.LayoutParams(0, -1, 1));
        choices.addView(autoChoice, new LinearLayout.LayoutParams(0, -1, 1));
        timerChoice.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectMode(SleepController.TIMER); } });
        autoChoice.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectMode(SleepController.AUTO); } });
        status = text("未开启", 14, MUTED); status.setGravity(Gravity.CENTER); status.setPadding(dp(16), dp(8), dp(16), dp(8));
        status.setBackground(shape(SOFT, 22));
        LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, -2); badge.topMargin = dp(24); badge.bottomMargin = dp(20);
        column.addView(status, badge);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(20), dp(18), dp(20)); card.setGravity(Gravity.CENTER);
        card.setBackground(shape(Color.WHITE, 28)); column.addView(card, new LinearLayout.LayoutParams(-1, -2));
        durationLabel = text("多久后关闭", 14, MUTED); durationLabel.setGravity(Gravity.CENTER); card.addView(durationLabel);
        timeRow = new LinearLayout(this); timeRow.setGravity(Gravity.CENTER);
        LinearLayout minuteColumn = timeColumn("分钟"), secondColumn = timeColumn("秒钟");
        minutes = number(minuteValue, 3, "分钟"); seconds = number(secondValue, 2, "秒钟");
        minuteColumn.addView(minutes, 0, new LinearLayout.LayoutParams(-1, dp(76)));
        secondColumn.addView(seconds, 0, new LinearLayout.LayoutParams(-1, dp(76)));
        timeRow.addView(minuteColumn, new LinearLayout.LayoutParams(0, -2, 1));
        TextView colon = text(":", 40, INK); colon.setPadding(0, 0, 0, dp(24)); timeRow.addView(colon);
        timeRow.addView(secondColumn, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(timeRow, new LinearLayout.LayoutParams(-1, -2));
        autoRow = timeColumn("分钟"); autoMinutes = number(automaticValue, 3, "体动检测分钟");
        autoRow.addView(autoMinutes, 0, new LinearLayout.LayoutParams(dp(160), dp(76)));
        card.addView(autoRow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout quick = new LinearLayout(this); presets = new Button[3];
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(-1, dp(44)); quickParams.topMargin = dp(16);
        column.addView(quick, quickParams);
        for (int i = 0; i < 3; i++) {
            final int index = i; Button chip = action("", SOFT, INK); chip.setTextSize(15); presets[i] = chip;
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1); if (i != 2) cp.rightMargin = dp(8);
            quick.addView(chip, cp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    hideKeyboard(); int value = presetValues()[index];
                    if (selectedMode == SleepController.AUTO) autoMinutes.setText(String.valueOf(value));
                    else { minutes.setText(String.valueOf(value)); seconds.setText("00"); }
                    saveValues(); updatePresets();
                }
            });
        }
        View space = new View(this); column.addView(space, new LinearLayout.LayoutParams(1, dp(28)));
        start = action("开启睡眠", RED, Color.WHITE);
        column.addView(start, new LinearLayout.LayoutParams(-1, dp(56)));
        start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startSleep(); } });
        cancel = action("关闭睡眠", SOFT, MUTED);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, dp(50)); cancelParams.topMargin = dp(12);
        column.addView(cancel, cancelParams);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendingMode = 0; SwipeService service = SwipeService.current;
                if (service != null) service.sleep().cancel(); else prefs.edit().putInt("mode", 0).apply();
                update();
            }
        });
        selectMode(selectedMode);
    }

    private void selectMode(int mode) {
        selectedMode = mode; boolean automatic = mode == SleepController.AUTO;
        style(timerChoice, automatic ? 0 : RED, automatic ? INK : Color.WHITE, 24);
        style(autoChoice, automatic ? RED : 0, automatic ? Color.WHITE : INK, 24);
        timeRow.setVisibility(automatic ? View.GONE : View.VISIBLE); autoRow.setVisibility(automatic ? View.VISIBLE : View.GONE);
        durationLabel.setText(automatic ? "持续少动多久后关闭" : "多久后关闭");
        prefs.edit().putInt("choice", mode).apply(); updatePresets(); update();
    }
    private int[] presetValues() { return new int[]{15,30,60}; }
    private void updatePresets() {
        if (presets == null) return;
        int[] values = presetValues(); int value = selectedMode == SleepController.AUTO ? read(autoMinutes) : read(minutes);
        for (int i = 0; i < 3; i++) if (presets[i] != null) {
            presets[i].setText(values[i] + " 分钟");
            boolean selected = value == values[i] && (selectedMode == SleepController.AUTO || read(seconds) == 0);
            style(presets[i], selected ? ROSE : SOFT, selected ? RED : MUTED, 22);
        }
    }
    private void startSleep() {
        hideKeyboard(); long duration;
        if (selectedMode == SleepController.AUTO) {
            int value = read(autoMinutes);
            if (value < 15 || value > 120) { toast("自动检测请设置 15 到 120 分钟"); return; }
            duration = value * 60000L;
        } else {
            int m = read(minutes), s = read(seconds);
            if (m < 0 || s < 0 || s > 59) { toast("请填写分钟和秒，秒数为 0 到 59"); return; }
            duration = m * 60000L + s * 1000L;
        }
        if (duration < 1000 || duration > SleepController.MAX_DURATION) { toast("请设置 1 秒到 12 小时"); return; }
        saveValues(); SwipeService service = SwipeService.current;
        if (service == null) {
            pendingMode = selectedMode; pendingDuration = duration; pendingUntil = SystemClock.elapsedRealtime() + 300000;
            toast("请开启红果助手无障碍服务，再返回这里"); openPermission(); return;
        }
        String error = service.startSleep(selectedMode, duration); toast(error == null ? "睡眠已开启" : error); update();
    }
    private void saveValues() {
        if (minutes == null) return;
        int m = read(minutes), s = read(seconds), a = read(autoMinutes);
        if (m >= 0 && m <= 720) minuteValue = m;
        if (s >= 0 && s <= 59) secondValue = s;
        if (a >= 15 && a <= 120) automaticValue = a;
        prefs.edit().putInt("minutes", minuteValue).putInt("seconds", secondValue).putInt("auto_minutes", automaticValue).apply();
    }
    private void update() {
        SwipeService service = SwipeService.current;
        if (pendingMode != 0) {
            if (SystemClock.elapsedRealtime() > pendingUntil) pendingMode = 0;
            else if (service != null) {
                int mode = pendingMode; pendingMode = 0;
                String error = service.startSleep(mode, pendingDuration); toast(error == null ? "睡眠已开启" : error);
            }
        }
        if (home != null) {
            boolean enabled = service != null && service.isRunning();
            if (shown == null || shown != enabled) { shown = enabled; home.display(enabled); }
        }
        if (status != null) {
            String label = service == null ? (prefs.getInt("mode", 0) == 0 ? "未开启" : "等待无障碍服务连接") : service.sleep().status();
            if (pendingMode != 0) label = "等待开启无障碍服务";
            if (!label.contentEquals(status.getText())) status.setText(label);
            boolean active = pendingMode != 0 || (service != null ? service.sleep().isActive() : prefs.getInt("mode", 0) != 0);
            cancel.setVisibility(active ? View.VISIBLE : View.GONE); start.setText(active ? "更新设置" : "开启睡眠");
        }
    }
    private void openPermission() {
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        details.putExtra("android.intent.extra.COMPONENT_NAME", new ComponentName(this, SwipeService.class).flattenToString());
        try { startActivity(details); return; } catch (RuntimeException ignored) { }
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (RuntimeException unavailable) { toast("请到系统设置中打开无障碍服务"); }
    }
    private LinearLayout timeColumn(String unit) {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER);
        TextView label = text(unit, 14, MUTED); label.setGravity(Gravity.CENTER); column.addView(label); return column;
    }
    private EditText number(int value, int length, String description) {
        EditText input = new EditText(this); input.setText(value < 10 ? "0" + value : String.valueOf(value));
        input.setTextSize(48); input.setTypeface(Typeface.create("sans-serif-light", 0)); input.setTextColor(INK);
        input.setGravity(Gravity.CENTER); input.setPadding(0, 0, 0, 0); input.setBackgroundColor(Color.TRANSPARENT);
        input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setSingleLine(true); input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)}); input.setContentDescription(description);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updatePresets(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        return input;
    }
    private int read(EditText field) {
        if (field == null) return -1;
        try { return Integer.parseInt(field.getText().toString()); } catch (NumberFormatException invalid) { return -1; }
    }
    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this); view.setText(label); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private Button action(String label, int background, int foreground) {
        Button view = new Button(this); view.setText(label); view.setTextSize(17); view.setAllCaps(false);
        view.setTypeface(Typeface.create("sans-serif-medium", 0)); view.setMinHeight(0); view.setMinimumHeight(0);
        view.setMinWidth(0); view.setMinimumWidth(0); view.setStateListAnimator(null); style(view, background, foreground, 28); return view;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(radius)); shape.setColor(color); return shape;
    }
    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x18000000), shape(color, radius), shape(Color.WHITE, radius));
    }
    private void style(Button button, int background, int foreground, int radius) { button.setBackground(ripple(background, radius)); button.setTextColor(foreground); }
    private void hideKeyboard() { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0); root.clearFocus(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override public void onBackPressed() {
        if (drawerOpen) closeDrawer(); else if (sleepPage) { saveValues(); sleepPage = false; showContent(); } else super.onBackPressed();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); saveValues(); drawerOpen = false; buildPage();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        saveValues(); out.putBoolean("page", sleepPage); out.putInt("choice", selectedMode);
        out.putInt("minutes", minuteValue); out.putInt("seconds", secondValue); out.putInt("auto_minutes", automaticValue);
        out.putInt("pending", pendingMode); out.putLong("pending_duration", pendingDuration); out.putLong("pending_until", pendingUntil);
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); saveValues(); super.onPause(); }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private final class NavRow extends LinearLayout {
        private final LineIcon icon; private final TextView label;
        NavRow(String name, int kind, final boolean isSleep) {
            super(MainActivity.this); setGravity(Gravity.CENTER_VERTICAL); setPadding(dp(16), 0, dp(16), 0); setFocusable(true);
            icon = new LineIcon(kind, MUTED); addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            label = text(name, 18, INK); label.setPadding(dp(18), 0, 0, 0); addView(label);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { saveValues(); sleepPage = isSleep; showContent(); closeDrawer(); }
            });
        }
        void select(boolean selected) {
            setBackground(ripple(selected ? ROSE : 0, 18)); label.setTextColor(selected ? RED : INK);
            icon.color = selected ? RED : MUTED; icon.invalidate(); setSelected(selected);
        }
    }
    private final class LineIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private final int kind; private int color;
        LineIcon(int kind, int color) { super(MainActivity.this); this.kind = kind; this.color = color; }
        @Override protected void onDraw(Canvas c) {
            c.save(); c.translate(getWidth()/2f, getHeight()/2f); c.scale(dp(24)/24f, dp(24)/24f);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            if (kind == 0) for (int i = -1; i <= 1; i++) c.drawLine(-9, i*6, 9, i*6, paint);
            else if (kind == 1) {
                Path p = new Path(); p.moveTo(-10,-1); p.lineTo(0,-10); p.lineTo(10,-1);
                p.moveTo(-7,-3); p.lineTo(-7,9); p.lineTo(7,9); p.lineTo(7,-3); c.drawPath(p, paint);
            } else {
                Path p = new Path(); p.moveTo(1,-10); p.cubicTo(-10,-10,-13,6,-2,10);
                p.cubicTo(5,13,11,7,11,2); p.cubicTo(1,8,-4,-3,1,-10); c.drawPath(p, paint);
            }
            c.restore();
        }
    }
    private final class PowerButton extends Button {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private boolean active;
        PowerButton() { super(MainActivity.this); setStateListAnimator(null); setBackgroundColor(Color.TRANSPARENT); setPadding(0,0,0,0); }
        void display(boolean enabled) {
            active = enabled; setText(enabled ? "已开启" : "打开"); setContentDescription(enabled ? "已开启，点击关闭" : "打开"); invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float cx=getWidth()/2f, cy=getHeight()/2f, radius=Math.min(cx,cy);
            paint.setStyle(Paint.Style.FILL); paint.setColor(active ? 0xffedf4ee : 0xffffeee8); c.drawCircle(cx,cy,radius,paint);
            paint.setColor(active ? 0xff4a8162 : (isPressed() ? 0xffd95546 : RED)); c.drawCircle(cx,cy,radius-dp(12),paint);
            float scale=Math.min(1f,getWidth()/(float)dp(260)); c.save(); c.translate(cx,cy); c.scale(scale,scale);
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3)); paint.setStrokeCap(Paint.Cap.ROUND);
            float r=dp(23), y=-dp(20); c.drawArc(new RectF(-r,y-r,r,y+r),-50,280,false,paint);
            c.drawLine(0,y-dp(28),0,y,paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(getResources().getDisplayMetrics().scaledDensity*27);
            paint.setTypeface(Typeface.create("sans-serif-medium",0)); paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(getText().toString(),0,dp(48),paint); c.restore();
        }
    }
}
