package com.rocky35.radio

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*

class RadioService : Service() {
    companion object {
        private const val PLAY = "com.rocky35.radio.PLAY"
        private const val PAUSE = "com.rocky35.radio.PAUSE"
        private const val CHANNEL = "radio_playback"
        private const val NOTIFICATION = 35
        data class Snapshot(val id:String, val name:String, val phase:String, val message:String)
        private var state = Snapshot("","","idle","Lista para escuchar")
        private val listeners = linkedSetOf<(Snapshot)->Unit>()
        fun snapshot():Snapshot = state
        fun observe(listener:(Snapshot)->Unit) { listeners.add(listener);listener(state) }
        fun removeObserver(listener:(Snapshot)->Unit) { listeners.remove(listener) }
        private fun emit(snapshot:Snapshot) {
            if(state==snapshot)return
            state=snapshot
            listeners.toList().forEach{it(snapshot)}
        }
        @JvmStatic fun playIntent(c:Context,id:String)=Intent(c,RadioService::class.java).setAction(PLAY).putExtra("id",id)
        @JvmStatic fun pauseIntent(c:Context)=Intent(c,RadioService::class.java).setAction(PAUSE)
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var session: MediaSession
    private var player: MediaPlayer? = null
    private var id = ""
    private var name = ""
    private var phase = "idle"
    private var message = ""
    private var urls = emptyList<String>()
    private var index = 0
    private var timeout: Runnable? = null
    private var focus = false
    private var foreground = false
    private var prepared = false
    private var resumeAfterFocus = false
    private val noisy = object: BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){stopPlayback("paused","En pausa")}
    }
    override fun onCreate(){
        super.onCreate()
        audio=getSystemService(AUDIO_SERVICE) as AudioManager
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener({focusChanged(it)},handler).build()
        val filter=IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(noisy,filter)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Reproducción de radio",NotificationManager.IMPORTANCE_LOW))
        session=MediaSession(this,"RadioRocky35")
        session.setCallback(object: MediaSession.Callback(){
            override fun onPause(){stopPlayback("paused","En pausa")}
            override fun onStop(){stopPlayback("paused","En pausa")}
            override fun onPlay(){if(id.isNotEmpty()&&phase!="playing"&&phase!="loading")begin(id)}
        })
        session.setSessionActivity(openApp());session.isActive=true
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            PLAY->begin(intent.getStringExtra("id"))
            PAUSE->stopPlayback("paused","En pausa")
            else->stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun loadStation(stationId:String):Boolean{
        val station=Catalog.station(stationId)
        if(station.size<2)return false
        id=stationId;name=station[0]
        urls=station.asList().subList(1,station.size)
        return true
    }
    private fun begin(stationId:String?){
        releasePlayer();resumeAfterFocus=false
        if(stationId==null||!loadStation(stationId)){stopPlayback("error","Emisora no disponible");return}
        index=0
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,name).putString(MediaMetadata.METADATA_KEY_ARTIST,"Radio Rocky 35").build())
        publish("loading","Conectando…")
        if(!foreground){
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION,notification())
            foreground=true
        }
        if(!focus)focus=audio.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(!focus){stopPlayback("error","Audio ocupado por otra aplicación");return}
        connect()
    }
    private fun connect(){
        releasePlayer()
        if(index>=urls.size){stopPlayback("error","No se pudo conectar");return}
        publish("loading","Conectando…")
        val p=MediaPlayer();player=p
        try{
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK)
            p.setOnPreparedListener{mp->if(player===mp){clearTimeout();try{mp.start();prepared=true;publish("playing","En vivo")}catch(e:RuntimeException){fail(mp)}}}
            p.setOnErrorListener{mp,_,_->fail(mp);true}
            p.setOnCompletionListener{fail(it)}
            p.setOnInfoListener{mp,what,_->
                if(player===mp){
                    if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START){publish("loading","Reconectando…");clearTimeout();timeout=Runnable{fail(mp)};handler.postDelayed(timeout!!,20000)}
                    else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END&&prepared){clearTimeout();publish("playing","En vivo")}
                }
                false
            }
            p.setDataSource(urls[index]);p.prepareAsync();timeout=Runnable{fail(p)};handler.postDelayed(timeout!!,20000)
        }catch(e:Exception){fail(p)}
    }
    private fun fail(p:MediaPlayer){if(p===player){index++;connect()}}
    private fun clearTimeout(){timeout?.let{handler.removeCallbacks(it)};timeout=null}
    private fun releasePlayer(){clearTimeout();val old=player;player=null;prepared=false;try{old?.release()}catch(_:RuntimeException){}}
    private fun focusChanged(change:Int){
        when(change){
            AudioManager.AUDIOFOCUS_LOSS->stopPlayback("paused","En pausa")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->{resumeAfterFocus=phase=="playing"||phase=="loading";if(resumeAfterFocus){releasePlayer();publish("paused","Audio interrumpido")}}
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->try{player?.setVolume(.2f,.2f)}catch(_:RuntimeException){}
            AudioManager.AUDIOFOCUS_GAIN->if(resumeAfterFocus){resumeAfterFocus=false;connect()}else try{player?.setVolume(1f,1f)}catch(_:RuntimeException){}
        }
    }
    private fun stopPlayback(p:String,text:String){
        resumeAfterFocus=false;releasePlayer()
        if(focus){audio.abandonAudioFocusRequest(focusRequest);focus=false}
        publish(p,text)
        if(foreground){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false}
        stopSelf()
    }
    private fun publish(p:String,text:String){
        phase=p;message=text;emit(Snapshot(id,name,p,text))
        val playback=when(p){"playing"->PlaybackState.STATE_PLAYING;"loading"->PlaybackState.STATE_BUFFERING;"error"->PlaybackState.STATE_ERROR;else->PlaybackState.STATE_PAUSED}
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP).setState(playback,PlaybackState.PLAYBACK_POSITION_UNKNOWN,if(playback==PlaybackState.STATE_PLAYING)1f else 0f).build())
        if(foreground)getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
    }
    private fun openApp()=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification():Notification{
        val pause=PendingIntent.getService(this,1,pauseIntent(this),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_radio_notification).setContentTitle(name).setContentText(message).setContentIntent(openApp()).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(true).addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,"Pausar",pause).build()).setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0)).build()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){
        unregisterReceiver(noisy);releasePlayer();if(focus)audio.abandonAudioFocusRequest(focusRequest)
        if(phase=="playing"||phase=="loading")publish("paused","En pausa")
        session.release();super.onDestroy()
    }
}
