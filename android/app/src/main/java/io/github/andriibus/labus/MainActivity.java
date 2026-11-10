package io.github.andriibus.labus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

/** Экран приложения — тот же LA Bus, что на сайте, плюс мост к виджету и уведомлениям. */
public class MainActivity extends Activity {
    static final String TAG = "LABus";
    static final String SITE = "https://andrii-bus.github.io/";
    static final String HOST = "andrii-bus.github.io";
    static final String RELEASES = "https://api.github.com/repos/andrii-bus/andrii-bus.github.io/releases/latest";

    private WebView wv;
    private boolean pendingTest;
    private boolean errorShown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0E0E10);
        getWindow().setNavigationBarColor(0xFF0E0E10);
        Notif.channels(this);

        Intent in = getIntent();
        if (ciMode() && in.getBooleanExtra("preview", false)) { showWidgetPreview(); return; }

        wv = new WebView(this);
        wv.setBackgroundColor(0xFF0E0E10);
        setContentView(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " LABusAndroid/" + BuildConfig.VERSION_NAME);
        s.setCacheMode(online() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        wv.addJavascriptInterface(new Bridge(), "LABusAndroid");
        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !errorShown) {
                    errorShown = true;
                    view.loadDataWithBaseURL(SITE, OFFLINE_HTML, "text/html", "utf-8", null);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // движок страницы упал — пересоздаём экран, а не закрываем приложение
                Log.w(TAG, "renderer gone, crash=" + detail.didCrash());
                if (wv == view) wv = null;
                ((android.view.ViewGroup) view.getParent()).removeView(view);
                view.destroy();
                recreate();
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith(SITE)) errorShown = false;
                String js64 = getIntent().getStringExtra("js64");
                if (ciMode() && js64 != null) {
                    String js = new String(android.util.Base64.decode(js64, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
                    view.evaluateJavascript(js, null);
                }
            }
        });
        wv.loadUrl(SITE);

        if (ciMode() && in.getBooleanExtra("test_notify", false)) BusService.start(this, "test");
        Scheduler.reschedule(this);
        checkUpdate();
    }

    static final String OFFLINE_HTML = "<!DOCTYPE html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>body{margin:0;height:100vh;display:grid;place-items:center;background:#0e0e10;color:#f4f4f6;"
            + "font:18px system-ui,sans-serif;text-align:center}button{margin-top:18px;border:0;border-radius:28px;padding:14px 26px;"
            + "font:600 17px system-ui,sans-serif;background:#b9c7f5;color:#15161c}</style></head><body><div>"
            + "<div style='font-size:40px'>🚌</div><p>Нет связи с сервером</p>"
            + "<button onclick=\"location.href='" + SITE + "'\">Повторить</button></div></body></html>";

    /** Отладочные команды принимаем только при включённой отладке по USB (сборка на GitHub тестирует так). */
    private boolean ciMode() {
        try { return Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1; }
        catch (Exception e) { return false; }
    }

    private boolean online() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wv != null) wv.onResume();
        WidgetProvider.redraw(this);
    }

    @Override
    protected void onPause() {
        if (wv != null) wv.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }

    // ===== Разрешение на уведомления =====
    boolean notifAllowed() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    void askNotifPermission() {
        if (notifAllowed()) return;
        Prefs p = Prefs.of(this);
        boolean askedBefore = p.getLong("askedNotif", 0) > 0;
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            // пользователь запретил насовсем — открываем настройки уведомлений приложения
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } catch (Exception ignored) { }
            return;
        }
        p.putLong("askedNotif", System.currentTimeMillis());
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 1 && notifAllowed() && pendingTest) {
            pendingTest = false;
            BusService.start(this, "test");
        }
        if (wv != null) wv.evaluateJavascript("window.onAndroidSettings && window.onAndroidSettings()", null);
    }

    /** Мост для веб-части: настройки уведомлений, выбор работы, проверка. */
    final class Bridge {
        /** Активное напоминание (JSON) или пустая строка. */
        @JavascriptInterface public String getReminder() {
            JSONObject r = Prefs.of(MainActivity.this).reminder();
            return r == null ? "" : r.toString();
        }

        @JavascriptInterface public String getLead() { return String.valueOf(Prefs.of(MainActivity.this).lead()); }

        /** Напомнить о рейсе: {key, line, from, to, dep, depSched, track, lead}. Одно активное напоминание. */
        @JavascriptInterface public void setReminder(String json) {
            try {
                JSONObject in = new JSONObject(json);
                int lead = Math.max(1, Math.min(30, in.optInt("lead", 5)));
                String from = in.optString("from"), to = in.optString("to");
                if (!Api.STOPS.containsKey(from) || !Api.STOPS.containsKey(to) || in.optLong("dep") <= 0) return;
                JSONObject r = new JSONObject()
                        .put("key", in.optString("key")).put("line", in.optString("line"))
                        .put("from", from).put("to", to)
                        .put("dep", in.optLong("dep")).put("depSched", in.optLong("depSched", in.optLong("dep")))
                        .put("track", in.optString("track")).put("lead", lead).put("set", System.currentTimeMillis());
                Prefs p = Prefs.of(MainActivity.this);
                p.setReminder(r);
                p.setLead(lead);
                Scheduler.reschedule(getApplicationContext());
                runOnUiThread(MainActivity.this::askNotifPermission);
            } catch (Exception e) {
                Log.w(TAG, "setReminder: " + e);
            }
        }

        @JavascriptInterface public void cancelReminder() {
            Prefs.of(MainActivity.this).clearReminder();
            Scheduler.reschedule(getApplicationContext());
        }

        @JavascriptInterface public void setWork(String w) {
            Prefs p = Prefs.of(MainActivity.this);
            if (w == null || w.equals(p.work())) return;
            p.setWork(w);
            WidgetProvider.redraw(getApplicationContext());
            if (WidgetProvider.hasWidgets(getApplicationContext())) RefreshJob.runNow(getApplicationContext());
        }

        @JavascriptInterface public void testNotify() {
            runOnUiThread(() -> {
                if (!notifAllowed()) { pendingTest = true; askNotifPermission(); return; }
                BusService.start(MainActivity.this, "test");
            });
        }

        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME; }

        /** Отправления из справочной NRW для веб-части; ответ приходит в window.__efaDone(id, json). */
        @JavascriptInterface public void efa(String id, String stopKey) {
            new Thread(() -> {
                String json;
                try { json = Api.efaDepartures(stopKey).toString(); }
                catch (Exception e) { Log.w(TAG, "efa: " + e.getMessage()); json = "null"; }
                final String js = "window.__efaDone && window.__efaDone(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")";
                runOnUiThread(() -> { if (wv != null) wv.evaluateJavascript(js, null); });
            }, "labus-efa").start();
        }
    }

    // ===== Проверка новой версии (не чаще раза в 6 часов) =====
    private void checkUpdate() {
        Prefs p = Prefs.of(this);
        long now = System.currentTimeMillis();
        if (now - p.getLong("updCheck", 0) < 3 * 3_600_000L) return;
        p.putLong("updCheck", now);
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) return;
                JSONObject r = new JSONObject(Api.read(c.getInputStream()));
                String tag = r.optString("tag_name");             // android-<номер сборки>
                if (!tag.startsWith("android-")) return;
                int code = Integer.parseInt(tag.substring(8));
                if (code <= BuildConfig.VERSION_CODE) return;
                // файл берём с сайта: со страницы релизов Chrome на Pixel скачивает с зависанием
                final String dl = SITE + "LA-Bus.apk?v=" + code;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Есть новая версия LA Bus")
                            .setMessage("Скачай файл и открой его — обновление встанет поверх, настройки сохранятся.")
                            .setPositiveButton("Скачать", (d, w) -> {
                                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dl))); } catch (Exception ignored) { }
                            })
                            .setNegativeButton("Позже", null)
                            .show();
                });
            } catch (Exception e) {
                Log.w(TAG, "update check: " + e.getMessage());
            }
        }, "labus-update").start();
    }

    // ===== Предпросмотр виджета (для проверки сборки на GitHub) =====
    private void showWidgetPreview() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(40, 60, 70));
        int pad = dp(16);
        root.setPadding(pad, dp(60), pad, pad);
        TextView t = new TextView(this);
        t.setText("Загружаю…");
        t.setTextColor(Color.WHITE);
        root.addView(t);
        FrameLayout host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(dp(330), dp(150)));
        FrameLayout host2 = new FrameLayout(this);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(270), dp(64));
        lp2.topMargin = dp(24);
        root.addView(host2, lp2);
        FrameLayout host3 = new FrameLayout(this);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(dp(180), dp(60));
        lp3.topMargin = dp(16);
        root.addView(host3, lp3);
        FrameLayout host4 = new FrameLayout(this);
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(dp(180), dp(170));
        lp4.topMargin = dp(16);
        root.addView(host4, lp4);
        setContentView(root);
        new Thread(() -> {
            String w = Prefs.of(this).widgetWork();
            Api.Board a = Api.loadOrCached(this, Prefs.HOME, w);
            Api.Board b = Api.loadOrCached(this, w, Prefs.HOME);
            runOnUiThread(() -> {
                t.setText("Предпросмотр виджета · рейсов: " + (a == null ? "—" : a.trips.size()) + " / " + (b == null ? "—" : b.trips.size()));
                long now = System.currentTimeMillis();
                long[] nx = {Long.MAX_VALUE};
                View v1 = WidgetProvider.buildFull(this, now, nx).apply(this, host);
                host.addView(v1, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                View v2 = WidgetProvider.buildCompact(this, now, nx).apply(this, host2);
                host2.addView(v2, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                View v3 = WidgetProvider.buildNarrow(this, now, nx).apply(this, host3);
                View v4 = WidgetProvider.buildSquare(this, now, nx).apply(this, host4);
                host4.addView(v4, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                host3.addView(v3, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            });
        }).start();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
