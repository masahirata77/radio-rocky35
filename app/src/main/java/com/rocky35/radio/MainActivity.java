package com.rocky35.radio;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.net.Uri;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.media.AudioManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;

public class MainActivity extends Activity {
    private WebView web;
    private AudioManager audioManager;
    private static final String HOST = "onda35.local";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(16,21,30));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16,21,30));
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(i.left,i.top,i.right,i.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new VolumeBridge(), "RockyAndroidVolume");
        web.addJavascriptInterface(new PlaybackBridge(), "RockyAndroidPlayback");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return !isLocal(request.getUrl());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!isLocal(uri)) return null;
                String path = uri.getPath();
                String file, mime;
                if ("/".equals(path) || "/index.html".equals(path)) {file="index.html";mime="text/html";}
                else if ("/app.js".equals(path)) {file="app.js";mime="application/javascript";}
                else if ("/stations.json".equals(path)) {file="stations.json";mime="application/json";}
                else return missing();
                try {return new WebResourceResponse(mime,"UTF-8",getAssets().open(file));}
                catch (IOException e) {return missing();}
            }
        });
        web.loadUrl("http://onda35.local/index.html");
    }
    private final class PlaybackBridge {
        @JavascriptInterface public String state() { return RadioService.snapshot(); }
        @JavascriptInterface public void play(String id) {
            if (id == null || id.length() > 100) return;
            runOnUiThread(() -> {
                try { startForegroundService(RadioService.playIntent(MainActivity.this, id)); }
                catch (RuntimeException e) { android.util.Log.w("RadioRocky35", "No se pudo iniciar el servicio", e); }
            });
        }
        @JavascriptInterface public void pause() {
            runOnUiThread(() -> startService(RadioService.pauseIntent(MainActivity.this)));
        }
    }
    private final class VolumeBridge {
        @JavascriptInterface public int getMax() {
            return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        }
        @JavascriptInterface public int getMin() {
            return android.os.Build.VERSION.SDK_INT >= 28
                ? audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
        }
        @JavascriptInterface public int getVolume() {
            return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        @JavascriptInterface public void setVolume(int value) {
            int safe = Math.max(getMin(), Math.min(getMax(), value));
            runOnUiThread(() -> audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0));
        }
    }
    private static boolean isLocal(Uri uri) { return "http".equals(uri.getScheme()) && HOST.equals(uri.getHost()); }
    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
    }
    @Override protected void onDestroy() {
        if (web != null) {web.destroy();web=null;}
        super.onDestroy();
    }
}
