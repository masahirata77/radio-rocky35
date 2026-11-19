package com.rocky35.radio

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.os.*

class RadioService : Service() {
    companion object {
        private const val PLAY = "com.rocky35.radio.PLAY"
        private const val PAUSE = "com.rocky35.radio.PAUSE"
        private const val STOP = "com.rocky35.radio.STOP"
        private const val RESUME = "com.rocky35.radio.RESUME"
        private const val COPY = "com.rocky35.radio.COPY"
        private const val CHANNEL = "radio_playback"
        private const val NOTIFICATION = 35
        data class Snapshot(val id:String, val name:String, val phase:String, val message:String, val title:String="")
        private var state = Snapshot("","","idle","Lista para escuchar")
        private val listeners = linkedSetOf<(Snapshot)->Unit>()
        fun snapshot():Snapshot = state
        fun copyToClipboard(c:Context,text:String){
            c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tema",text))
            if(Build.VERSION.SDK_INT<33)android.widget.Toast.makeText(c,"Copiado: $text",android.widget.Toast.LENGTH_SHORT).show()
        }
        fun observe(listener:(Snapshot)->Unit) { listeners.add(listener);listener(state) }
        fun removeObserver(listener:(Snapshot)->Unit) { listeners.remove(listener) }
        private fun emit(snapshot:Snapshot) {
            if(state==snapshot)return
            state=snapshot
            listeners.toList().forEach{it(snapshot)}
        }
        private val junk=listOf("hi-fi internet","azuracast","mike llama","audiocheck","use http","http","www.","sinsonido","en vivo","stream")
        fun fetchTitle(u:String):String?{
            var c:java.net.HttpURLConnection?=null
            return try{
                c=java.net.URL(u).openConnection() as java.net.HttpURLConnection
                c.connectTimeout=8000;c.readTimeout=8000;c.setRequestProperty("Icy-MetaData","1");c.setRequestProperty("User-Agent","RadioRocky35")
                val mi=c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull()?:return null
                if(mi<=0||mi>65536)return null
                val inp=java.io.DataInputStream(java.io.BufferedInputStream(c.inputStream,8192))
                inp.readFully(ByteArray(mi))
                val len=inp.read()*16;if(len<=0)return ""
                val m=ByteArray(len);inp.readFully(m)
                var s=String(m,Charsets.UTF_8);if('\uFFFD' in s)s=String(m,Charsets.ISO_8859_1)
                Regex("StreamTitle='(.*?)';").find(s)?.groupValues?.get(1)?.trim()
            }catch(e:Exception){null}finally{try{c?.disconnect()}catch(_:Exception){}}
        }
        fun usefulTitle(t:String?,station:String):Boolean{
            if(t==null||t.length<3)return false
            val l=t.lowercase();if(junk.any{it in l})return false
            val a=l.filter{it.isLetterOrDigit()};val b=station.lowercase().filter{it.isLetterOrDigit()}
            return a.isNotEmpty()&&!b.contains(a)&&(b.isEmpty()||!a.contains(b.take(8)))
        }
        @JvmStatic fun playIntent(c:Context,id:String)=Intent(c,RadioService::class.java).setAction(PLAY).putExtra("id",id)
        @JvmStatic fun pauseIntent(c:Context)=Intent(c,RadioService::class.java).setAction(PAUSE)
        fun stopIntent(c:Context)=Intent(c,RadioService::class.java).setAction(STOP)
        fun resumeIntent(c:Context)=Intent(c,RadioService::class.java).setAction(RESUME)
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
    private var round = 0
    private var song = ""
    private var titleMisses = 0
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val titleTick = object:Runnable{override fun run(){
        if(phase!="playing"&&phase!="loading")return
        handler.postDelayed(this,30000)
        if(phase!="playing"||titleMisses>=3)return
        val u=urls.getOrNull(index)?:return;val sid=id;val st=name
        try{io.execute{val t=fetchTitle(u);handler.post{applyTitle(sid,st,t)}}}catch(_:RuntimeException){}
    }}
    private fun applyTitle(sid:String,st:String,t:String?){
        if(sid!=id)return
        if(!usefulTitle(t,st)){titleMisses++;if(titleMisses>=3&&song.isNotEmpty()){song="";publish(phase,message)};return}
        titleMisses=0
        if(t!=song){song=t!!;session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,name).putString(MediaMetadata.METADATA_KEY_ARTIST,song).build());publish(phase,message)}
    }
    private fun markDown(on:Boolean){
        if(id.isEmpty())return
        val p=getSharedPreferences("radio",MODE_PRIVATE);val m=Health.read(p)
        val before=m[id]?:0
        if(on)m[id]=maxOf(before,1) else m.remove(id)
        if((m[id]?:0)!=before)Health.write(p,m)
    }
    private var retry: Runnable? = null
    private val delays = longArrayOf(2000,4000,8000,15000,30000)
    private val network = object: ConnectivityManager.NetworkCallback(){
        override fun onAvailable(n:Network){handler.post{retry?.let{handler.removeCallbacks(it);retry=null;connect()}}}
    }
    private val noisy = object: BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){pausePlayback()}
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
            override fun onPause(){pausePlayback()}
            override fun onStop(){stopPlayback("stopped","Detenida")}
            override fun onPlay(){if(phase!="playing"&&phase!="loading")resume()}
            override fun onCustomAction(action:String,extras:Bundle?){when(action){STOP->stopPlayback("stopped","Detenida");COPY->if(song.isNotEmpty())copyToClipboard(this@RadioService,song)}}
        })
        session.setSessionActivity(openApp());session.isActive=true
        try{getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)}catch(_:RuntimeException){}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            PLAY->begin(intent.getStringExtra("id"))
            PAUSE->pausePlayback()
            STOP->stopPlayback("stopped","Detenida")
            RESUME->resume()
            COPY->{if(song.isNotEmpty())copyToClipboard(this,song);if(!foreground)stopSelf()}
            else->stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun loadStation(stationId:String):Boolean{
        val station=Catalog.station(stationId)
        if(station.size<2)return false
        id=stationId;name=station[0];getSharedPreferences("radio",MODE_PRIVATE).edit().putString("last",stationId).apply()
        urls=station.asList().subList(1,station.size)
        return true
    }
    private fun begin(stationId:String?){
        releasePlayer();resumeAfterFocus=false
        if(stationId==null||!loadStation(stationId)){stopPlayback("error","Emisora no disponible");return}
        index=0;round=0;song="";titleMisses=0
        handler.removeCallbacks(titleTick);handler.postDelayed(titleTick,4000)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,name).putString(MediaMetadata.METADATA_KEY_ARTIST,"Radio Rocky 35").build())
        publish("loading","Conectando…")
        ensureForeground()
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
            p.setOnPreparedListener{mp->if(player===mp){clearTimeout();try{mp.start();prepared=true;round=0;markDown(false);publish("playing","En vivo")}catch(e:RuntimeException){fail(mp)}}}
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
    private fun fail(p:MediaPlayer){
        if(p!==player)return
        index++
        if(index<urls.size){connect();return}
        releasePlayer();index=0
        if(round>=delays.size){markDown(true);stopPlayback("error","No se pudo conectar");return}
        val wait=delays[round++]
        publish("loading","Reconectando… intento $round de ${delays.size}")
        val r=Runnable{retry=null;connect()};retry=r;handler.postDelayed(r,wait)
    }
    private fun clearTimeout(){timeout?.let{handler.removeCallbacks(it)};timeout=null}
    private fun releasePlayer(){clearTimeout();retry?.let{handler.removeCallbacks(it)};retry=null;val old=player;player=null;prepared=false;try{old?.release()}catch(_:RuntimeException){}}
    private fun focusChanged(change:Int){
        when(change){
            AudioManager.AUDIOFOCUS_LOSS->pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->{resumeAfterFocus=phase=="playing"||phase=="loading";if(resumeAfterFocus){releasePlayer();publish("paused","Audio interrumpido")}}
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->try{player?.setVolume(.2f,.2f)}catch(_:RuntimeException){}
            AudioManager.AUDIOFOCUS_GAIN->if(resumeAfterFocus){resumeAfterFocus=false;connect()}else try{player?.setVolume(1f,1f)}catch(_:RuntimeException){}
        }
    }
    private fun ensureForeground(){
        if(foreground)return
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION,notification())
        foreground=true
    }
    private fun resume(){
        ensureForeground()
        val last=id.ifEmpty{getSharedPreferences("radio",MODE_PRIVATE).getString("last","")?:""}
        if(last.isEmpty()){stopPlayback("stopped","Detenida");return}
        begin(last)
    }
    private fun pausePlayback(){
        resumeAfterFocus=false;releasePlayer()
        if(focus){audio.abandonAudioFocusRequest(focusRequest);focus=false}
        publish("paused","En pausa")
        if(foreground){stopForeground(STOP_FOREGROUND_DETACH);foreground=false}
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
    }
    private fun stopPlayback(p:String,text:String){
        resumeAfterFocus=false;releasePlayer()
        if(focus){audio.abandonAudioFocusRequest(focusRequest);focus=false}
        publish(p,text)
        if(foreground){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false}
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        stopSelf()
    }
    private fun publish(p:String,text:String){
        phase=p;message=text;emit(Snapshot(id,name,p,text,if(p=="playing")song else ""))
        val playback=when(p){"playing"->PlaybackState.STATE_PLAYING;"loading"->PlaybackState.STATE_BUFFERING;"error"->PlaybackState.STATE_ERROR;else->PlaybackState.STATE_PAUSED}
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP).setState(playback,PlaybackState.PLAYBACK_POSITION_UNKNOWN,if(playback==PlaybackState.STATE_PLAYING)1f else 0f)
            .apply{
                if(p=="playing"&&song.isNotEmpty())addCustomAction(PlaybackState.CustomAction.Builder(COPY,"Copiar",R.drawable.ic_copy).build())
                addCustomAction(PlaybackState.CustomAction.Builder(STOP,"Detener",R.drawable.ic_stop).build())
            }.build())
        if(foreground)getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification())
    }
    private fun openApp()=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification():Notification{
        val f=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val active=phase=="playing"||phase=="loading"
        val main=if(active)Notification.Action.Builder(android.R.drawable.ic_media_pause,"Pausa",PendingIntent.getService(this,1,pauseIntent(this),f)).build()
            else Notification.Action.Builder(android.R.drawable.ic_media_play,"Play",PendingIntent.getForegroundService(this,3,resumeIntent(this),f)).build()
        val stop=Notification.Action.Builder(R.drawable.ic_stop,"Detener",PendingIntent.getService(this,2,stopIntent(this),f)).build()
        val b=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_radio_notification).setContentIntent(openApp()).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(active)
            .setDeleteIntent(PendingIntent.getService(this,4,stopIntent(this),f))
            .setContentTitle(name).setContentText(if(phase=="playing"&&song.isNotEmpty())"♪ $song" else message).addAction(main).addAction(stop)
        val hasSong=phase=="playing"&&song.isNotEmpty()
        if(hasSong)b.addAction(Notification.Action.Builder(R.drawable.ic_copy,"Copiar",PendingIntent.getService(this,5,Intent(this,RadioService::class.java).setAction(COPY),f)).build())
        return b.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).apply{if(hasSong)setShowActionsInCompactView(0,1,2) else setShowActionsInCompactView(0,1)}).build()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){
        try{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)}catch(_:RuntimeException){}
        unregisterReceiver(noisy);releasePlayer();if(focus)audio.abandonAudioFocusRequest(focusRequest)
        if(phase=="playing"||phase=="loading")publish("paused","En pausa")
        handler.removeCallbacks(titleTick);io.shutdownNow()
        session.release();super.onDestroy()
    }
}
