package com.rocky35.radio

import android.app.Activity
import android.os.*
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.TrafficStats
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import org.json.*

class MainActivity : Activity() {
    private val bg = Color.rgb(16,24,38)
    private val panel = Color.rgb(27,38,56)
    private val line = Color.rgb(53,68,91)
    private val fg = Color.rgb(245,248,255)
    private val muted = Color.rgb(185,199,218)
    private val blue = Color.rgb(155,213,255)
    private val yellow = Color.rgb(255,212,81)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var region: Spinner
    private lateinit var allTab: Button
    private lateinit var favTab: Button
    private lateinit var heading: TextView
    private lateinit var count: TextView
    private lateinit var now: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var favorite: Button
    private lateinit var volume: SeekBar
    private lateinit var percent: TextView
    private lateinit var copy: ImageButton
    private lateinit var adapter: StationAdapter
    private val favorites = linkedSetOf<String>()
    private val departments = mutableListOf("", "unknown")
    private var onlyFavorites = false
    private var selected = ""
    private var phase = "idle"
    private var rows = IntArray(0)
    private var detached = false
    private var firstRow = 0
    private var firstOffset = 0
    private var changing = false
    private var draggingVolume = false
    private val prefs by lazy { getSharedPreferences("radio", MODE_PRIVATE) }
    private val playbackObserver: (RadioService.Companion.Snapshot)->Unit = { syncPlayback(it) }
    private val volumeObserver = object:ContentObserver(handler){
        override fun onChange(selfChange:Boolean){syncVolume()}
    }
    private val audioDevices = object:AudioDeviceCallback(){
        override fun onAudioDevicesAdded(devices:Array<out AudioDeviceInfo>){syncVolume()}
        override fun onAudioDevicesRemoved(devices:Array<out AudioDeviceInfo>){syncVolume()}
    }
    private val volumeSync = Runnable {syncVolume()}
    private val searchRefresh = Runnable {refresh()}
    private var message = ""
    private var song = ""
    private var lastRate = ""
    private var down = HashMap<String,Int>()
    private val alert = Color.rgb(255,138,128)
    private var lastRx = -1L
    private var lastAt = 0L
    private val rateTick = object:Runnable{override fun run(){updateRate();handler.postDelayed(this,1000)}}
    private val allOn by lazy{shape(Color.rgb(48,72,107))}
    private val allOff by lazy{shape(panel)}
    private val favOn by lazy{shape(Color.rgb(48,72,107))}
    private val favOff by lazy{shape(panel)}
    private fun scheduleSearch(){handler.removeCallbacks(searchRefresh);handler.postDelayed(searchRefresh,100)}
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun shape(c: Int, radius:Int=12, border:Int=0) = GradientDrawable().apply { setColor(c);cornerRadius=dp(radius).toFloat();if(border!=0)setStroke(dp(1),border) }
    private fun label(t:String,size:Float=15f,c:Int=fg,bold:Boolean=false) = TextView(this).apply {
        text=t;textSize=size;setTextColor(c);if(bold)setTypeface(null,Typeface.BOLD)
    }
    private fun button(t:String,c:Int=fg) = Button(this).apply {
        text=t;isAllCaps=false;textSize=15f;setTextColor(c);background=shape(panel);minimumHeight=0;minimumWidth=0;minHeight=0;minWidth=0;setPadding(dp(8),0,dp(8),0)
    }
    private fun col()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private fun LinearLayout.addFull(v:View,h:Int=ViewGroup.LayoutParams.WRAP_CONTENT,space:Int=0) {
        addView(v,LinearLayout.LayoutParams(-1,if(h<0)h else dp(h)).apply {bottomMargin=dp(space)})
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        audio=getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeControlStream=AudioManager.STREAM_MUSIC
        try { val a=JSONArray(prefs.getString("favorites","[]")?: "[]");for(i in 0 until a.length())favorites.add(a.getString(i)) }catch(_:Exception){}
        departments.clear();departments.addAll(Catalog.departments())
        val root=col().apply {setBackgroundColor(bg);setPadding(dp(12),dp(14),dp(12),dp(10))}
        if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v,ins ->
            if(Build.VERSION.SDK_INT>=30) {
                val i=ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(dp(12)+i.left,dp(14)+i.top,dp(12)+i.right,dp(10)+i.bottom)
            } else v.setPadding(dp(12)+ins.systemWindowInsetLeft,dp(14)+ins.systemWindowInsetTop,dp(12)+ins.systemWindowInsetRight,dp(10)+ins.systemWindowInsetBottom)
            ins
        }
        val header=row();val mark=label("◉",25f,fg,true).apply {gravity=Gravity.CENTER;background=shape(Color.rgb(22,74,158))}
        header.addView(mark,LinearLayout.LayoutParams(dp(46),dp(46)).apply {marginEnd=dp(11)})
        val titles=col();titles.addFull(label("Radio Rocky 35",25f,fg,true));titles.addFull(label("RADIOS DE URUGUAY",11f,muted));header.addView(titles)
        root.addFull(header,48,10)
        search=EditText(this).apply {hint="Buscar emisora…";setSingleLine(true);textSize=16f;setTextColor(fg);setHintTextColor(muted);background=shape(panel,12,line);setPadding(dp(12),0,dp(12),0);inputType=android.text.InputType.TYPE_CLASS_TEXT}
        root.addFull(search,46,8)
        region=Spinner(this).apply {background=shape(panel,12,line);setPadding(dp(10),0,dp(10),0)}
        val names=departments.map { when(it){""->"Todos los departamentos";"unknown"->"Sin departamento confirmado";else->it} }
        region.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,names).apply {setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
        root.addFull(region,46,8)
        val tabs=row();allTab=button("Todas");favTab=button("★ Favoritas");tabs.addView(allTab,LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(5)});tabs.addView(favTab,LinearLayout.LayoutParams(0,dp(44),1f));root.addFull(tabs,44,9)
        val summary=row();heading=label("Emisoras",17f,fg,true);count=label("",13f,muted);summary.addView(heading,LinearLayout.LayoutParams(0,-2,1f));summary.addView(count);root.addFull(summary,26,5)
        adapter=StationAdapter();list=ListView(this).apply {divider=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);dividerHeight=dp(6);adapter=this@MainActivity.adapter;cacheColorHint=Color.TRANSPARENT}
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val player=col().apply {background=shape(Color.rgb(32,46,66));setPadding(dp(10),dp(8),dp(10),dp(5))}
        val playing=row();toggle=button("▶",bg).apply{background=shape(blue);textSize=21f}
        playing.addView(toggle,LinearLayout.LayoutParams(dp(50),dp(50)).apply{marginEnd=dp(10)})
        val info=col();now=label("Elegí una emisora",16f,fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};status=label("Lista para escuchar",12f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};info.addFull(now);info.addFull(status);playing.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        favorite=button("☆",muted).apply{textSize=25f};playing.addView(favorite,LinearLayout.LayoutParams(dp(45),dp(46)));player.addFull(playing,54)
        val volRow=row();volRow.addView(ImageView(this).apply{setImageResource(R.drawable.ic_volume);imageTintList=ColorStateList.valueOf(muted);contentDescription="Volumen"},LinearLayout.LayoutParams(dp(22),dp(22)).apply{marginEnd=dp(4)});volume=SeekBar(this).apply{max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);progress=audio.getStreamVolume(AudioManager.STREAM_MUSIC);progressTintList=ColorStateList.valueOf(blue);thumbTintList=ColorStateList.valueOf(blue)}
        volRow.addView(volume,LinearLayout.LayoutParams(0,dp(40),1f));percent=label("",13f,muted);volRow.addView(percent,LinearLayout.LayoutParams(dp(42),-2).apply{marginEnd=dp(6)});gravityEnd(percent)
        copy=ImageButton(this).apply{setImageResource(R.drawable.ic_copy);imageTintList=ColorStateList.valueOf(fg);background=shape(panel);contentDescription="Copiar tema";setOnClickListener{copySong()}}
        volRow.addView(copy,LinearLayout.LayoutParams(dp(45),dp(38)));updateCopy();player.addFull(volRow,40);root.addFull(player)
        setContentView(root);root.requestApplyInsets()
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!changing)scheduleSearch()};override fun afterTextChanged(s:Editable?) {}})
        region.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(parent:AdapterView<*>?,v:View?,position:Int,id:Long){if(!changing)refresh()};override fun onNothingSelected(parent:AdapterView<*>?){}}
        allTab.setOnClickListener{mode(false)};favTab.setOnClickListener{mode(true)}
        toggle.setOnClickListener{if(selected.isNotEmpty())listen(selected) else if(rows.isNotEmpty())listen(Catalog.id(rows[0]))}
        favorite.setOnClickListener{if(selected.isNotEmpty())flip(selected)}
        volume.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(bar:SeekBar?,p:Int,fromUser:Boolean){if(fromUser)audio.setStreamVolume(AudioManager.STREAM_MUSIC,p,0);percent.text="${p*100/volume.max.coerceAtLeast(1)}%"}
            override fun onStartTrackingTouch(bar:SeekBar?){draggingVolume=true}
            override fun onStopTrackingTouch(bar:SeekBar?){draggingVolume=false;syncVolume()}
        })
        refresh();syncPlayback(RadioService.snapshot());syncVolume()
        if(selected.isEmpty()){
            val last=prefs.getString("last","")?:"";val st=if(last.isEmpty())emptyArray() else Catalog.station(last)
            if(st.isNotEmpty()){selected=last;now.text=st[0];message="Tocá ▶ para escuchar";showStatus("");updatePlayer();adapter.notifyDataSetChanged()}
        }
    }
    private fun mode(f:Boolean){handler.removeCallbacks(searchRefresh);onlyFavorites=f;changing=true;search.setText("");region.setSelection(0);changing=false;refresh();if(rows.isNotEmpty())list.setSelection(0)}
    private fun refresh(){
        if(!::adapter.isInitialized||detached)return
        rows=Catalog.filterIndices(search.text.toString(),departments.getOrElse(region.selectedItemPosition){""},onlyFavorites,if(onlyFavorites)favorites.toTypedArray() else emptyArray())
        down=Health.read(prefs)
        if(down.isNotEmpty()){
            val r=rows;val a=IntArray(r.size);var n=0
            for(i in r){val f=down[Catalog.id(i)]?:0;if(f==0)a[n++]=i}
            for(i in r){val sid=Catalog.id(i);val f=down[sid]?:0;if(f in 1 until Health.HIDE||(f>=Health.HIDE&&sid==selected))a[n++]=i}
            rows=a.copyOf(n)
        }
        heading.text=if(onlyFavorites)"Mis favoritas" else "Emisoras"
        count.text=if(onlyFavorites&&favorites.isEmpty())"Tocá ☆ en una radio" else "${rows.size} radios"
        allTab.background=if(!onlyFavorites)allOn else allOff
        favTab.background=if(onlyFavorites)favOn else favOff
        adapter.notifyDataSetChanged();updatePlayer()
    }
    private fun flip(id:String){if(!favorites.add(id))favorites.remove(id);prefs.edit().putString("favorites",JSONArray(favorites.toList()).toString()).apply(); refresh()}
    private fun playing()=phase=="playing"||phase=="loading"
    private fun listen(id:String){
        if(id==selected && playing()){startService(RadioService.pauseIntent(this));phase="paused"}
        else {selected=id;phase="loading";val station=Catalog.station(id);now.text=station.firstOrNull()?:"";status.text="Conectando…";startForegroundService(RadioService.playIntent(this,id))}
        updatePlayer();adapter.notifyDataSetChanged()
    }
    private fun updatePlayer(){
        if(!::now.isInitialized)return
        toggle.text=if(playing())"Ⅱ" else "▶"
        favorite.text=if(favorites.contains(selected))"★" else "☆"
        favorite.setTextColor(if(favorites.contains(selected))yellow else muted)
        favorite.isEnabled=selected.isNotEmpty()
    }
    private fun syncPlayback(snap:RadioService.Companion.Snapshot){
        if(snap.id.isEmpty())return
        val change=snap.id!=selected||phase!=snap.phase
        selected=snap.id;phase=snap.phase
        if(now.text!=snap.name)now.text=snap.name
        if(message!=snap.message){message=snap.message;lastRx=-1;showStatus("")}
        if(song!=snap.title){song=snap.title;showStatus(lastRate)}
        updateCopy()
        if(snap.phase=="playing"&&down.containsKey(selected)){down.remove(selected);refresh()}
        if(change){adapter.notifyDataSetChanged();updatePlayer()}
    }
    private fun showStatus(rate:String){
        val q=if(selected.isEmpty())"" else Catalog.qualityOf(selected)
        lastRate=rate
        val t=buildString{if(phase=="playing"&&song.isNotEmpty())append("♪ ").append(song) else {append(message.ifEmpty{"Lista para escuchar"});if(phase=="playing"&&q.isNotEmpty())append(" · ").append(q)};if(rate.isNotEmpty())append(" · ").append(rate)}
        if(status.text.toString()!=t)status.text=t
    }
    private fun updateRate(){
        if(!::status.isInitialized)return
        if(phase!="playing"){lastRx=-1;return}
        val rx=TrafficStats.getUidRxBytes(Process.myUid());val now=SystemClock.elapsedRealtime()
        if(rx<0)return
        if(lastRx<0){lastRx=rx;lastAt=now;return}
        if(now-lastAt<3000)return
        showStatus(String.format("%.1f kB/s",(rx-lastRx)*1000.0/1024/(now-lastAt)))
        lastRx=rx;lastAt=now
    }
    private fun gravityEnd(v:TextView){v.gravity=Gravity.END or Gravity.CENTER_VERTICAL}
    private fun updateCopy(){
        if(!::copy.isInitialized)return
        val on=phase=="playing"&&song.isNotEmpty()
        if(copy.isEnabled!=on){copy.isEnabled=on;copy.alpha=if(on)1f else .3f}
    }
    private fun copySong(){
        if(song.isEmpty())return
        RadioService.copyToClipboard(this,song)
    }
    private fun syncVolume(){
        if(!::volume.isInitialized||draggingVolume)return
        val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if(volume.max!=max)volume.max=max
        if(volume.progress!=level)volume.progress=level
        val text="${level*100/max.coerceAtLeast(1)}%"
        if(percent.text!=text)percent.text=text
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        val result=super.dispatchKeyEvent(event)
        if(event.keyCode==KeyEvent.KEYCODE_VOLUME_UP||event.keyCode==KeyEvent.KEYCODE_VOLUME_DOWN||event.keyCode==KeyEvent.KEYCODE_VOLUME_MUTE){
            handler.removeCallbacks(volumeSync);handler.postDelayed(volumeSync,120)
        }
        return result
    }
    override fun onStart(){
        super.onStart()
        if(detached){
            detached=false;refresh()
            list.adapter=adapter
            list.setSelectionFromTop(firstRow,firstOffset)
        }
        RadioService.observe(playbackObserver)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI,true,volumeObserver)
        audio.registerAudioDeviceCallback(audioDevices,handler)
        syncVolume();lastRx=-1;handler.post(rateTick);checkStations()
    }
    private fun checkStations(){
        val t=System.currentTimeMillis()
        if(t-prefs.getLong("checkedAt",0L)<12*3600_000L)return
        val cm=getSystemService(android.net.ConnectivityManager::class.java)
        if(cm.activeNetwork==null||cm.isActiveNetworkMetered)return
        prefs.edit().putLong("checkedAt",t).apply()
        val store=applicationContext.getSharedPreferences("radio",MODE_PRIVATE)
        Thread{
            val pool=java.util.concurrent.Executors.newFixedThreadPool(6)
            val dead=java.util.Collections.synchronizedSet(HashSet<String>())
            for(i in 0 until Catalog.size){val sid=Catalog.id(i);pool.execute{val st=Catalog.station(sid);if(st.size>1&&st.drop(1).none{alive(it)})dead.add(sid)}}
            pool.shutdown();pool.awaitTermination(10,java.util.concurrent.TimeUnit.MINUTES)
            if(dead.size<Catalog.size/3){
                val old=Health.read(store);val m=HashMap<String,Int>()
                for(sid in dead)m[sid]=(old[sid]?:0)+1
                Health.write(store,m);handler.post{if(!isDestroyed)refresh()}
            }
            else store.edit().putLong("checkedAt",0L).apply()
        }.start()
    }
    private fun alive(u:String):Boolean{
        var c:java.net.HttpURLConnection?=null
        return try{
            c=java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.connectTimeout=8000;c.readTimeout=8000;c.setRequestProperty("User-Agent","RadioRocky35")
            if(c.responseCode !in 200..299)return false
            val b=ByteArray(4096);var n=0;val inp=c.inputStream
            while(n<4096){val r=inp.read(b,n,4096-n);if(r<0)break;n+=r}
            n>=1024
        }catch(e:Exception){false}finally{try{c?.disconnect()}catch(_:Exception){}}
    }
    override fun onStop(){
        RadioService.removeObserver(playbackObserver)
        contentResolver.unregisterContentObserver(volumeObserver)
        audio.unregisterAudioDeviceCallback(audioDevices)
        handler.removeCallbacks(volumeSync);handler.removeCallbacks(searchRefresh);handler.removeCallbacks(rateTick)
        firstRow=list.firstVisiblePosition
        firstOffset=list.getChildAt(0)?.top?:0
        list.adapter=null
        rows=IntArray(0);detached=true
        super.onStop()
    }
    private inner class StationRow:LinearLayout(this@MainActivity){
        var id=""
        private val border=shape(panel,15,line)
        private val icon=button("▶",blue)
        private val labels=col()
        private val name=label("",15f,fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        private val department=label("",12f,muted)
        private val star=button("☆",muted).apply{textSize=25f}
        init{
            orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=border;setPadding(dp(8),dp(5),dp(5),dp(5))
            addView(icon,LayoutParams(dp(44),dp(46)).apply{marginEnd=dp(9)})
            labels.addFull(name);labels.addFull(department);addView(labels,LayoutParams(0,dp(52),1f))
            addView(star,LayoutParams(dp(45),dp(46)))
            icon.setOnClickListener{listen(id)};labels.setOnClickListener{listen(id)};star.setOnClickListener{flip(id)}
        }
        fun bind(index:Int){
            if(index !in 0 until Catalog.size)return
            id=Catalog.id(index);val nm=Catalog.name(index);name.text=nm
            val off=down.containsKey(id);department.text=if(off)"Sin señal · "+Catalog.quality(index) else Catalog.quality(index);department.setTextColor(if(off)alert else muted)
            border.setStroke(dp(1),if(id==selected)blue else line)
            icon.text=if(id==selected&&playing())"Ⅱ" else "▶"
            icon.contentDescription=(if(id==selected&&playing())"Pausar " else "Escuchar ")+nm
            val f=favorites.contains(id);star.text=if(f)"★" else "☆";star.setTextColor(if(f)yellow else muted)
            star.contentDescription=(if(f)"Quitar de favoritas: " else "Agregar a favoritas: ")+nm
        }
    }
    private inner class StationAdapter:BaseAdapter(){
        override fun getCount()=rows.size
        override fun getItem(position:Int)=rows.getOrElse(position){-1}
        override fun getItemId(position:Int)=rows.getOrElse(position){-1}.toLong()
        override fun hasStableIds()=true
        override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View{
            return (convertView as? StationRow ?: StationRow()).apply{bind(getItem(position))}
        }
    }
}
