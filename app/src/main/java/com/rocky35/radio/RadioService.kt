package com.rocky35.radio

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.os.*

class RadioService : android.service.media.MediaBrowserService() {
    companion object {
        private const val PLAY = "com.rocky35.radio.PLAY"
        private const val PAUSE = "com.rocky35.radio.PAUSE"
        private const val STOP = "com.rocky35.radio.STOP"
        private const val RESUME = "com.rocky35.radio.RESUME"
        private const val COPY = "com.rocky35.radio.COPY"
        private const val SEARCH = "com.rocky35.radio.SEARCH"
        private const val NEXT = "com.rocky35.radio.NEXT"
        private const val PREV = "com.rocky35.radio.PREV"
        // Lista que ve el usuario (Todas o Favoritas, con orden y filtros): la usan ⏮ ⏭ de la notificación, el volante y Android Auto.
        fun setQueue(c:Context,ids:List<String>){c.getSharedPreferences("radio",Context.MODE_PRIVATE).edit().putString("queue",ids.joinToString(",")).apply()}
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
        // Bytes de audio que pasan por el puente local (para mostrar kB/s sin contar el tráfico interno).
        @Volatile private var proxyBytes=0L
        @Volatile var proxyActive=false
        fun addBytes(n:Int){proxyBytes+=n}
        fun rxBytes(uid:Int):Long=if(proxyActive)proxyBytes else android.net.TrafficStats.getUidRxBytes(uid)
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
    private var proxy: IcyProxy? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var program = ""
    private val programTick = object:Runnable{override fun run(){
        if(!Programs.has(name))return
        val sid=id
        try{io.execute{val p=Programs.current(name);handler.post{if(sid==id&&p!=program){program=p;publish(phase,message)}}}}catch(_:RuntimeException){}
        handler.postDelayed(this,60000)
    }}
    private val titleTick = object:Runnable{override fun run(){
        if(phase!="playing"&&phase!="loading")return
        handler.postDelayed(this,30000)
        if(phase!="playing"||titleMisses>=3||proxy!=null)return
        val u=urls.getOrNull(index)?:return;val sid=id;val st=name
        try{io.execute{val t=fetchTitle(u);handler.post{applyTitle(sid,st,t)}}}catch(_:RuntimeException){}
    }}
    // Título que llega dentro del mismo audio: se aplica al instante.
    private fun applyInline(sid:String,t:String){
        if(sid!=id)return
        if(!usefulTitle(t,name)){if(song.isNotEmpty()){song="";publish(phase,message)};return}
        if(t!=song){song=t;publish(phase,message)}
    }
    private fun applyTitle(sid:String,st:String,t:String?){
        if(sid!=id)return
        if(!usefulTitle(t,st)){titleMisses++;if(titleMisses>=3&&song.isNotEmpty()){song="";publish(phase,message)};return}
        titleMisses=0
        if(t!=song){song=t!!;publish(phase,message)}
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
            override fun onSkipToNext(){skip(1)}
            override fun onSkipToPrevious(){skip(-1)}
            override fun onPlayFromMediaId(mediaId:String?,extras:Bundle?){
                val m=mediaId?:return;val sid=m.substringAfter(':')
                setQueue(this@RadioService,if(m.startsWith("fav:"))favIds() else allIds())
                startService(Intent(this@RadioService,RadioService::class.java));begin(sid)
            }
            override fun onCustomAction(action:String,extras:Bundle?){when(action){STOP->stopPlayback("stopped","Detenida");COPY->copyText()?.let{copyToClipboard(this@RadioService,it)};SEARCH->searchPrompt()}}
        })
        session.setSessionActivity(openApp());session.isActive=true
        sessionToken=session.sessionToken
        try{getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)}catch(_:RuntimeException){}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            PLAY->begin(intent.getStringExtra("id"))
            PAUSE->pausePlayback()
            STOP->stopPlayback("stopped","Detenida")
            RESUME->resume()
            NEXT->skip(1)
            PREV->skip(-1)
            COPY->{copyText()?.let{copyToClipboard(this,it)};if(!foreground)stopSelf()}
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
        handler.removeCallbacks(programTick);program="";if(Programs.has(name))handler.post(programTick)
        lastMeta="";updateMetadata()
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
            val src=urls[index];val sid=id
            val px=if(src.contains(".m3u8",true))null else try{IcyProxy(src){t->handler.post{applyInline(sid,t)}}.also{it.start()}}catch(_:Exception){null}
            proxy=px;proxyActive=px!=null
            p.setDataSource(px?.localUrl?:src);p.prepareAsync();timeout=Runnable{fail(p)};handler.postDelayed(timeout!!,20000)
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
    private fun releasePlayer(){proxy?.close();proxy=null;proxyActive=false;clearTimeout();retry?.let{handler.removeCallbacks(it)};retry=null;val old=player;player=null;prepared=false;try{old?.release()}catch(_:RuntimeException){}}
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
    // Texto principal (chip de la barra de estado, persiana y notificación):
    // tema si la radio lo manda; si no, "Radio · Programa"; si no, solo la radio.
    private fun nowTitle():Pair<String,String>{
        val live=phase=="playing"
        val prog=program.removePrefix("🎙 ")
        return when{
            live&&song.isNotEmpty()->song to name
            live&&program.isNotEmpty()&&program!="🎙 Selección musical"->"$name · ${prog.substringBefore(" · ")}" to prog.substringAfter(" · ","")
            else->name to "Radio Rocky 35"
        }
    }
    private var lastMeta=""
    private fun updateMetadata(){
        val (t,sub)=nowTitle();val k="$t|$sub"
        if(k==lastMeta)return;lastMeta=k
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t).putString(MediaMetadata.METADATA_KEY_ARTIST,sub).putString(MediaMetadata.METADATA_KEY_ALBUM,name).build())
    }
    // Anota en el historial cada tema o programa nuevo mientras suena.
    private var lastLogged=""
    private fun logHistory(){
        if(phase!="playing")return
        val (t,txt)=when{song.isNotEmpty()->'s' to song;program.isNotEmpty()&&program!="🎙 Selección musical"->'p' to program.removePrefix("🎙 ");else->return}
        val k="$id|$t|$txt";if(k==lastLogged)return;lastLogged=k
        val st=name;val ctx=applicationContext
        try{io.execute{try{History.append(ctx,t,st,txt)}catch(_:Exception){}}}catch(_:Exception){}
    }
    private fun publish(p:String,text:String){
        updateMetadata()
        phase=p;message=text;logHistory();emit(Snapshot(id,name,p,text,if(p=="playing")song.ifEmpty{program} else ""))
        val playback=when(p){"playing"->PlaybackState.STATE_PLAYING;"loading"->PlaybackState.STATE_BUFFERING;"error"->PlaybackState.STATE_ERROR;else->PlaybackState.STATE_PAUSED}
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID).setState(playback,PlaybackState.PLAYBACK_POSITION_UNKNOWN,if(playback==PlaybackState.STATE_PLAYING)1f else 0f)
            .apply{
                if(p!="stopped"&&p!="error"&&copyText()!=null)addCustomAction(PlaybackState.CustomAction.Builder(COPY,"Copiar",R.drawable.ic_copy).build())
                if(p!="stopped"&&p!="error"&&searchIntent()!=null)addCustomAction(PlaybackState.CustomAction.Builder(SEARCH,"Buscar",R.drawable.ic_search).build())
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
            .setContentTitle(nowTitle().first).setContentText(if(phase=="playing"&&song.isNotEmpty())name else if(phase=="playing"&&nowTitle().second.isNotEmpty())nowTitle().second else message).addAction(Notification.Action.Builder(R.drawable.ic_prev,"Anterior",PendingIntent.getService(this,8,Intent(this,RadioService::class.java).setAction(PREV),f)).build()).addAction(main).addAction(Notification.Action.Builder(R.drawable.ic_next,"Siguiente",PendingIntent.getService(this,9,Intent(this,RadioService::class.java).setAction(NEXT),f)).build()).addAction(stop)
        val keep=phase!="stopped"&&phase!="error";val hasSong=keep&&copyText()!=null
        if(hasSong)b.addAction(Notification.Action.Builder(R.drawable.ic_copy,"Copiar",PendingIntent.getService(this,5,Intent(this,RadioService::class.java).setAction(COPY),f)).build())
        val web=if(keep)searchIntent() else null
        if(web!=null)b.addAction(Notification.Action.Builder(R.drawable.ic_search,"Buscar",PendingIntent.getActivity(this,6,web,f)).build())
        return b.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2)).build()
    }
    // Tema si la radio lo manda; si no, radio + programa de la grilla.
    private fun copyText():String?=when{
        song.isNotEmpty()->song
        program.isNotEmpty()&&program!="🎙 Selección musical"->"$name · "+program.removePrefix("🎙 ")
        else->null
    }
    // Android no deja abrir el navegador desde el reproductor de la persiana (bloqueo de inicio en segundo plano),
    // así que se muestra un aviso: al tocarlo, el sistema sí permite abrir la búsqueda.
    private fun searchPrompt(){
        val web=searchIntent()?:return
        val nm=getSystemService(NotificationManager::class.java)
        if(nm.getNotificationChannel("radio_search")==null)nm.createNotificationChannel(NotificationChannel("radio_search","Buscar tema o programa",NotificationManager.IMPORTANCE_HIGH).apply{setSound(null,null);enableVibration(false)})
        val q=android.net.Uri.parse(web.dataString).getQueryParameter("q")?:""
        nm.notify(36,Notification.Builder(this,"radio_search").setSmallIcon(R.drawable.ic_search).setContentTitle("🔎 Tocá para buscar").setContentText(q)
            .setContentIntent(PendingIntent.getActivity(this,7,web,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).setTimeoutAfter(20000).setCategory(Notification.CATEGORY_RECOMMENDATION).build())
    }
    // Búsqueda directa: tema si hay; si no, radio + nombre del programa.
    private fun searchIntent():Intent?{
        val q=when{
            song.isNotEmpty()->song
            program.isNotEmpty()&&program!="🎙 Selección musical"->"$name "+program.removePrefix("🎙 ").substringBefore(" · ")
            else->return null
        }
        return Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.google.com/search?q="+android.net.Uri.encode(q))).addCategory(Intent.CATEGORY_BROWSABLE)
    }
    override fun onBind(intent:Intent?):IBinder?=if(intent?.action==SERVICE_INTERFACE)super.onBind(intent) else null
    private fun prefs()=getSharedPreferences("radio",MODE_PRIVATE)
    private fun allIds()=(0 until Catalog.size).map{Catalog.id(it)}
    private fun favIds():List<String>{val out=ArrayList<String>();try{val a=org.json.JSONArray(prefs().getString("favorites","[]")?:"[]");for(i in 0 until a.length())out.add(a.getString(i))}catch(_:Exception){};return out}
    private fun skip(d:Int){
        // Por defecto ⏮⏭ recorren las favoritas (en el orden de la última lista vista); sin favoritas, la lista vista.
        val seen=(prefs().getString("queue","")?:"").split(',').filter{it.isNotEmpty()&&Catalog.station(it).isNotEmpty()}
        val fav=favIds().filter{Catalog.station(it).isNotEmpty()}
        val favSeen=seen.filter{it in fav}
        val q=(if(fav.isNotEmpty())(favSeen+fav.filter{it !in favSeen}) else seen).ifEmpty{allIds()}
        val i=q.indexOf(id);val next=q[if(i<0)0 else Math.floorMod(i+d,q.size)]
        ensureForeground();begin(next)
    }
    // Android Auto: raíz con Favoritas y Todas; cada radio se puede reproducir.
    override fun onGetRoot(clientPackageName:String,clientUid:Int,rootHints:Bundle?)=BrowserRoot("root",null)
    override fun onLoadChildren(parentId:String,result:Result<MutableList<android.media.browse.MediaBrowser.MediaItem>>){
        fun folder(mid:String,title:String)=android.media.browse.MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId(mid).setTitle(title).build(),android.media.browse.MediaBrowser.MediaItem.FLAG_BROWSABLE)
        fun station(prefix:String,sid:String):android.media.browse.MediaBrowser.MediaItem?{val st=Catalog.station(sid);if(st.isEmpty())return null
            return android.media.browse.MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId("$prefix:$sid").setTitle(st[0]).setSubtitle(Catalog.qualityOf(sid)).build(),android.media.browse.MediaBrowser.MediaItem.FLAG_PLAYABLE)}
        val items:MutableList<android.media.browse.MediaBrowser.MediaItem> = when(parentId){
            "root"->mutableListOf(folder("fav","★ Favoritas"),folder("all","Todas las radios"))
            "fav"->favIds().mapNotNull{station("fav",it)}.toMutableList()
            "all"->allIds().mapNotNull{station("all",it)}.sortedBy{it.description.title.toString().lowercase()}.toMutableList()
            else->mutableListOf()
        }
        result.sendResult(items)
    }
    override fun onDestroy(){
        try{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)}catch(_:RuntimeException){}
        unregisterReceiver(noisy);releasePlayer();if(focus)audio.abandonAudioFocusRequest(focusRequest)
        if(phase=="playing"||phase=="loading")publish("paused","En pausa")
        handler.removeCallbacks(titleTick);handler.removeCallbacks(programTick);io.shutdownNow()
        session.release();super.onDestroy()
    }
}
