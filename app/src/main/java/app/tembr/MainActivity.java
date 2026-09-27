package app.tembr;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/** Оболочка: показывает плеер из assets/index.html и держит музыку в фоне через PlaybackService. */
public class MainActivity extends Activity {

    static MainActivity current;
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;

        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#141312"));
        w.setNavigationBarColor(Color.parseColor("#141312"));

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#141312"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " TembrAndroid/1.0");

        web.setWebChromeClient(new WebChromeClient() {
            private View full;
            private CustomViewCallback cb;
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (full != null) { callback.onCustomViewHidden(); return; }
                full = view; cb = callback;
                ((android.widget.FrameLayout) getWindow().getDecorView()).addView(view,
                        new android.widget.FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            @Override
            public void onHideCustomView() {
                if (full == null) return;
                ((android.widget.FrameLayout) getWindow().getDecorView()).removeView(full);
                full = null;
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                if (cb != null) cb.onCustomViewHidden();
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                // внешние ссылки (archive.org и т.п.) открываем в браузере телефона
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "TembrNative");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** Команда из уведомления: toggle / next / prev / pause. */
    void command(final String action) {
        ui.post(() -> {
            if (web != null) web.evaluateJavascript("window.tembr && window.tembr.cmd('" + action + "')", null);
        });
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.tembr ? window.tembr.back() : false", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);   // не закрываем, чтобы музыка играла дальше
        });
    }

    // Не вызываем web.onPause(): иначе WebView остановит звук при сворачивании.
    @Override
    protected void onPause() { super.onPause(); }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, PlaybackService.class));
        if (current == this) current = null;
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    private class Bridge {
        @JavascriptInterface
        public void onTrack(String title, String artist, String cover) {
            PlaybackService.title = title;
            PlaybackService.artist = artist;
            PlaybackService.refresh(MainActivity.this);
        }

        @JavascriptInterface
        public void onPlayState(boolean playing) {
            PlaybackService.playing = playing;
            if (playing) {
                Intent i = new Intent(MainActivity.this, PlaybackService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            } else {
                PlaybackService.refresh(MainActivity.this);
            }
        }
    }
}
