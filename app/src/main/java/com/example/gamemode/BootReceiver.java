package com.example.gamemode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import rikka.shizuku.Shizuku;

/**
 * 再起動後の手助け。root なしの Shizuku は再起動で止まり、自動起動もできないので、
 * ワイヤレスデバッグを ON にしておき、「タップで Shizuku を起動」の通知を出す。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "shizuku_boot";
    static final int NOTIFY_ID = 2;

    @Override
    public void onReceive(Context c, Intent i) {
        // 受信口は公開されているので、システムだけが送れる BOOT_COMPLETED 以外は無視する
        // （他のアプリから呼ばれてワイヤレスデバッグを ON にされるのを防ぐ）
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        PendingResult r = goAsync();
        new Thread(() -> {
            try {
                Thread.sleep(15000); // 起動直後は Wi-Fi やほかのアプリが落ち着くまで待つ
                if (shizukuRunning()) {
                    GameMode.restorePending(c); // 戻しきれていない設定があれば戻す
                    return;
                }
                // Shizuku の「ワイヤレスデバッグで開始」がすぐ使えるよう ON にしておく
                if (GameMode.canWriteSecure(c)) Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
                notifyStart(c);
            } catch (InterruptedException ignored) {
            } finally {
                r.finish();
            }
        }).start();
    }

    private static boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void notifyStart(Context c) {
        Intent open = c.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (open == null) return; // Shizuku が入っていない
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Shizuku の起動", NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("しずく（Shizuku）が止まっています")
                .setContentText("タップして「開始」を押すと、ブースト機能が使えるようになります")
                .setColor(0xFFFF6A2B)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .build();
        nm.notify(NOTIFY_ID, n);
    }
}
