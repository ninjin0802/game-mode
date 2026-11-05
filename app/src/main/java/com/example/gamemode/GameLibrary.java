package com.example.gamemode;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ライブラリ（登録したゲームの一覧と、選択中のゲーム）と、端末のアプリ情報の読み出し */
final class GameLibrary {
    private GameLibrary() {}

    static List<String> saved(Context c) {
        List<String> l = new ArrayList<>();
        for (String p : GameMode.prefs(c).getString("games", "").split(",")) if (!p.isEmpty()) l.add(p);
        return l;
    }

    private static void save(Context c, Set<String> set) {
        GameMode.prefs(c).edit().putString("games", String.join(",", set)).apply();
    }

    static String selected(Context c) { return GameMode.prefs(c).getString("selected", null); }

    static void select(Context c, String pkg) { GameMode.prefs(c).edit().putString("selected", pkg).apply(); }

    /** 追加して選択する */
    static void add(Context c, String pkg) {
        Set<String> set = new LinkedHashSet<>(saved(c));
        set.add(pkg);
        save(c, set);
        select(c, pkg);
    }

    /** 外す。選択中だったら残りの先頭を選ぶ */
    static void remove(Context c, String pkg) {
        Set<String> set = new LinkedHashSet<>(saved(c));
        set.remove(pkg);
        save(c, set);
        if (pkg.equals(selected(c))) select(c, set.isEmpty() ? null : set.iterator().next());
    }

    /** 初回だけ、Android が「ゲーム」と判定しているアプリを自動で登録する */
    static void autoDetectIfFirstRun(Context c) {
        SharedPreferences p = GameMode.prefs(c);
        if (p.contains("games")) return;
        Set<String> set = new LinkedHashSet<>();
        for (ResolveInfo ri : launcherApps(c)) {
            boolean mh = ri.loadLabel(c.getPackageManager()).toString().contains("Outlanders");
            if (mh) select(c, ri.activityInfo.packageName); // 作ったきっかけのゲームを最初に選んでおく
            if (mh || isGame(ri)) set.add(ri.activityInfo.packageName);
        }
        save(c, set);
        if (!set.isEmpty() && !p.contains("selected")) select(c, set.iterator().next());
    }

    /** ゲーム判定のアプリをまとめて一覧に足す。足した数を返す */
    static int autoAdd(Context c) {
        Set<String> set = new LinkedHashSet<>(saved(c));
        int before = set.size();
        for (ResolveInfo ri : launcherApps(c)) if (isGame(ri)) set.add(ri.activityInfo.packageName);
        save(c, set);
        if (selected(c) == null && !set.isEmpty()) select(c, set.iterator().next());
        return set.size() - before;
    }

    static boolean isGame(ResolveInfo ri) {
        ApplicationInfo ai = ri.activityInfo.applicationInfo;
        return ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }

    static List<ResolveInfo> launcherApps(Context c) {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return c.getPackageManager().queryIntentActivities(main, 0);
    }

    /** アンインストール済みなら null */
    static ApplicationInfo appInfo(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** パッケージごとの直近 30 日のプレイ時間（ミリ秒）。権限がなければ空 */
    static Map<String, Long> playTime(Context c) {
        Map<String, Long> m = new HashMap<>();
        if (!hasUsageAccess(c)) return m;
        long now = System.currentTimeMillis();
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        for (UsageStats u : um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 30L * 86400000L, now))
            m.merge(u.getPackageName(), u.getTotalTimeInForeground(), Long::sum);
        return m;
    }
}
