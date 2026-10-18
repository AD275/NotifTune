package dev.codex.classicshade;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String CHANNEL = "gesture_test";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = dp(24);
        content.setPadding(side, dp(24), side, dp(40));
        content.setBackgroundColor(Color.rgb(246, 247, 249));
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars());
            v.setPadding(side + bars.left, dp(24) + bars.top,
                    side + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        text(content, "经典通知自由展开", 26, true);
        text(content, "1.0.4 · 澎湃 OS 4 · LSPosed API 102", 13, false);
        text(content, "经典外观，系统原生手势", 20, true);
        text(content, "在 LSPosed 中启用本模块，作用域勾选“系统界面”（com.android.systemui），重启系统界面或手机后生效。系统通知样式保持“经典样式”。", 16, false);
        text(content, "手指落在可展开的通知上后向下拖动，内容会随拖动展开；同一次触摸中往回拖可逐步收回，松手由系统完成收拢或展开。无需停留到长按菜单出现。", 16, false);
        text(content, "复用系统通知视图，分组内容会随拖动逐渐铺开。不添加后台服务、定时器或网络请求，锁屏限制沿用系统设置。", 15, false);
        button(content, "发送测试通知（单条 + 9 条分组）", v -> sendTests());
        button(content, "清除测试通知", v -> {
            getSystemService(NotificationManager.class).cancelAll();
            Toast.makeText(this, "已清除本模块的测试通知", Toast.LENGTH_SHORT).show();
        });
        text(content, "恢复原状：在 LSPosed 禁用本模块后重启系统界面。没有修改系统文件。", 14, false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        if (getIntent().getBooleanExtra("send_tests", false)) sendTests();
    }

    private void sendTests() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "展开手势测试", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String body = "这是用于验证原生展开手势的本地测试通知。\n" +
                "手指向下拖动时，卡片应逐渐变高，显示更多内容。\n" +
                "保持触摸再向上拖动，卡片应逐渐缩回。\n" +
                "标题、图标、时间和玻璃背景继续使用澎湃系统原来的经典排版。\n" +
                "此通知不联网，也不会向任何人发送消息。";
        manager.notify(101, base(open).setContentTitle("单条通知 · 拖动展开测试")
                .setContentText("向下拖动看全文，保持触摸往回拖可收起。")
                .setStyle(new Notification.BigTextStyle().bigText(body)).build());
        for (int i = 0; i < 9; i++) {
            manager.notify(201 + i, base(open).setGroup("classic_shade_demo")
                    .setContentTitle("分组通知 " + (i + 1))
                    .setContentText("测试内容 · 保留经典样式")
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).build());
        }
        manager.notify(200, base(open).setGroup("classic_shade_demo").setGroupSummary(true)
                .setContentTitle("分组通知 · 展开测试")
                .setStyle(new Notification.InboxStyle()
                        .addLine("分组通知 1 · 保留经典样式")
                        .addLine("分组通知 2 · 随手指连续展开")
                        .addLine("分组通知 3 · 使用系统动画")
                        .setSummaryText("9 条本地测试通知"))
                .setContentText("包含九条本地测试通知").build());
        Toast.makeText(this, "已发送本地测试通知，请下拉通知栏测试", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 100 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) sendTests();
    }

    private Notification.Builder base(PendingIntent open) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(open).setOnlyAlertOnce(true).setShowWhen(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
    }

    private void text(LinearLayout parent, String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(30, 34, 44));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(18);
        parent.addView(view, params);
    }

    private void button(LinearLayout parent, String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
