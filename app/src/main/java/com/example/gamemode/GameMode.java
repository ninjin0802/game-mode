package com.example.gamemode;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** ゲームモードの開始・終了。画面からも通知の「終了」ボタンからも呼ばれる */
final class GameMode {
    private static final String MOBILE_ALWAYS_ON = "mobile_data_always_on"; // 開発者向けオプション「モバイルデータを常にON」
    private static final String CHANNEL = "gamemode_v2"; // 重要度を上げたので新しい ID（チャンネルの重要度は後から変えられない）
    private static final int NOTIFY_ID = 1;

    private GameMode() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("saved", Context.MODE_PRIVATE); }

    static boolean isActive(Context c) { return prefs(c).getBoolean("active", false); }

    static boolean hasBasicPermissions(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted()
                && Settings.System.canWrite(c);
    }

    // adb shell pm grant com.example.gamemode android.permission.WRITE_SECURE_SETTINGS で許可する
    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Shizuku を使う追加機能の ON/OFF（既定は ON） */
    static boolean opt(Context c, String key) { return prefs(c).getBoolean("opt_" + key, true); }

    static void setOpt(Context c, String key, boolean on) { prefs(c).edit().putBoolean("opt_" + key, on).apply(); }

    /**
     * 動いているアプリを強制終了する。ゲーム・このアプリ・Shizuku・ホーム・キーボード、
     * それと常駐通知を出しているアプリ（音楽・イヤホン・VPN など）は残す。
     */
    private static void forceStopBackground(Context c, String game) {
        java.util.Set<String> running = new java.util.HashSet<>(shellLines("ps -A -o NAME"));
        for (String p : targets(c, game)) if (running.contains(p)) shell("am force-stop " + p);
    }

    /** ゲーム中に止めてよいアプリ（ユーザーが入れたアプリから、止めると困るものを除いたもの） */
    private static java.util.List<String> targets(Context c, String game) {
        java.util.Set<String> keep = new java.util.HashSet<>(java.util.Arrays.asList(
                game, c.getPackageName(), "moe.shizuku.privileged.api",
                // Cloudflare One（WARP）は止めると通信と adb が切れるので必ず残す
                "com.cloudflare.cloudflareoneagent", "com.cloudflare.onedotonedotonedotone"));
        ResolveInfo home = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (home != null) keep.add(home.activityInfo.packageName);
        String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null) keep.add(ime.split("/")[0]);
        String pkg = null;
        for (String line : shellLines("dumpsys activity services")) {
            line = line.trim();
            if (line.startsWith("* ServiceRecord{")) {
                String[] parts = line.split(" ");
                // 例: "* ServiceRecord{c3c1d13 u0 com.foo/.Svc c:com.foo}" → 4 番目がパッケージ/サービス
                pkg = parts.length > 3 ? parts[3].split("/")[0] : null;
            } else if (pkg != null && (line.startsWith("isForeground=true")
                    // システムが BIND_ 権限でつないでいるサービス（VPN・自動入力・ユーザー補助・通知リスナーなど）は止めない
                    || line.contains("permission=android.permission.BIND_"))) keep.add(pkg);
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String l : shellLines("pm list packages -3")) {
            String p = l.replace("package:", "").trim();
            if (!p.isEmpty() && !keep.contains(p)) out.add(p);
        }
        return out;
    }

    /** 複数のコマンドを 1 回の shell でまとめて実行 */
    private static java.util.List<String> script(java.util.List<String> cmds) {
        // 各行を並列に実行して最後に待つ（アプリが 100 個以上あると順番に実行では 20 秒近くかかる）
        java.util.List<String> par = new java.util.ArrayList<>();
        for (String cmd : cmds) par.add("( " + cmd + " ) &");
        par.add("wait");
        return cmds.isEmpty() ? new java.util.ArrayList<>() : shellLines(String.join("\n", par));
    }

    static boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void start(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        ContentResolver cr = c.getContentResolver();
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("active", false)) {
            // 元の設定を保存（二重に開始しても上書きしない）
            SharedPreferences.Editor e = p.edit()
                    .putBoolean("active", true)
                    .putLong("since", System.currentTimeMillis())
                    .putInt("filter", nm.getCurrentInterruptionFilter())
                    .putInt("brightMode", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1))
                    .putInt("bright", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
                    .putInt("rotate", Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1))
                    .putInt("mobileOn", Settings.Global.getInt(cr, MOBILE_ALWAYS_ON, 1))
                    .putBoolean("hzChanged", false)
                    .putBoolean("saverChanged", false);
            if (shizukuReady() && opt(c, "noSaver")) {
                e.putString("saver", shell("settings get global low_power")).putBoolean("saverChanged", true);
            }
            if (shizukuReady() && opt(c, "hz120")) {
                e.putString("peakHz", shell("settings get system peak_refresh_rate"))
                 .putString("minHz", shell("settings get system min_refresh_rate"))
                 .putBoolean("hzChanged", true);
            }
            e.apply();
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        // 明るさは今の値で固定。最大にすると発熱で性能が落ちる（サーマルスロットリング）
        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
        // Wi-Fi 接続中だけ、モバイル通信も待機させておき Wi-Fi が不安定なとき素早く切り替える
        if (onWifi(c) && canWriteSecure(c)) Settings.Global.putInt(cr, MOBILE_ALWAYS_ON, 1);
        // スムーズディスプレイ（120Hz）は隠し設定なので、shell 権限を持つ Shizuku 経由でしか変えられない
        if (p.getBoolean("hzChanged", false) && shizukuReady()) {
            shell("settings put system peak_refresh_rate 120.0");
            shell("settings put system min_refresh_rate 120.0");
        }
        if (p.getBoolean("saverChanged", false) && shizukuReady()) shell("cmd power set-mode 0");
        if (shizukuReady() && !p.contains("boostSaved")) applyBoosts(c, p);
        if (opt(c, "forceKill") && shizukuReady()) forceStopBackground(c, p.getString("selected", ""));
        else killBackground(c);
        showNotification(c);
    }

    /**
     * ① Wi-Fi/Bluetooth の常時スキャンを止める ② ほかのアプリの通信を止める ③ ほかのアプリの裏での動作を禁止。
     * 元に戻すための情報は prefs に残し、終了時（Shizuku がつながったら）に restorePending で戻す。
     */
    private static void applyBoosts(Context c, SharedPreferences p) {
        SharedPreferences.Editor e = p.edit().putBoolean("boostSaved", true);
        if (opt(c, "noScan")) {
            e.putString("wifiScan", shell("settings get global wifi_scan_always_enabled"))
             .putString("bleScan", shell("settings get global ble_scan_always_enabled"));
            shell("settings put global wifi_scan_always_enabled 0; settings put global ble_scan_always_enabled 0");
        }
        java.util.List<String> t = (opt(c, "blockNet") || opt(c, "noBg")) ? targets(c, p.getString("selected", "")) : java.util.Collections.emptyList();
        if (opt(c, "blockNet") && !t.isEmpty()) {
            e.putString("chain3", shell("cmd connectivity get-chain3-enabled")).putString("netBlocked", String.join(",", t));
            java.util.List<String> cmds = new java.util.ArrayList<>();
            for (String pkg : t) cmds.add("cmd connectivity set-package-networking-enabled false " + pkg);
            cmds.add("cmd connectivity set-chain3-enabled true");
            script(cmds);
        }
        if (opt(c, "noBg") && !t.isEmpty()) {
            // すでに禁止されているアプリは触らない。変えたアプリだけ echo して記録する
            java.util.List<String> cmds = new java.util.ArrayList<>();
            for (String pkg : t) cmds.add("case \"$(cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND)\" in *ignore*) ;; *) cmd appops set "
                    + pkg + " RUN_ANY_IN_BACKGROUND ignore && echo " + pkg + ";; esac");
            e.putString("bgBlocked", String.join(",", script(cmds)));
        }
        e.apply();
    }

    private static void undoBoosts(SharedPreferences p) {
        if (!p.contains("boostSaved")) return;
        java.util.List<String> cmds = new java.util.ArrayList<>();
        if (p.contains("wifiScan")) {
            cmds.add(putOrDelete("wifi_scan_always_enabled", p.getString("wifiScan", "")));
            cmds.add(putOrDelete("ble_scan_always_enabled", p.getString("bleScan", "")));
        }
        for (String pkg : p.getString("netBlocked", "").split(","))
            if (!pkg.isEmpty()) cmds.add("cmd connectivity set-package-networking-enabled true " + pkg);
        if (p.contains("netBlocked") && !p.getString("chain3", "").equals("chain:enabled") /* 出力例: chain:disabled */) cmds.add("cmd connectivity set-chain3-enabled false");
        for (String pkg : p.getString("bgBlocked", "").split(","))
            if (!pkg.isEmpty()) cmds.add("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");
        script(cmds);
        p.edit().remove("boostSaved").remove("wifiScan").remove("bleScan").remove("netBlocked").remove("chain3").remove("bgBlocked").apply();
    }

    private static String putOrDelete(String key, String old) {
        return old.matches("[0-9]+") ? "settings put global " + key + " " + old : "settings delete global " + key;
    }

    static void stop(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("active", false)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        ContentResolver cr = c.getContentResolver();
        if (nm.isNotificationPolicyAccessGranted())
            nm.setInterruptionFilter(p.getInt("filter", NotificationManager.INTERRUPTION_FILTER_ALL));
        if (Settings.System.canWrite(c)) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, p.getInt("brightMode", 1));
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, p.getInt("bright", 128));
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, p.getInt("rotate", 1));
        }
        if (canWriteSecure(c)) Settings.Global.putInt(cr, MOBILE_ALWAYS_ON, p.getInt("mobileOn", 1));
        if (p.getBoolean("saverChanged", false) && shizukuReady() && "1".equals(p.getString("saver", "")))
            shell("cmd power set-mode 1"); // 元々バッテリーセーバーが ON だったときだけ戻す
        SharedPreferences.Editor e = p.edit().putBoolean("active", false).putBoolean("hzPending", p.getBoolean("hzChanged", false));
        if (opt(c, "killGame")) e.putString("killPending", p.getString("selected", null)); // 終了時にゲームも閉じる
        e.apply();
        nm.cancel(NOTIFY_ID);
        restorePendingHz(c);
    }

    /**
     * 120Hz を元に戻す。アプリが起動直後だと Shizuku の接続が届いていないので、届いてから戻す。
     * 戻せなかったら hzPending が残り、次にアプリを開いたときに再挑戦する。
     */
    static void restorePendingHz(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("hzPending", false) && p.getString("killPending", null) == null
                && !(p.contains("boostSaved") && !isActive(c))) return;
        Shizuku.OnBinderReceivedListener[] l = new Shizuku.OnBinderReceivedListener[1];
        l[0] = () -> {
            Shizuku.removeBinderReceivedListener(l[0]);
            if (!shizukuReady()) return;
            String kill = p.getString("killPending", null);
            if (kill != null && !GameMode.isActive(c)) {
                // 履歴（最近使ったアプリ）からも消す。例: "Recent #2: Task{83a812d #31419 type=standard A=10336:com.foo}"
                java.util.regex.Pattern t = java.util.regex.Pattern.compile("Task\\{\\S+ #(\\d+) .*A=\\d+:" + java.util.regex.Pattern.quote(kill) + "\\}");
                for (String line : shellLines("dumpsys activity recents")) {
                    java.util.regex.Matcher m = t.matcher(line);
                    if (m.find()) shell("am stack remove " + m.group(1));
                }
                shell("am force-stop " + kill);
            }
            if (p.getBoolean("hzPending", false)) {
                restoreHz("peak_refresh_rate", p.getString("peakHz", ""));
                restoreHz("min_refresh_rate", p.getString("minHz", ""));
            }
            if (!isActive(c)) undoBoosts(p);
            p.edit().putBoolean("hzPending", false).remove("killPending").apply();
        };
        try {
            Shizuku.addBinderReceivedListenerSticky(l[0]); // 接続済みならすぐ呼ばれる
        } catch (Throwable t) {
            // Shizuku が入っていない端末。次回に再挑戦
        }
    }

    private static void restoreHz(String key, String old) {
        // 元が未設定（null）なら削除して端末の既定に戻す
        if (old == null || old.isEmpty() || old.equals("null")) shell("settings delete system " + key);
        else if (old.matches("[0-9.]+")) shell("settings put system " + key + " " + old);
    }

    private static void showNotification(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "ゲームモード中", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null); // 「サイレント」欄に埋もれないよう DEFAULT にしつつ、音は鳴らさない
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
        nm.deleteNotificationChannel("gamemode");
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getBroadcast(c, 1, new Intent(c, StopReceiver.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ゲームモード中")
                .setContentText("遊び終わったら「終了」で設定を元に戻します")
                .setColor(0xFFFF6A2B)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setUsesChronometer(true) // 何分ゲームモードにしているか表示
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "終了して元に戻す", stop).build())
                .build();
        nm.notify(NOTIFY_ID, n);
    }

    private static boolean onWifi(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    /** バックグラウンドのアプリを終了してメモリを空ける（効果は控えめ） */
    private static void killBackground(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(c.getPackageName())) am.killBackgroundProcesses(pkg);
        }
    }

    /** 選択中のゲームを起動。未選択・アンインストール済みなら false */
    static boolean launchGame(Context c) {
        String pkg = prefs(c).getString("selected", null);
        Intent i = pkg == null ? null : c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    /** Shizuku 経由で shell 権限のコマンドを実行し、1行目を返す */
    private static String shell(String cmd) {
        java.util.List<String> l = shellLines(cmd);
        return l.isEmpty() ? "" : l.get(0).trim();
    }

    private static java.util.List<String> shellLines(String cmd) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            // shortcut: Shizuku.newProcess は非公開 API なのでリフレクションで呼ぶ。将来消えたら UserService 方式に移行
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process pr = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            BufferedReader br = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            for (String line; (line = br.readLine()) != null; ) out.add(line);
            pr.waitFor();
        } catch (Exception e) {
            // Shizuku が止まっている等。空を返す
        }
        return out;
    }
}
