package com.example.gamemode;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ホーム画面の背景。選択中ゲームのアイコンをぼかしたものを敷き、Google Play に載っている公式 PV（YouTube 埋め込み）が
 * 再生できたら、それを PSP のように背景いっぱいに流す。下端は背景色に溶かす。
 */
final class PvBackground extends FrameLayout {
    interface Listener {
        /** PV が見えた（true）／見えなくなった（false） */
        void onPvShown(boolean shown);
    }

    private static final float BLUR_ALPHA = 0.55f;

    private final ImageView blur;
    private final WebView web;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String playing;   // 再生中の YouTube ID
    private String requested; // 再生したいゲーム（ID を探している間に選択が変わったら捨てる）
    private boolean loaded;   // プレイヤーのページを読み込み済みか
    private boolean shown;

    PvBackground(Context c, int bgColor, Listener listener) {
        super(c);
        this.listener = listener;
        this.prefs = GameMode.prefs(c);
        setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        setClipToOutline(true); // ぼかしが枠の外ににじまないように切る

        blur = new ImageView(c);
        blur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blur.setRenderEffect(RenderEffect.createBlurEffect(dp(60), dp(60), Shader.TileMode.CLAMP));
        blur.setAlpha(BLUR_ALPHA);
        // PV がないときも背景がゆっくり動くように
        ObjectAnimator kb = ObjectAnimator.ofPropertyValuesHolder(blur,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f));
        kb.setDuration(9000);
        kb.setRepeatMode(ValueAnimator.REVERSE);
        kb.setRepeatCount(ValueAnimator.INFINITE);
        kb.start();
        addView(blur, new LayoutParams(-1, -1));

        web = new WebView(c);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setAlpha(0f);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false); // 自動再生のため
        web.setOnTouchListener((v, e) -> true); // 背景なので触れても反応させない
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        addView(web, new LayoutParams(-1, -1));

        View fade = new View(c);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fadeStops(bgColor)));
        addView(fade, new LayoutParams(-1, -1));
    }

    boolean isPvShown() { return shown; }

    void setIcon(Drawable icon) {
        blur.setImageDrawable(icon.getConstantState() == null ? icon : icon.getConstantState().newDrawable().mutate());
    }

    /** そのゲームの PV を流す（ID は Google Play のページから探して保存。空文字は「PV なし」） */
    void play(String pkg) {
        requested = pkg;
        String id = prefs.getString("yt_" + pkg, null);
        if (id == null) {
            new Thread(() -> {
                String found = findYoutubeId(pkg);
                if (found == null) return; // 通信できないときは次回また探す
                prefs.edit().putString("yt_" + pkg, found).apply();
                main.post(() -> { if (pkg.equals(requested)) play(pkg); });
            }).start();
            return;
        }
        if (id.isEmpty()) { stop(); return; }
        if (id.equals(playing)) return;
        playing = id;
        web.animate().alpha(0f).setDuration(200).start();
        blur.animate().alpha(BLUR_ALPHA).setDuration(300).start();
        if (loaded) { // プレイヤーは作り直さず動画だけ差し替える（速い）
            web.evaluateJavascript("f=0;pl.loadVideoById('" + id + "')", null);
            return;
        }
        loaded = true;
        // YouTube は埋め込み元（Referer/origin）がないとエラー 152/153 で再生しないので、https のオリジンを付ける
        web.loadDataWithBaseURL("https://" + getContext().getPackageName(), playerHtml(id), "text/html", "utf-8", null);
    }

    void stop() {
        requested = null;
        if (playing == null) return;
        playing = null;
        loaded = false;
        setShown(false);
        web.loadUrl("about:blank");
        web.setAlpha(0f);
        blur.animate().alpha(BLUR_ALPHA).setDuration(400).start();
    }

    /** START ボタンの後ろの円形ぼかしを、ボタンの位置（ウィンドウ座標の中心 Y）と直径に合わせる */
    void moveDisk(float centerYInWindowPx, float diameterDp, boolean animate) {
        if (!loaded) return;
        int[] wl = new int[2];
        web.getLocationInWindow(wl);
        float cy = (centerYInWindowPx - wl[1]) / getResources().getDisplayMetrics().density;
        web.evaluateJavascript("var d=document.getElementById('d');if(d){d.style.transition='"
                + (animate ? "all .7s cubic-bezier(.2,0,0,1)" : "none")
                + "';d.style.top='" + cy + "px';d.style.width=d.style.height='" + diameterDp + "px';}", null);
    }

    private void setShown(boolean on) {
        shown = on;
        listener.onPvShown(on);
    }

    private class Bridge {
        @JavascriptInterface
        public void show(boolean on) {
            main.post(() -> {
                if (playing == null) return;
                web.animate().alpha(on ? 1f : 0f).setDuration(on ? 700 : 200).start();
                blur.animate().alpha(on ? 0f : BLUR_ALPHA).setDuration(600).start();
                setShown(on);
            });
        }
    }

    /** 見つからなければ ""、通信できなければ null */
    private static String findYoutubeId(String pkg) {
        try {
            HttpURLConnection con = (HttpURLConnection)
                    new URL("https://play.google.com/store/apps/details?id=" + pkg + "&hl=ja").openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            String html = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("youtube\\.com/embed/([A-Za-z0-9_-]{11})").matcher(html);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return null;
        }
    }

    private String playerHtml(String id) {
        // 外側の div で 16:9 を縦長の枠いっぱいに広げて中央で切り抜く（YT.Player は内側の div を iframe に置き換える）
        return "<html style='height:100%'><body style='margin:0;height:100%;background:#000;overflow:hidden'>"
                // 動画の中心（YouTube が再生/一時停止アイコンを出す場所）を START ボタンの裏あたりに置く
                + "<div style='position:absolute;top:490px;left:50%;height:calc(2 * max(490px, 100vh - 490px));aspect-ratio:16/9;transform:translate(-50%,-50%) scale(1.4);pointer-events:none'>"
                + "<div id=p></div></div>"
                // START ボタンの後ろだけ円形にぼかす（すりガラス）。YouTube のアイコンもこれで見えなくなる
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
                + "playerVars:{autoplay:1,controls:0,playsinline:1,rel:0,origin:'https://" + getContext().getPackageName() + "',iv_load_policy:3,disablekb:1,fs:0},"
                + "events:{onReady:function(e){e.target.setVolume(0);e.target.playVideo();},"
                // 再生が始まったら少し待ってから見せる（一度見せたら、ループの切れ目などで隠さない）。音はゆっくり大きくする。終わったら頭に戻す
                + "onStateChange:function(e){if(e.data==1){clearTimeout(t);t=setTimeout(function(){Android.show(true);if(!f){f=1;fade();}},1800);}"
                + "if(e.data==0){e.target.seekTo(0);e.target.playVideo();}}}});}"
                + "function fade(){clearInterval(v);var n=0;pl.unMute();pl.setVolume(0);v=setInterval(function(){n+=5;pl.setVolume(n);if(n>=60)clearInterval(v);},100);}"
                + "</script></body></html>";
    }

    /** PV の上に重ねる暗さ。上（ステータスバー）は薄く、START の下あたりから滑らかに背景色へ（境目が見えないよう S 字カーブ） */
    private static int[] fadeStops(int bg) {
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
            c[i] = (Math.round(Math.min(1f, a) * 255) << 24) | (bg & 0x00FFFFFF);
        }
        return c;
    }

    private int statusBarDp() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int px = id > 0 ? getResources().getDimensionPixelSize(id) : dp(40);
        return Math.round(px / getResources().getDisplayMetrics().density);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
