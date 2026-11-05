package com.example.gamemode;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int BG = 0xFF07080B, CARD = 0xFF111318, CARD2 = 0xFF181B22, LINE = 0x14FFFFFF;
    private static final int TEXT = 0xFFF5F5F7, SUB = 0xFF8C919E, DIM = 0xFF5A5F6B;
    private static final int ACCENT = 0xFFFF6A2B, ACCENT2 = 0xFFE5254B, OK = 0xFF3DDC97;

    private SharedPreferences prefs;
    private ImageView heroBlur, heroIcon;
    private android.webkit.WebView yt; // Google Play に載っている公式 PV（YouTube 埋め込み）
    private String ytPlaying;
    private boolean ytLoaded; // プレイヤーのページを読み込み済みか
    private View heroView, headView;
    private boolean pvShowing; // PV が見えている（UI をよけている）か
    private final android.os.Handler idle = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable makeRoom = () -> makeRoomForPv(true);
    private TextView heroName, heroStat, pill, powerLabel, powerSub;
    private Chronometer timer;
    private FrameLayout power;
    private View halo1, halo2, spin, coreView;
    private TextView powerIcon, gear;
    private int[] spinColors = {0, 0};
    private android.widget.ProgressBar loading;
    private LinearLayout settingsPage;
    private LinearLayout games, basic, boost;
    private final List<ObjectAnimator> pulses = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = GameMode.prefs(this);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(BG);
        if (!prefs.contains("games")) autoDetectGames();
        GameMode.restorePendingHz(this);
        if (GameMode.shizukuReady()) getSystemService(NotificationManager.class).cancel(BootReceiver.NOTIFY_ID);

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(BG);

        // 選択中ゲームのアイコンをぼかして背景に敷く
        heroBlur = new ImageView(this);
        heroBlur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBlur.setRenderEffect(RenderEffect.createBlurEffect(dp(60), dp(60), Shader.TileMode.CLAMP));
        heroBlur.setAlpha(0.55f);
        // PV がないときも背景がゆっくり動くように
        ObjectAnimator kb = ObjectAnimator.ofPropertyValuesHolder(heroBlur,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f));
        kb.setDuration(9000);
        kb.setRepeatMode(ValueAnimator.REVERSE);
        kb.setRepeatCount(ValueAnimator.INFINITE);
        kb.start();
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        backdrop.setClipToOutline(true); // ぼかしが枠の外ににじまないように切る
        backdrop.addView(heroBlur, new FrameLayout.LayoutParams(-1, -1));
        yt = new android.webkit.WebView(this);
        yt.setBackgroundColor(Color.TRANSPARENT);
        yt.setAlpha(0f);
        yt.getSettings().setJavaScriptEnabled(true);
        yt.getSettings().setMediaPlaybackRequiresUserGesture(false); // 自動再生のため
        // スマホ版の埋め込みは controls=0 でも中央に再生/一時停止ボタンが残るので、PC 版のプレイヤーを使う
        yt.getSettings().setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        yt.setOnTouchListener((v, e) -> true); // 背景なので触れても反応させない
        yt.setVerticalScrollBarEnabled(false);
        yt.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                android.util.Log.d("GameModePV", m.message());
                return true;
            }
        });
        yt.setHorizontalScrollBarEnabled(false);
        backdrop.addView(yt, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                fadeStops()));
        backdrop.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        page.addView(backdrop, new FrameLayout.LayoutParams(-1, dp(860))); // PV はステータスバーの裏からライブラリの下まで、ゆっくり背景に溶かす

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(40));

        // ヘッダー
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("GAME MODE", 12, 0xCCFFFFFF, Typeface.BOLD, 0.3f), new LinearLayout.LayoutParams(0, -2, 1));
        pill = text("", 12, TEXT, Typeface.BOLD, 0);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        head.addView(pill);
        gear = text("⚙", 20, TEXT, Typeface.NORMAL, 0);
        gear.setGravity(Gravity.CENTER);
        gear.setContentDescription("設定");
        gear.setOnClickListener(v -> openSettings());
        pressable(gear);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(40), dp(40));
        glp.setMargins(dp(10), 0, 0, 0);
        head.addView(gear, glp);
        root.addView(head);
        headView = head;

        // ヒーロー: 選択中のゲーム
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, dp(28), 0, 0);
        heroIcon = new ImageView(this);
        heroIcon.setClipToOutline(true);
        heroIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroIcon.setBackground(round(CARD2, dp(26), 0));
        heroIcon.setElevation(dp(18));
        hero.addView(heroIcon, new LinearLayout.LayoutParams(dp(96), dp(96)));
        heroName = text("", 26, TEXT, Typeface.BOLD, 0);
        heroName.setGravity(Gravity.CENTER);
        heroName.setPadding(0, dp(16), 0, dp(6));
        hero.addView(heroName);
        heroStat = text("", 13, 0xCCFFFFFF, Typeface.NORMAL, 0);
        heroStat.setPadding(dp(12), dp(5), dp(12), dp(5));
        heroStat.setBackground(round(0x26FFFFFF, dp(14), 0));
        hero.addView(heroStat, new LinearLayout.LayoutParams(-2, -2));
        root.addView(hero);
        heroView = hero;

        // 開始ボタン: 脈打つ光の輪 + グラデーションの円
        power = new FrameLayout(this);
        halo1 = halo();
        halo2 = halo();
        power.addView(halo1, new FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER));
        power.addView(halo2, new FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER));
        spin = new View(this) { // ボタンの縁をぐるぐる回る光（細い輪だけ描く。中は透明）
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                float w = dp(2), cx = getWidth() / 2f, cy = getHeight() / 2f;
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(w);
                p.setShader(new android.graphics.SweepGradient(cx, cy, spinColors, null));
                c.drawOval(w, w, getWidth() - w, getHeight() - w, p);
            }
        };
        power.addView(spin, new FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER));
        LinearLayout core = new LinearLayout(this);
        coreView = core;
        core.setOrientation(LinearLayout.VERTICAL);
        core.setGravity(Gravity.CENTER);
        core.setElevation(dp(24));
        powerIcon = text("", 20, Color.WHITE, Typeface.BOLD, 0);
        powerIcon.setGravity(Gravity.CENTER);
        core.addView(powerIcon, new LinearLayout.LayoutParams(-1, -2));
        loading = new android.widget.ProgressBar(this); // 開始・終了の処理中だけ出すくるくる
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        loading.setVisibility(View.GONE);
        LinearLayout.LayoutParams llp2 = new LinearLayout.LayoutParams(dp(28), dp(28));
        llp2.gravity = Gravity.CENTER_HORIZONTAL;
        llp2.setMargins(0, 0, 0, dp(6));
        core.addView(loading, llp2);
        powerLabel = text("", 28, Color.WHITE, Typeface.BOLD, 0.2f);
        powerLabel.setGravity(Gravity.CENTER);
        core.addView(powerLabel, new LinearLayout.LayoutParams(-1, -2));
        timer = new Chronometer(this);
        timer.setTextColor(0xE6FFFFFF);
        timer.setTextSize(14);
        timer.setTypeface(Typeface.MONOSPACE);
        timer.setGravity(Gravity.CENTER);
        core.addView(timer, new LinearLayout.LayoutParams(-1, -2));
        powerSub = text("", 12, 0xCCFFFFFF, Typeface.NORMAL, 0);
        powerSub.setGravity(Gravity.CENTER);
        core.addView(powerSub, new LinearLayout.LayoutParams(-1, -2));
        power.addView(core, new FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER));
        core.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            toggle();
        });
        pressable(core);
        core.setContentDescription("ゲームモードの開始・終了");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(260), dp(260));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.setMargins(0, dp(12), 0, 0);
        root.addView(power, plp);

        // ライブラリ
        LinearLayout libHead = new LinearLayout(this);
        libHead.setGravity(Gravity.CENTER_VERTICAL);
        libHead.setPadding(dp(4), dp(8), 0, dp(12));
        libHead.addView(text("ライブラリ", 18, TEXT, Typeface.BOLD, 0), new LinearLayout.LayoutParams(0, -2, 1));
        libHead.addView(text("よく遊ぶ順", 12, DIM, Typeface.NORMAL, 0));
        root.addView(libHead);
        games = new LinearLayout(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.addView(games);
        root.addView(hs);

        // 設定カード
        // 設定（ベーシック・ブースト）は右上の歯車から開く別ページに置く
        settingsPage = new LinearLayout(this);
        settingsPage.setOrientation(LinearLayout.VERTICAL);
        settingsPage.setPadding(dp(20), dp(8), dp(20), dp(40));
        settingsPage.addView(sectionTitle("ベーシック", "端末の設定を切り替えます"));
        basic = card();
        settingsPage.addView(basic, new LinearLayout.LayoutParams(-1, -2));
        settingsPage.addView(sectionTitle("ブースト", "しめじ（Shizuku）で使える強めの機能"));
        boost = card();
        settingsPage.addView(boost, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true); // Android 15 以降は全画面描画なのでステータスバー分を空ける
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> moveDisk(power.getTranslationY(), power.getScaleY(), false));
        sv.addView(root);
        page.addView(sv);
        setContentView(page);

        if (!canNotify()) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopYoutube();
        for (ObjectAnimator a : pulses) a.cancel();
        pulses.clear();
    }

    private void refresh() {
        boolean active = GameMode.isActive(this);
        boolean shizuku = GameMode.shizukuReady();
        String sel = prefs.getString("selected", null);
        ApplicationInfo selInfo = sel == null ? null : appInfo(sel);
        String selName = selInfo == null ? null : selInfo.loadLabel(getPackageManager()).toString();
        Map<String, Long> played = playTime();

        // ステータス
        pill.setText(active ? "●  プレイ中" : "●  待機中");
        pill.setTextColor(active ? OK : SUB);
        pill.setBackground(round(active ? 0x263DDC97 : 0x1AFFFFFF, dp(14), 0));

        // ヒーロー
        Drawable icon = squareIcon(selInfo == null ? getDrawable(R.mipmap.ic_launcher) : selInfo.loadIcon(getPackageManager()));
        heroIcon.setImageDrawable(icon);
        heroBlur.setImageDrawable(icon.getConstantState() == null ? icon : icon.getConstantState().newDrawable().mutate());
        heroName.setText(selName == null ? "ゲームを選んでください" : selName);
        long ms = sel == null ? 0 : played.getOrDefault(sel, 0L);
        heroStat.setText(hasUsageAccess() ? "直近30日  " + formatTime(ms) + " プレイ" : "ライブラリから選んでスタート");

        // PV: Google Play に載っている公式 PV を背景で流す
        if (sel != null && !active) playYoutube(sel); else stopYoutube();

        // 開始ボタン
        View core = coreView;
        // すりガラス: 後ろの PV は WebView 側の円形ぼかし（#d）でぼかし、ここでは薄い色と縁だけ乗せる
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x38FFFFFF, 0x14FFFFFF, 0x26000000});
        glass.setShape(GradientDrawable.OVAL);
        glass.setStroke(dp(1), active ? 0x993DDC97 : 0x8CFFFFFF);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x00FFFFFF});
        sheen.setShape(GradientDrawable.OVAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new Drawable[]{glass, sheen});
        ld.setLayerInset(1, dp(18), dp(4), dp(18), dp(96));
        core.setBackground(ld);
        core.setElevation(0); // 半透明なので影は付けない（透けて汚く見える）
        powerIcon.setText(active ? "■" : "▶");
        powerIcon.setTextColor(active ? OK : Color.WHITE);
        spinColors = active ? new int[]{0x003DDC97, 0x003DDC97, 0xE63DDC97, 0x003DDC97}
                : new int[]{0x00FF8A3D, 0x00FF8A3D, 0xE6FF8A3D, 0x00FF8A3D};
        spin.invalidate();
        powerLabel.setText(active ? "STOP" : "START");
        powerLabel.setTextColor(Color.WHITE);
        if (active) {
            timer.setBase(SystemClock.elapsedRealtime() - (System.currentTimeMillis() - prefs.getLong("since", System.currentTimeMillis())));
            timer.start();
            timer.setVisibility(View.VISIBLE);
            powerSub.setText("タップで元に戻す");
        } else {
            timer.stop();
            timer.setVisibility(View.GONE);
            powerSub.setText(selName == null ? "" : "ゲームを起動");
        }
        int haloColor = active ? OK : 0xFFFFFFFF;
        halo1.setBackground(ring(haloColor));
        halo2.setBackground(ring(haloColor));
        startPulse(active);

        // ライブラリ（よく遊ぶ順）
        games.removeAllViews();
        List<String> ordered = savedGames();
        ordered.sort((a, b) -> Long.compare(played.getOrDefault(b, 0L), played.getOrDefault(a, 0L)));
        for (String pkg : ordered) {
            ApplicationInfo ai = appInfo(pkg);
            if (ai != null) games.addView(gameTile(pkg, ai.loadLabel(getPackageManager()).toString(),
                    squareIcon(ai.loadIcon(getPackageManager())), pkg.equals(sel)));
        }
        games.addView(addTile());

        // ベーシック
        NotificationManager nm = getSystemService(NotificationManager.class);
        basic.removeAllViews();
        permRow(basic, R.drawable.ic_dnd, 0xFF7C5CFF, "通知をブロック", "おやすみモード（優先のみ）", nm.isNotificationPolicyAccessGranted(),
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        permRow(basic, R.drawable.ic_sun, 0xFFFFB020, "明るさ固定・回転ロック", "発熱を抑えて性能低下を防ぐ", Settings.System.canWrite(this),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))));
        permRow(basic, R.drawable.ic_wifi, 0xFF2D9CFF, "Wi-Fi + モバイル待機", "Wi-Fi が不安定でもすぐ切り替え", GameMode.canWriteSecure(this),
                () -> toast("PC から adb で許可してください:\npm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"));
        permRow(basic, R.drawable.ic_bell, 0xFFFF5C8A, "ゲーム中の通知", "消し忘れ防止。通知から終了できる", canNotify(),
                () -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0));
        permRow(basic, R.drawable.ic_chart, 0xFF3DDC97, "よく遊ぶ順に並べる", "使用状況へのアクセス", hasUsageAccess(),
                () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        boolean missing = !nm.isNotificationPolicyAccessGranted() || !Settings.System.canWrite(this) || !canNotify();
        gear.setText(missing ? "⚙•" : "⚙");
        gear.setTextColor(missing ? ACCENT : TEXT);

        // ブースト（Shizuku）
        boost.removeAllViews();
        if (!shizuku) {
            permRow(boost, R.drawable.ic_link, 0xFFFF6A2B, "しめじ（Shizuku）を接続", "下の機能を使うのに必要", false, this::askShizuku);
        }
        toggleRow(boost, R.drawable.ic_bolt, 0xFFFFD60A, "スムーズディスプレイ 120Hz",
                "どのゲームでも画面を 120Hz に固定。終了で元に戻す",
                shizuku && !active, GameMode.opt(this, "hz120"), on -> GameMode.setOpt(this, "hz120", on));
        toggleRow(boost, R.drawable.ic_wifi, 0xFF64D2FF, "Wi-Fi・Bluetooth の周辺スキャンを止める",
                "スキャンによる通信の一瞬の遅れを減らす。終了で元に戻す",
                shizuku && !active, GameMode.opt(this, "noScan"), on -> GameMode.setOpt(this, "noScan", on));
        toggleRow(boost, R.drawable.ic_block, 0xFFFF9F0A, "ほかのアプリの通信を止める",
                "ゲームに回線を集中。VPN・常駐中のアプリは除く",
                shizuku && !active, GameMode.opt(this, "blockNet"), on -> GameMode.setOpt(this, "blockNet", on));
        toggleRow(boost, R.drawable.ic_sleep, 0xFFBF5AF2, "ほかのアプリの裏での動作を禁止",
                "勝手に起きて重くなるのを防ぐ。終了で解除",
                shizuku && !active, GameMode.opt(this, "noBg"), on -> GameMode.setOpt(this, "noBg", on));
        toggleRow(boost, R.drawable.ic_chip, 0xFF5AC8FA, "アプリを強制終了してメモリ確保",
                "音楽・イヤホン・VPN など常駐中のアプリは残す",
                shizuku && !active, GameMode.opt(this, "forceKill"), on -> GameMode.setOpt(this, "forceKill", on));
        toggleRow(boost, R.drawable.ic_stop, 0xFFFF453A, "STOP でゲームも終了",
                "ゲームモードを止めたらゲームのタスクも閉じる",
                shizuku && !active, GameMode.opt(this, "killGame"), on -> GameMode.setOpt(this, "killGame", on));
        toggleRow(boost, R.drawable.ic_battery, 0xFF34C759, "バッテリーセーバーを OFF",
                "省電力による性能制限を外す。終了で元に戻す",
                shizuku && !active, GameMode.opt(this, "noSaver"), on -> GameMode.setOpt(this, "noSaver", on));
    }

    /** Google Play のページから公式 PV の YouTube ID を探す（結果はキャッシュ。空文字は「なし」） */
    private void playYoutube(String pkg) {
        String id = prefs.getString("yt_" + pkg, null);
        if (id == null) {
            new Thread(() -> {
                String found = "";
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                            new java.net.URL("https://play.google.com/store/apps/details?id=" + pkg + "&hl=ja").openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(8000);
                    String html = new String(con.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("youtube\\.com/embed/([A-Za-z0-9_-]{11})").matcher(html);
                    if (m.find()) found = m.group(1);
                } catch (Exception e) {
                    return; // 通信できないときは次回また探す
                }
                prefs.edit().putString("yt_" + pkg, found).apply();
                runOnUiThread(() -> { if (pkg.equals(prefs.getString("selected", null))) refresh(); });
            }).start();
            return;
        }
        if (id.isEmpty()) { stopYoutube(); return; }
        if (id.equals(ytPlaying)) return;
        ytPlaying = id;
        yt.animate().alpha(0f).setDuration(200).start();
        heroBlur.animate().alpha(0.55f).setDuration(300).start();
        if (ytLoaded) { // プレイヤーは作り直さず動画だけ差し替える（速い）
            yt.evaluateJavascript("f=0;pl.loadVideoById('" + id + "')", null);
            return;
        }
        ytLoaded = true;
        // 外側の div で 16:9 を縦長の枠いっぱいに広げて中央で切り抜く（YT.Player は内側の div を iframe に置き換える）
        String html = "<html style='height:100%'><body style='margin:0;height:100%;background:#000;overflow:hidden'>"
                // YouTube が動画の中央に出す再生/一時停止アイコンを、START ボタンの裏に隠す。
                // 中心 490dp は、通常時（中心 440・半径 88）と縮んだ時（中心 490・半径 62）のどちらでもボタンに隠れる位置
                + "<div style='position:absolute;top:490px;left:50%;height:calc(2 * max(490px, 100vh - 490px));aspect-ratio:16/9;transform:translate(-50%,-50%) scale(1.4);pointer-events:none'>"
                + "<div id=p></div></div>"
                // START ボタンの後ろだけ円形にぼかす（すりガラス）。YouTube が中央に出すアイコンもこれで見えなくなる
                + "<div id=d style='position:absolute;left:50%;top:440px;width:176px;height:176px;border-radius:50%;"
                + "transform:translate(-50%,-50%);backdrop-filter:blur(26px) brightness(.85);-webkit-backdrop-filter:blur(26px) brightness(.85)'></div>"
                // ステータスバー部分と下の溶ける部分に、すりガラス風のぼかしを重ねる（境目が出ないようマスクで徐々に）
                + "<div style='position:absolute;left:0;right:0;top:0;height:" + (statusBarDp() + 40) + "px;"
                + "backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);"
                + "-webkit-mask-image:linear-gradient(#000 60%,transparent);mask-image:linear-gradient(#000 60%,transparent)'></div>"
                + "<div style='position:absolute;left:0;right:0;bottom:0;height:45%;"
                + "backdrop-filter:blur(22px);-webkit-backdrop-filter:blur(22px);"
                + "-webkit-mask-image:linear-gradient(transparent,#000 70%);mask-image:linear-gradient(transparent,#000 70%)'></div>"
                + "<script src='https://www.youtube.com/iframe_api'></script><script>var pl,t,v,f=0;"
                + "function onYouTubeIframeAPIReady(){pl=new YT.Player('p',{width:'100%',height:'100%',videoId:'" + id + "',"
                + "playerVars:{autoplay:1,controls:0,playsinline:1,rel:0,origin:'https://" + getPackageName() + "',iv_load_policy:3,disablekb:1,fs:0},"
                + "events:{onReady:function(e){e.target.setVolume(0);e.target.playVideo();},"
                // 再生が始まったら少し待ってから見せる（一度見せたら、ループの切れ目などで隠したり UI を戻したりしない）。音はゆっくり大きくする。終わったら頭に戻す
                + "onStateChange:function(e){if(e.data==1){clearTimeout(t);t=setTimeout(function(){Android.show(true);if(!f){f=1;fade();}},1800);}"
                + "if(e.data==0){e.target.seekTo(0);e.target.playVideo();}}}});}"
                + "function fade(){clearInterval(v);var n=0;pl.unMute();pl.setVolume(0);v=setInterval(function(){n+=5;pl.setVolume(n);if(n>=60)clearInterval(v);},100);}"
                + "</script></body></html>";
        yt.removeJavascriptInterface("Android");
        yt.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void show(boolean on) {
                runOnUiThread(() -> {
                    if (ytPlaying == null) return;
                    yt.animate().alpha(on ? 1f : 0f).setDuration(on ? 700 : 200).start();
                    pvShowing = on;
                    moveDisk(power.getTranslationY(), power.getScaleY(), false);
                    idle.removeCallbacks(makeRoom);
                    if (on) idle.postDelayed(makeRoom, 1500); else makeRoomForPv(false);
                    heroBlur.animate().alpha(on ? 0f : 0.55f).setDuration(600).start();
                });
            }
        }, "Android");
        // YouTube は埋め込み元（Referer/origin）がないとエラー 152/153 で再生しないので、https のオリジンを付ける
        yt.loadDataWithBaseURL("https://" + getPackageName(), html, "text/html", "utf-8", null);
    }

    /**
     * PV が流れている間は、ゲームのアイコン・名前を上に逃がして消し、START ボタンを小さくして下に寄せる。
     * 画面に触れたら元に戻し、しばらく触らなければまたよける。
     */
    private void makeRoomForPv(boolean room) {
        boolean go = room && pvShowing;
        android.view.animation.Interpolator ease = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
        for (View v : new View[]{heroView, headView}) {
            v.animate().alpha(go ? 0f : 1f).translationY(go ? -dp(40) : 0).setInterpolator(ease).setDuration(700).start();
        }
        power.animate().scaleX(go ? 0.7f : 1f).scaleY(go ? 0.7f : 1f).translationY(go ? dp(50) : 0)
                .setInterpolator(ease).setDuration(700).start();
        moveDisk(go ? dp(50) : 0, go ? 0.7f : 1f, true);
    }

    /** WebView 内の円形ぼかしを START ボタンの位置・大きさに合わせる */
    private void moveDisk(float targetTy, float targetScale, boolean animate) {
        if (!ytLoaded || power.getHeight() == 0) return;
        int[] pl = new int[2], wl = new int[2];
        power.getLocationInWindow(pl);
        yt.getLocationInWindow(wl);
        float d = getResources().getDisplayMetrics().density;
        float baseCenter = pl[1] + power.getHeight() / 2f * power.getScaleY() - power.getTranslationY() - wl[1];
        float cy = (baseCenter + targetTy) / d;
        float dia = 176 * targetScale;
        yt.evaluateJavascript("var d=document.getElementById('d');if(d){d.style.transition='" + (animate ? "all .7s cubic-bezier(.2,0,0,1)" : "none")
                + "';d.style.top='" + cy + "px';d.style.width=d.style.height='" + dia + "px';}", null);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && pvShowing) {
            idle.removeCallbacks(makeRoom);
            makeRoomForPv(false);
            idle.postDelayed(makeRoom, 4000);
        }
        return super.dispatchTouchEvent(e);
    }

    private void stopYoutube() {
        if (ytPlaying == null) return;
        ytPlaying = null;
        ytLoaded = false;
        pvShowing = false;
        idle.removeCallbacks(makeRoom);
        makeRoomForPv(false);
        yt.loadUrl("about:blank");
        yt.setAlpha(0f);
        heroBlur.animate().alpha(0.55f).setDuration(400).start();
    }

    private void openSettings() {
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(20), 0);
        TextView back = text("←", 22, TEXT, Typeface.NORMAL, 0);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("戻る");
        back.setOnClickListener(v -> d.dismiss());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(text("設定", 22, TEXT, Typeface.BOLD, 0));
        page.addView(bar);
        ScrollView sv = new ScrollView(this);
        if (settingsPage.getParent() != null) ((ViewGroup) settingsPage.getParent()).removeView(settingsPage);
        sv.addView(settingsPage);
        page.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        d.setContentView(page);
        d.getWindow().setWindowAnimations(android.R.style.Animation_Activity);
        d.setOnDismissListener(x -> refresh());
        d.show();
    }

    private boolean busy; // 開始・終了の処理中（二度押し防止）

    private void toggle() {
        if (busy) return;
        boolean stopping = GameMode.isActive(this);
        if (!stopping) {
            if (prefs.getString("selected", null) == null) {
                toast("先にライブラリからゲームを選んでください");
                return;
            }
            if (!GameMode.hasBasicPermissions(this)) {
                toast("設定で「許可」を押してください");
                openSettings();
                return;
            }
        }
        // Shizuku 経由のコマンド（アプリ終了・設定変更）は時間がかかるので、裏で実行して画面は止めない
        busy = true;
        powerIcon.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        powerLabel.setText(stopping ? "終了中" : "準備中");
        powerSub.setText(stopping ? "設定を元に戻しています…" : "ゲームモードに切り替えています…");
        timer.setVisibility(View.GONE);
        ObjectAnimator dots = ObjectAnimator.ofFloat(powerLabel, View.ALPHA, 1f, 0.35f);
        dots.setDuration(500);
        dots.setRepeatMode(ValueAnimator.REVERSE);
        dots.setRepeatCount(ValueAnimator.INFINITE);
        dots.start();
        spin.animate().cancel();
        ObjectAnimator fast = ObjectAnimator.ofFloat(spin, View.ROTATION, spin.getRotation(), spin.getRotation() + 3600f);
        fast.setDuration(6000);
        fast.setInterpolator(new android.view.animation.LinearInterpolator());
        fast.start();
        new Thread(() -> {
            if (stopping) GameMode.stop(this); else GameMode.start(this);
            runOnUiThread(() -> {
                busy = false;
                loading.setVisibility(View.GONE);
                powerIcon.setVisibility(View.VISIBLE);
                dots.cancel();
                fast.cancel();
                powerLabel.setAlpha(1f);
                refresh();
                if (!stopping && !GameMode.launchGame(this)) toast("ゲームを起動できませんでした");
            });
        }).start();
    }

    // ---- ゲームの登録 ----

    private List<String> savedGames() {
        List<String> l = new ArrayList<>();
        for (String p : prefs.getString("games", "").split(",")) if (!p.isEmpty()) l.add(p);
        return l;
    }

    private void saveGames(Set<String> set) { prefs.edit().putString("games", String.join(",", set)).apply(); }

    /** 初回だけ、Android が「ゲーム」と判定しているアプリを自動で登録する */
    private void autoDetectGames() {
        Set<String> set = new LinkedHashSet<>();
        for (ResolveInfo ri : launcherApps()) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            boolean mh = ri.loadLabel(getPackageManager()).toString().contains("Outlanders");
            if (mh) prefs.edit().putString("selected", ai.packageName).apply(); // 作ったきっかけのゲームを最初に選んでおく
            if (mh || isGame(ri)) set.add(ai.packageName);
        }
        saveGames(set);
        if (!set.isEmpty() && !prefs.contains("selected")) prefs.edit().putString("selected", set.iterator().next()).apply();
    }

    /** ゲーム判定のアプリをまとめて一覧に足す。足した数を返す */
    private int autoAddGames() {
        Set<String> set = new LinkedHashSet<>(savedGames());
        int before = set.size();
        for (ResolveInfo ri : launcherApps()) if (isGame(ri)) set.add(ri.activityInfo.packageName);
        saveGames(set);
        if (prefs.getString("selected", null) == null && !set.isEmpty())
            prefs.edit().putString("selected", set.iterator().next()).apply();
        return set.size() - before;
    }

    /** 「＋ 追加」で下から出るシート。つまみ部分を下にスワイプしても閉じる */
    private void showAddDialog() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = launcherApps();
        apps.removeIf(ri -> ri.activityInfo.packageName.equals(getPackageName()) || savedGames().contains(ri.activityInfo.packageName));
        // ゲーム判定のアプリを先頭に、あとは名前順
        Collections.sort(apps, (a, b) -> {
            int ga = isGame(a) ? 0 : 1, gb = isGame(b) ? 0 : 1;
            return ga != gb ? ga - gb : a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
        });

        Dialog sheet = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        float rad = dp(28);
        bg.setCornerRadii(new float[]{rad, rad, rad, rad, 0, 0, 0, 0});
        bg.setStroke(1, LINE);
        box.setBackground(bg);
        box.setPadding(0, 0, 0, dp(16));

        LinearLayout grip = new LinearLayout(this); // ここを掴んで下にスワイプすると閉じる
        grip.setOrientation(LinearLayout.VERTICAL);
        grip.setPadding(0, dp(12), 0, dp(4));
        View handle = new View(this);
        handle.setBackground(round(0xFF3A3E49, dp(3), 0));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        grip.addView(handle, hlp);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(24), dp(18), dp(20), dp(6));
        head.addView(text("ゲームを追加", 22, TEXT, Typeface.BOLD, 0), new LinearLayout.LayoutParams(0, -2, 1));
        TextView auto = text("✨ 自動で追加", 13, Color.WHITE, Typeface.BOLD, 0);
        auto.setPadding(dp(16), dp(9), dp(16), dp(9));
        auto.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2}) {{
            setCornerRadius(dp(18));
        }});
        auto.setOnClickListener(v -> {
            int n = autoAddGames();
            toast(n > 0 ? n + " 個のゲームを追加しました" : "追加できるゲームは見つかりませんでした");
            sheet.dismiss();
            refresh();
        });
        pressable(auto);
        head.addView(auto);
        grip.addView(head);
        TextView hint = text("🎮 は Android がゲームと判定したアプリ", 12, SUB, Typeface.NORMAL, 0);
        hint.setPadding(dp(24), 0, dp(24), dp(10));
        grip.addView(hint);
        box.addView(grip);

        grip.setOnTouchListener(new View.OnTouchListener() {
            float startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        box.setTranslationY(Math.max(0, e.getRawY() - startY));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (box.getTranslationY() > dp(90)) {
                            box.animate().translationY(box.getHeight()).setDuration(180).withEndAction(sheet::dismiss).start();
                        } else {
                            box.animate().translationY(0).setInterpolator(new OvershootInterpolator()).setDuration(250).start();
                        }
                        return true;
                }
                return false;
            }
        });

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0x10FFFFFF));
        list.setAdapter(new ArrayAdapter<ResolveInfo>(this, 0, apps) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                ResolveInfo ri = getItem(pos);
                LinearLayout r = new LinearLayout(getContext());
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(24), dp(10), dp(24), dp(10));
                ImageView iv = new ImageView(getContext());
                iv.setImageDrawable(squareIcon(ri.loadIcon(pm)));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setClipToOutline(true);
                iv.setBackground(round(CARD2, dp(13), 0));
                r.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
                TextView t = text(ri.loadLabel(pm).toString(), 16, TEXT, Typeface.NORMAL, 0);
                t.setPadding(dp(16), 0, 0, 0);
                r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                if (isGame(ri)) {
                    TextView tag = text("🎮 ゲーム", 11, ACCENT, Typeface.BOLD, 0);
                    tag.setPadding(dp(10), dp(4), dp(10), dp(4));
                    tag.setBackground(round(0x1FFF6A2B, dp(10), 0));
                    r.addView(tag);
                }
                return r;
            }
        });
        list.setOnItemClickListener((parent, v, i, id) -> {
            String pkg = apps.get(i).activityInfo.packageName;
            Set<String> set = new LinkedHashSet<>(savedGames());
            set.add(pkg);
            saveGames(set);
            prefs.edit().putString("selected", pkg).apply();
            sheet.dismiss();
            refresh();
        });
        box.addView(list, new LinearLayout.LayoutParams(-1, -1));

        sheet.setContentView(box);
        Window w = sheet.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.72));
        w.setWindowAnimations(android.R.style.Animation_InputMethod); // 下からスライド
        w.setDimAmount(0.6f);
        sheet.show();
    }

    private void confirmRemove(String pkg, String label) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(label + " をライブラリから外しますか？")
                .setPositiveButton("外す", (d, i) -> {
                    Set<String> set = new LinkedHashSet<>(savedGames());
                    set.remove(pkg);
                    saveGames(set);
                    if (pkg.equals(prefs.getString("selected", null)))
                        prefs.edit().putString("selected", set.isEmpty() ? null : set.iterator().next()).apply();
                    refresh();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private boolean isGame(ResolveInfo ri) {
        ApplicationInfo ai = ri.activityInfo.applicationInfo;
        return ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }

    private List<ResolveInfo> launcherApps() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return getPackageManager().queryIntentActivities(main, 0);
    }

    private ApplicationInfo appInfo(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null; // アンインストール済み
        }
    }

    // ---- 部品 ----

    private View gameTile(String pkg, String label, Drawable icon, boolean selected) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        // 選択中: アイコンとすき間をあけた細い輪 + オレンジのにじむ影。未選択は少し小さく暗く
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setClipToOutline(true);
        iv.setBackground(round(CARD2, dp(17), 0));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(iv, new FrameLayout.LayoutParams(dp(60), dp(60)));
        frame.setScaleX(selected ? 1.06f : 0.9f);
        frame.setScaleY(selected ? 1.06f : 0.9f);
        frame.setClipToPadding(false);
        t.addView(frame);
        TextView name = text(label, 12, selected ? TEXT : SUB, selected ? Typeface.BOLD : Typeface.NORMAL, 0);
        name.setSingleLine();
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(2), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(dp(72), -2));
        View dot = new View(this); // 選択中の印
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(selected ? ACCENT : Color.TRANSPARENT);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
        dlp.setMargins(0, dp(6), 0, 0);
        t.addView(dot, dlp);
        if (!selected) t.setAlpha(0.6f);
        t.setOnClickListener(v -> {
            if (GameMode.isActive(this)) { toast("ゲームモード中は切り替えできません"); return; }
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            prefs.edit().putString("selected", pkg).apply();
            refresh();
        });
        t.setOnLongClickListener(v -> { confirmRemove(pkg, label); return true; });
        pressable(t);
        t.setContentDescription(label + (selected ? "（選択中）" : ""));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(14), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private View addTile() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView plus = text("＋", 24, SUB, Typeface.NORMAL, 0);
        plus.setGravity(Gravity.CENTER);
        GradientDrawable g = round(0x0DFFFFFF, dp(20), 0);
        g.setStroke(dp(1), 0x33FFFFFF, dp(5), dp(4));
        plus.setBackground(g);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(68), dp(68));
        t.addView(plus, plp);
        TextView name = text("追加", 12, SUB, Typeface.NORMAL, 0);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(8), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(dp(72), -2));
        t.setOnClickListener(v -> showAddDialog());
        pressable(t);
        t.setContentDescription("ゲームを追加");
        return t;
    }

    private View sectionTitle(String title, String sub) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(4), dp(32), 0, dp(12));
        l.addView(text(title, 18, TEXT, Typeface.BOLD, 0));
        l.addView(text(sub, 12, DIM, Typeface.NORMAL, 0));
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{CARD2, CARD});
        g.setCornerRadius(dp(24));
        g.setStroke(1, LINE);
        c.setBackground(g);
        c.setPadding(dp(16), dp(6), dp(16), dp(6));
        return c;
    }

    /** 共通の行: 色付きアイコン・タイトル・説明。右端は呼び出し側が付ける */
    private LinearLayout baseRow(LinearLayout parent, int icon, int tint, String title, String sub) {
        if (parent.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 1);
            llp.setMargins(dp(54), 0, 0, 0);
            parent.addView(line, llp);
        }
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(14), 0, dp(14));
        ImageView ic = new ImageView(this); // 線の太さがそろった Material アイコンを、項目の色で塗る
        ic.setImageResource(icon);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        ic.setPadding(dp(9), dp(9), dp(9), dp(9));
        ic.setBackground(round((tint & 0x00FFFFFF) | 0x1F000000, dp(12), 0));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
        ilp.setMargins(0, 0, dp(14), 0);
        r.addView(ic, ilp);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, TEXT, Typeface.BOLD, 0));
        TextView s = text(sub, 12, SUB, Typeface.NORMAL, 0);
        s.setPadding(0, dp(2), dp(8), 0);
        labels.addView(s);
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(r);
        return r;
    }

    private void permRow(LinearLayout parent, int icon, int tint, String title, String sub, boolean ok, Runnable fix) {
        LinearLayout r = baseRow(parent, icon, tint, title, sub);
        TextView badge = text(ok ? "✓" : "許可", ok ? 15 : 13, ok ? OK : Color.WHITE, Typeface.BOLD, 0);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(14), dp(7), dp(14), dp(7));
        badge.setBackground(ok ? round(0x1F3DDC97, dp(16), 0)
                : new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2}) {{ setCornerRadius(dp(16)); }});
        r.addView(badge);
        if (!ok) {
            r.setOnClickListener(v -> fix.run());
            pressable(r);
        }
    }

    private interface OnToggle { void set(boolean on); }

    /** 自作のトグル（つまみがスライドする） */
    private void toggleRow(LinearLayout parent, int icon, int tint, String title, String sub,
                           boolean enabled, boolean checked, OnToggle cb) {
        LinearLayout r = baseRow(parent, icon, tint, title, sub);
        FrameLayout track = new FrameLayout(this);
        View thumb = new View(this);
        GradientDrawable th = new GradientDrawable();
        th.setShape(GradientDrawable.OVAL);
        th.setColor(Color.WHITE);
        thumb.setBackground(th);
        thumb.setElevation(dp(2));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL);
        tlp.setMargins(dp(3), 0, 0, 0);
        track.addView(thumb, tlp);
        boolean[] on = {checked};
        Runnable paint = () -> {
            track.setBackground(on[0]
                    ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2}) {{ setCornerRadius(dp(14)); }}
                    : round(0xFF2A2E37, dp(14), 0));
            thumb.animate().translationX(on[0] ? dp(20) : 0).setInterpolator(new DecelerateInterpolator()).setDuration(180).start();
        };
        paint.run();
        r.addView(track, new LinearLayout.LayoutParams(dp(48), dp(28)));
        r.setAlpha(enabled ? 1f : 0.45f);
        r.setContentDescription(title + (on[0] ? " オン" : " オフ"));
        if (enabled) {
            r.setOnClickListener(v -> {
                on[0] = !on[0];
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                cb.set(on[0]);
                paint.run();
                r.setContentDescription(title + (on[0] ? " オン" : " オフ"));
            });
        }
    }

    private View halo() {
        View v = new View(this);
        v.setAlpha(0f);
        return v;
    }

    private GradientDrawable ring(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor((color & 0x00FFFFFF) | 0x22000000);
        g.setStroke(dp(2), (color & 0x00FFFFFF) | 0x88000000);
        return g;
    }

    /** 開始ボタンの周りで輪が広がって消えるアニメーション（2つを半周期ずらす） */
    private void startPulse(boolean active) {
        for (ObjectAnimator a : pulses) a.cancel();
        pulses.clear();
        long dur = active ? 1600 : 2600;
        float to = active ? 1.45f : 1.3f;
        ObjectAnimator rot = ObjectAnimator.ofFloat(spin, View.ROTATION, 0f, 360f);
        rot.setDuration(active ? 1400 : 2800);
        rot.setRepeatCount(ValueAnimator.INFINITE);
        rot.setInterpolator(new android.view.animation.LinearInterpolator());
        rot.start();
        pulses.add(rot);
        View[] hs = {halo1, halo2};
        for (int i = 0; i < 2; i++) {
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(hs[i],
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, to),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, to),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
            a.setDuration(dur);
            a.setStartDelay(i * dur / 2);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new DecelerateInterpolator());
            a.start();
            pulses.add(a);
        }
    }

    /** 押すと少し縮む */
    private void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
                view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start();
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator()).setDuration(220).start();
            return false; // クリック処理はそのまま通す
        });
    }

    /**
     * アイコンを角丸四角にそろえるため、アダプティブアイコンは端末のマスク（丸など）を使わず
     * 背景+前景をそのまま正方形に描く。表示側の角丸クリップで形が統一される。
     */
    private Drawable squareIcon(Drawable d) {
        if (!(d instanceof android.graphics.drawable.AdaptiveIconDrawable)) return d;
        android.graphics.drawable.AdaptiveIconDrawable a = (android.graphics.drawable.AdaptiveIconDrawable) d;
        int size = dp(96);
        int full = Math.round(size * 1.5f); // レイヤーは 108dp、見える範囲は中央の 72dp
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        c.translate(-(full - size) / 2f, -(full - size) / 2f);
        for (Drawable layer : new Drawable[]{a.getBackground(), a.getForeground()}) {
            if (layer == null) continue;
            layer.setBounds(0, 0, full, full);
            layer.draw(c);
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), bm);
    }

    /** PV の上に重ねる暗さ。上（ステータスバー）は薄く、START の下あたりから滑らかに背景色へ（境目が見えないよう S 字カーブ） */
    private int[] fadeStops() {
        int n = 24;
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            float y = i / (float) (n - 1);
            float a;
            if (y < 0.12f) a = 0.35f - y;                       // ステータスバーと見出しは少しだけ暗く
            else if (y < 0.5f) a = 0.23f + (y - 0.12f) * 0.3f;  // PV をしっかり見せる
            else {
                float t = (y - 0.5f) / 0.5f;
                a = 0.34f + 0.66f * (t * t * (3 - 2 * t));      // smoothstep で背景色まで
            }
            c[i] = (Math.round(Math.min(1f, a) * 255) << 24) | (BG & 0x00FFFFFF);
        }
        return c;
    }

    private int statusBarDp() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int px = id > 0 ? getResources().getDimensionPixelSize(id) : dp(40);
        return Math.round(px / getResources().getDisplayMetrics().density);
    }

    private String formatTime(long ms) {
        long min = ms / 60000;
        return min < 60 ? min + "分" : (min / 60) + "時間" + (min % 60 > 0 ? (min % 60) + "分" : "");
    }

    private boolean hasUsageAccess() {
        android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    /** パッケージごとの直近 30 日のプレイ時間（ミリ秒）。権限がなければ空 */
    private Map<String, Long> playTime() {
        Map<String, Long> m = new HashMap<>();
        if (!hasUsageAccess()) return m;
        long now = System.currentTimeMillis();
        android.app.usage.UsageStatsManager um = getSystemService(android.app.usage.UsageStatsManager.class);
        for (android.app.usage.UsageStats u : um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 30L * 86400000L, now))
            m.merge(u.getPackageName(), u.getTotalTimeInForeground(), Long::sum);
        return m;
    }

    private boolean canNotify() {
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void askShizuku() {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(0);
            else toast("Shizuku アプリを開いて起動してください");
        } catch (Throwable t) {
            toast("Shizuku が見つかりません");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refresh();
    }

    private TextView text(String s, int sp, int color, int style, float spacing) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        if (spacing > 0) t.setLetterSpacing(spacing);
        return t;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
