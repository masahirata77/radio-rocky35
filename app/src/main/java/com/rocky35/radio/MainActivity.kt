package com.rocky35.radio

import android.app.Activity
import android.os.*
import android.content.Context
import android.content.Intent
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
    private lateinit var searchBtn: ImageButton
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
    private val sortNames=arrayOf("Nombre","Popularidad","Últimas escuchadas","Más escuchadas")
    private var sortMode=0
    private var sortReverse=false
    private lateinit var sortBtn: Button
    private lateinit var dirBtn: Button
    private val plays=HashMap<String,LongArray>()
    private fun loadPlays(){try{val o=JSONObject(prefs.getString("plays","{}")?:"{}");for(k in o.keys()){val a=o.getJSONArray(k);plays[k]=longArrayOf(a.getLong(0),a.getLong(1))}}catch(_:Exception){}}
    private fun savePlay(id:String){
        val a=plays.getOrPut(id){longArrayOf(0,0)};a[0]++;a[1]=System.currentTimeMillis()
        val o=JSONObject();for((k,v) in plays)o.put(k,JSONArray().put(v[0]).put(v[1]))
        prefs.edit().putString("plays",o.toString()).apply()
    }
    // Copia congelada de las escuchas: el orden solo cambia al tocar los botones de orden.
    private var sortPlays:Map<String,LongArray>?=null
    private fun sortRows(r:IntArray):IntArray{
        val sp=sortPlays?:plays.mapValues{it.value.copyOf()}.also{sortPlays=it}
        val c:Comparator<Int> = when(sortMode){
            1->compareByDescending<Int>{Catalog.popularity(it)}
            2->compareByDescending<Int>{sp[Catalog.id(it)]?.get(1)?:0L}
            3->compareByDescending<Int>{sp[Catalog.id(it)]?.get(0)?:0L}
            else->Comparator{a,b->Catalog.compareNames(a,b)}
        }
        val tie=Comparator<Int>{a,b->Catalog.compareNames(a,b)}
        val full=c.then(tie)
        return r.sortedWith(if(sortReverse)full.reversed() else full).toIntArray()
    }
    private fun updateSortButtons(){sortBtn.text="⇅ "+sortNames[sortMode];dirBtn.text=if(sortReverse)"↑" else "↓"}
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
        val titles=col();titles.addFull(label("Radio Rocky 35",25f,fg,true));titles.addFull(label("RADIOS DE URUGUAY",11f,muted));header.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        val tools=col();toolsBtn=button("",fg).apply{background=shape(panel,12,line);textSize=15f;contentDescription="Mostrar u ocultar búsqueda y orden";setOnClickListener{setTools(tools.visibility!=View.VISIBLE,tools)}};header.addView(toolsBtn,LinearLayout.LayoutParams(dp(64),dp(42)))
        root.addFull(header,48,10)
        root.addFull(tools);setTools(prefs.getBoolean("toolsOpen",true),tools)
        search=EditText(this).apply {hint="Buscar emisora…";setSingleLine(true);textSize=16f;setTextColor(fg);setHintTextColor(muted);background=shape(panel,12,line);setPadding(dp(12),0,dp(12),0);inputType=android.text.InputType.TYPE_CLASS_TEXT}
        tools.addFull(search,46,8)
        region=Spinner(this).apply {background=shape(panel,12,line);setPadding(dp(10),0,dp(10),0)}
        val names=departments.map { when(it){""->"Todos los departamentos";"unknown"->"Sin departamento confirmado";else->it} }
        region.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,names).apply {setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
        tools.addFull(region,46,8)
        val tabs=row();allTab=button("Todas");favTab=button("★ Favoritas");tabs.addView(allTab,LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(5)});tabs.addView(favTab,LinearLayout.LayoutParams(0,dp(44),1f));root.addFull(tabs,44,6)
        sortMode=prefs.getInt("sortMode",0).coerceIn(0,3);sortReverse=prefs.getBoolean("sortReverse",false);loadPlays()
        val sorts=row();sortBtn=button("",fg).apply{background=shape(panel,12,line);contentDescription="Cambiar orden"};dirBtn=button("",fg).apply{background=shape(panel,12,line);textSize=18f;contentDescription="Invertir orden"}
        sorts.addView(sortBtn,LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(5)});sorts.addView(dirBtn,LinearLayout.LayoutParams(dp(56),dp(40)));tools.addFull(sorts,40,9);val copies=row();val saveB=button("💾 Guardar copia",fg).apply{textSize=13f;background=shape(panel,12,line);setOnClickListener{saveBackup()}};val loadB=button("📂 Restaurar copia",fg).apply{textSize=13f;background=shape(panel,12,line);setOnClickListener{restoreBackup()}};copies.addView(saveB,LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(5)});copies.addView(loadB,LinearLayout.LayoutParams(0,dp(40),1f));tools.addFull(copies,40,9)
        sortBtn.setOnClickListener{sortPlays=null;sortMode=(sortMode+1)%4;prefs.edit().putInt("sortMode",sortMode).apply();updateSortButtons();refresh();if(rows.isNotEmpty())list.setSelection(0)}
        dirBtn.setOnClickListener{sortPlays=null;sortReverse=!sortReverse;prefs.edit().putBoolean("sortReverse",sortReverse).apply();updateSortButtons();refresh();if(rows.isNotEmpty())list.setSelection(0)}
        updateSortButtons()
        val summary=row();heading=label("Emisoras",17f,fg,true);count=label("",13f,muted);summary.addView(heading,LinearLayout.LayoutParams(0,-2,1f));summary.addView(count);root.addFull(summary,26,5)
        adapter=StationAdapter();list=object:ListView(this@MainActivity){
            // La estrellita de cada radio tiene prioridad sobre la barra de desplazamiento rápido.
            override fun onInterceptTouchEvent(ev:MotionEvent):Boolean{
                if(ev.actionMasked==MotionEvent.ACTION_DOWN&&onStar(ev))return false
                return super.onInterceptTouchEvent(ev)
            }
            private fun onStar(ev:MotionEvent):Boolean{
                for(i in 0 until childCount){
                    val c=getChildAt(i);if(ev.y<c.top||ev.y>c.bottom)continue
                    val st=c.findViewWithTag<View>("star")?:return false
                    return ev.x>=c.left+st.left-dp(6)
                }
                return false
            }
        }.apply {divider=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);dividerHeight=dp(6);adapter=this@MainActivity.adapter;cacheColorHint=Color.TRANSPARENT;isFastScrollEnabled=true;scrollBarStyle=View.SCROLLBARS_OUTSIDE_INSET;setPadding(0,0,dp(14),0);clipToPadding=false}
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val player=col().apply {background=shape(Color.rgb(32,46,66));setPadding(dp(10),dp(8),dp(10),dp(5))}
        // Renglón 1: radio y lo que suena + estrellita.
        val playing=row()
        val info=col();now=label("Elegí una emisora",16f,fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};status=label("Lista para escuchar",12f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};info.addFull(now);info.addFull(status);playing.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        favorite=button("☆",muted).apply{textSize=25f};playing.addView(favorite,LinearLayout.LayoutParams(dp(45),dp(46)));player.addFull(playing,50,6)
        // Renglón 2: play a la izquierda, atrás y siguiente juntos; copiar, buscar y volumen a la derecha.
        val controls=row();toggle=button("▶",bg).apply{background=shape(blue);textSize=21f}
        val prevBtn=ImageButton(this).apply{setImageResource(R.drawable.ic_prev);imageTintList=ColorStateList.valueOf(fg);background=shape(panel);contentDescription="Radio anterior";setOnClickListener{skip(-1)}}
        val nextBtn=ImageButton(this).apply{setImageResource(R.drawable.ic_next);imageTintList=ColorStateList.valueOf(fg);background=shape(panel);contentDescription="Radio siguiente";setOnClickListener{skip(1)}}
        controls.addView(toggle,LinearLayout.LayoutParams(dp(54),dp(46)).apply{marginEnd=dp(14)})
        controls.addView(prevBtn,LinearLayout.LayoutParams(dp(46),dp(42)).apply{marginEnd=dp(3)})
        controls.addView(nextBtn,LinearLayout.LayoutParams(dp(46),dp(42)))
        controls.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        copy=ImageButton(this).apply{setImageResource(R.drawable.ic_copy);imageTintList=ColorStateList.valueOf(fg);background=shape(panel);contentDescription="Copiar";setOnClickListener{copySong()}}
        searchBtn=ImageButton(this).apply{setImageResource(R.drawable.ic_search);imageTintList=ColorStateList.valueOf(fg);background=shape(panel);contentDescription="Buscar en el navegador";setOnClickListener{searchSong()}}
        val volRow=row()
        volBtn=ImageButton(this).apply{setImageResource(R.drawable.ic_volume);imageTintList=ColorStateList.valueOf(fg);background=shape(panel);contentDescription="Mostrar volumen";setOnClickListener{setVolumeRow(volRow.visibility!=View.VISIBLE,volRow)}}
        controls.addView(copy,LinearLayout.LayoutParams(dp(44),dp(42)).apply{marginEnd=dp(6)});controls.addView(searchBtn,LinearLayout.LayoutParams(dp(44),dp(42)).apply{marginEnd=dp(6)});controls.addView(volBtn,LinearLayout.LayoutParams(dp(44),dp(42)))
        player.addFull(controls,46,4)
        // Renglón 3 (opcional): barra de volumen.
        volume=SeekBar(this).apply{max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);progress=audio.getStreamVolume(AudioManager.STREAM_MUSIC);progressTintList=ColorStateList.valueOf(blue);thumbTintList=ColorStateList.valueOf(blue)}
        volRow.addView(volume,LinearLayout.LayoutParams(0,dp(40),1f));percent=label("",13f,muted);volRow.addView(percent,LinearLayout.LayoutParams(dp(46),-2));gravityEnd(percent)
        updateCopy();player.addFull(volRow,40);setVolumeRow(prefs.getBoolean("volOpen",false),volRow);root.addFull(player)
        setContentView(root);root.requestApplyInsets()
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&!prefs.getBoolean("askedNotif",false)){prefs.edit().putBoolean("askedNotif",true).apply();requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),35)}
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
        rows=sortRows(rows)
        down=Health.read(prefs)
        if(down.isNotEmpty()){
            val r=rows;val a=IntArray(r.size);var n=0
            for(i in r){val f=down[Catalog.id(i)]?:0;if(f==0)a[n++]=i}
            for(i in r){val sid=Catalog.id(i);val f=down[sid]?:0;if(f in 1 until Health.HIDE||(f>=Health.HIDE&&sid==selected))a[n++]=i}
            rows=a.copyOf(n)
        }
        RadioService.setQueue(this,rows.map{Catalog.id(it)})
        heading.text=if(onlyFavorites)"Mis favoritas" else "Emisoras"
        count.text=if(onlyFavorites&&favorites.isEmpty())"Tocá ☆ en una radio" else "${rows.size} radios"
        allTab.background=if(!onlyFavorites)allOn else allOff
        favTab.background=if(onlyFavorites)favOn else favOff
        adapter.notifyDataSetChanged();updatePlayer()
    }
    private fun flip(id:String){if(!favorites.add(id))favorites.remove(id);prefs.edit().putString("favorites",JSONArray(favorites.toList()).toString()).apply()
        // Mantener la lista en el mismo lugar al marcar o desmarcar una favorita.
        val pos=list.firstVisiblePosition;val top=list.getChildAt(0)?.top?:0
        refresh();list.setSelectionFromTop(pos.coerceAtMost(maxOf(0,rows.size-1)),top)}
    private fun playing()=phase=="playing"||phase=="loading"
    private lateinit var toolsBtn:Button
    private lateinit var volBtn:ImageButton
    private fun setTools(open:Boolean,tools:View){tools.visibility=if(open)View.VISIBLE else View.GONE;toolsBtn.text=if(open)"🔍 ▴" else "🔍 ▾";prefs.edit().putBoolean("toolsOpen",open).apply()}
    private fun setVolumeRow(open:Boolean,row:View){row.visibility=if(open)View.VISIBLE else View.GONE;volBtn.background=shape(if(open)blue else panel);prefs.edit().putBoolean("volOpen",open).apply()}
    // Copia de seguridad de favoritas e historial en un archivo JSON (sirve para pasar a la versión de F-Droid).
    private fun saveBackup()=startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"radio-rocky35-favoritas.json"),41)
    private fun restoreBackup()=startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),42)
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req:Int,res:Int,data:Intent?){
        super.onActivityResult(req,res,data)
        val uri=data?.data;if(res!=RESULT_OK||uri==null)return
        try{
            if(req==41){
                val o=JSONObject().put("app","radio-rocky35").put("version",1).put("favorites",JSONArray(favorites.toList())).put("plays",JSONObject(prefs.getString("plays","{}")?:"{}"))
                contentResolver.openOutputStream(uri,"wt")!!.use{it.write(o.toString(1).toByteArray())}
                android.widget.Toast.makeText(this,"Copia guardada: ${favorites.size} favoritas",android.widget.Toast.LENGTH_SHORT).show()
            }else if(req==42){
                val o=JSONObject(contentResolver.openInputStream(uri)!!.use{String(it.readBytes())})
                val f=o.optJSONArray("favorites")?:JSONArray();var n=0
                for(i in 0 until f.length()){val id=f.getString(i);if(Catalog.station(id).isNotEmpty()&&favorites.add(id))n++}
                prefs.edit().putString("favorites",JSONArray(favorites.toList()).toString()).apply()
                o.optJSONObject("plays")?.let{pl->
                    for(k in pl.keys()){val a=pl.getJSONArray(k);val cur=plays.getOrPut(k){longArrayOf(0,0)};cur[0]=maxOf(cur[0],a.getLong(0));cur[1]=maxOf(cur[1],a.getLong(1))}
                    val out=JSONObject();for((k,v) in plays)out.put(k,JSONArray().put(v[0]).put(v[1]));prefs.edit().putString("plays",out.toString()).apply()
                }
                sortPlays=null;refresh()
                android.widget.Toast.makeText(this,"Restauradas: $n favoritas nuevas",android.widget.Toast.LENGTH_SHORT).show()
            }
        }catch(e:Exception){android.widget.Toast.makeText(this,"No se pudo leer el archivo",android.widget.Toast.LENGTH_SHORT).show()}
    }
    // ⏮ ⏭: recorren la lista que se está viendo (Todas o Favoritas, con su orden y filtros).
    private fun skip(d:Int){
        if(rows.isEmpty())return
        val ids=rows.map{Catalog.id(it)};val i=ids.indexOf(selected)
        val next=ids[if(i<0)(if(d>0)0 else ids.size-1) else Math.floorMod(i+d,ids.size)]
        if(next==selected&&playing())return
        listen(next)
        val pos=rows.indexOfFirst{Catalog.id(it)==next};if(pos>=0&&(pos<list.firstVisiblePosition||pos>list.lastVisiblePosition))list.smoothScrollToPosition(pos)
    }
    private fun listen(id:String){
        if(id==selected && playing()){startService(RadioService.pauseIntent(this));phase="paused"}
        else {selected=id;phase="loading";savePlay(id);val station=Catalog.station(id);now.text=station.firstOrNull()?:"";status.text="Conectando…";startForegroundService(RadioService.playIntent(this,id))}
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
        val t=buildString{if(phase=="playing"&&song.isNotEmpty()){if(!song.startsWith("🎙"))append("♪ ");append(song)} else {append(message.ifEmpty{"Lista para escuchar"});if(phase=="playing"&&q.isNotEmpty())append(" · ").append(q)};if(rate.isNotEmpty())append(" · ").append(rate)}
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
        val on=phase=="playing"&&song.isNotEmpty()&&song!="🎙 Selección musical"
        if(copy.isEnabled!=on){copy.isEnabled=on;copy.alpha=if(on)1f else .3f}
        if(::searchBtn.isInitialized&&searchBtn.isEnabled!=on){searchBtn.isEnabled=on;searchBtn.alpha=if(on)1f else .3f}
    }
    private fun songText():String?{
        if(song.isEmpty())return null
        return if(song.startsWith("🎙 "))"${now.text} · "+song.removePrefix("🎙 ") else song
    }
    private fun copySong(){songText()?.let{RadioService.copyToClipboard(this,it)}}
    private fun searchSong(){
        val q=(if(song.startsWith("🎙 "))"${now.text} "+song.removePrefix("🎙 ").substringBefore(" · ") else songText())?:return
        try{startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.google.com/search?q="+android.net.Uri.encode(q))).addCategory(Intent.CATEGORY_BROWSABLE))}catch(_:Exception){}
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
        private val star=button("☆",muted).apply{textSize=25f;tag="star"}
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
