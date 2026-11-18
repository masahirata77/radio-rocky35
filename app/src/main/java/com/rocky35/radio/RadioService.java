package com.rocky35.radio;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.*;
import org.json.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;

public final class RadioService extends Service {
    public static final String PLAY = "com.rocky35.radio.PLAY", PAUSE = "com.rocky35.radio.PAUSE";
    private static final String CHANNEL = "radio_playback";
    private static final int NOTIFICATION = 35;
    private static volatile String snapshot = "{\"id\":\"\",\"phase\":\"idle\",\"message\":\"Lista para escuchar\"}";
    public static String snapshot() { return snapshot; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private String id = "", name = "", phase = "idle", message = "";
    private final ArrayList<String> urls = new ArrayList<>();
    private int index;
    private Runnable timeout;
    private boolean resumedAfterFocus, hasFocusRequest, foreground, prepared;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { stopPlayback("paused", "En pausa"); }
    };

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(this::focusChanged, handler).build();
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, filter);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Reproducción de radio", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "RadioRocky35");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { stopPlayback("paused", "En pausa"); }
            @Override public void onStop() { stopPlayback("paused", "En pausa"); }
            @Override public void onPlay() {
                if (!id.isEmpty() && !"playing".equals(phase) && !"loading".equals(phase)) begin(id);
            }
        });
        session.setSessionActivity(openApp());
        session.setActive(true);
    }

    public static Intent playIntent(Context c, String id) {
        return new Intent(c, RadioService.class).setAction(PLAY).putExtra("id", id);
    }
    public static Intent pauseIntent(Context c) {
        return new Intent(c, RadioService.class).setAction(PAUSE);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (PLAY.equals(intent.getAction())) begin(intent.getStringExtra("id"));
        else if (PAUSE.equals(intent.getAction())) stopPlayback("paused", "En pausa");
        else stopSelf();
        return START_NOT_STICKY;
    }

    private boolean loadStation(String stationId) {
        try (java.io.InputStream in = getAssets().open("stations.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            JSONArray arr = new JSONArray(bytes.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject station = arr.getJSONObject(i);
                if (!station.optString("id").equals(stationId)) continue;
                id = stationId; name = station.optString("name");
                LinkedHashSet<String> unique = new LinkedHashSet<>();
                JSONArray alternatives = station.optJSONArray("streams");
                if (alternatives != null) for (int j = 0; j < alternatives.length(); j++) unique.add(alternatives.optString(j));
                unique.add(station.optString("url")); unique.add(station.optString("original"));
                urls.clear();
                for (String url : unique) if (url.startsWith("https://") || url.startsWith("http://")) urls.add(url);
                return !urls.isEmpty();
            }
        } catch (Exception e) { android.util.Log.w("RadioRocky35", "Catálogo", e); }
        return false;
    }
    private void begin(String stationId) {
        releasePlayer(); resumedAfterFocus = false;
        if (stationId == null || !loadStation(stationId)) {
            stopPlayback("error", "Emisora no disponible"); return;
        }
        index = 0;
        session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, name)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Radio Rocky 35").build());
        publish("loading", "Conectando…");
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION, notification());
            foreground = true;
        }
        if (!hasFocusRequest) {
            hasFocusRequest = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        if (!hasFocusRequest) { stopPlayback("error", "Audio ocupado por otra aplicación"); return; }
        connect();
    }

    private void connect() {
        releasePlayer();
        if (index >= urls.size()) { stopPlayback("error", "No se pudo conectar"); return; }
        publish("loading", "Conectando…");
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;
                clearTimeout();
                try { mp.start(); prepared = true; publish("playing", "En vivo"); }
                catch (RuntimeException e) { fail(mp); }
            });
            p.setOnErrorListener((mp, what, extra) -> { fail(mp); return true; });
            p.setOnCompletionListener(this::fail);
            p.setOnInfoListener((mp, what, extra) -> {
                if (player != mp) return false;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    publish("loading", "Reconectando…");
                    clearTimeout();
                    timeout = () -> fail(mp);
                    handler.postDelayed(timeout, 20000);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END && prepared) {
                    clearTimeout();
                    publish("playing", "En vivo");
                }
                return false;
            });
            p.setDataSource(urls.get(index));
            p.prepareAsync();
            timeout = () -> fail(p);
            handler.postDelayed(timeout, 20000);
        } catch (Exception e) { fail(p); }
    }
    private void fail(MediaPlayer p) {
        if (p != player) return;
        index++;
        connect();
    }
    private void clearTimeout() {
        if (timeout != null) { handler.removeCallbacks(timeout); timeout = null; }
    }
    private void releasePlayer() {
        clearTimeout();
        MediaPlayer old = player; player = null; prepared = false;
        if (old != null) try { old.release(); } catch (RuntimeException ignored) {}
    }

    private void focusChanged(int focus) {
        if (focus == AudioManager.AUDIOFOCUS_LOSS) {
            stopPlayback("paused", "En pausa");
        } else if (focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumedAfterFocus = "playing".equals(phase) || "loading".equals(phase);
            if (resumedAfterFocus) { releasePlayer(); publish("paused", "Audio interrumpido"); }
        } else if (focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) try { player.setVolume(.2f, .2f); } catch (RuntimeException ignored) {}
        } else if (focus == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumedAfterFocus) { resumedAfterFocus = false; connect(); }
            else if (player != null) try { player.setVolume(1f, 1f); } catch (RuntimeException ignored) {}
        }
    }
    private void stopPlayback(String state, String text) {
        resumedAfterFocus = false;
        releasePlayer();
        if (hasFocusRequest) { audioManager.abandonAudioFocusRequest(focusRequest); hasFocusRequest = false; }
        publish(state, text);
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        stopSelf();
    }

    private void publish(String state, String text) {
        phase = state; message = text;
        try {
            snapshot = new JSONObject().put("id", id).put("phase", phase).put("message", text).toString();
        } catch (JSONException ignored) {}
        int playback = "playing".equals(state) ? PlaybackState.STATE_PLAYING
            : "loading".equals(state) ? PlaybackState.STATE_BUFFERING
            : "error".equals(state) ? PlaybackState.STATE_ERROR : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
            .setState(playback, PlaybackState.PLAYBACK_POSITION_UNKNOWN, playback == PlaybackState.STATE_PLAYING ? 1f : 0f).build());
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        PendingIntent pause = PendingIntent.getService(this, 1, pauseIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_radio_notification)
            .setContentTitle(name).setContentText(message).setContentIntent(openApp())
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pausar", pause).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0))
            .build();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        unregisterReceiver(noisy);
        releasePlayer();
        if (hasFocusRequest) audioManager.abandonAudioFocusRequest(focusRequest);
        if ("playing".equals(phase) || "loading".equals(phase)) publish("paused", "En pausa");
        session.release();
        super.onDestroy();
    }
}
