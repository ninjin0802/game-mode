package com.example.gamemode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知の「終了」ボタン */
public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        PendingResult r = goAsync(); // 元に戻す処理は時間がかかるので裏で
        new Thread(() -> {
            GameMode.stop(c);
            r.finish();
        }).start();
    }
}
