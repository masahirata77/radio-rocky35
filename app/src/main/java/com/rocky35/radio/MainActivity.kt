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
    private val sortNames=arrayOf("Nombre","Popularidad","Últimas escuchadas","Más escuchadas","Calidad de audio")
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
            4->compareByDescending<Int>{qualityScore(Catalog.quality(it))}
            else->Comparator{a,b->Catalog.compareNames(a,b)}
        }
        val tie=Comparator<Int>{a,b->Catalog.compareNames(a,b)}
        val full=c.then(tie)
        return r.sortedWith(if(sortReverse)full.reversed() else full).toIntArray()
    }
    // Calidad: kbps del stream; AAC/Opus rinden más por kbps que MP3.
    private fun qualityScore(q:String):Int{
        val k=Regex("(\\d+)\\s*kbps").find(q)?.groupValues?.get(1)?.toIntOrNull()?:0
        val u=q.uppercase();return when{"OPUS" in u->k*2;"AAC" in u->k*3/2;else->k}
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
        val tools=col();toolsBtn=button("",fg).apply{setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search,0,0,0);compoundDrawableTintList=ColorStateList.valueOf(fg);compoundDrawablePadding=dp(2);setPadding(dp(8),0,dp(8),0);background=shape(panel,12,line);textSize=15f;contentDescription="Mostrar u ocultar búsqueda y orden";setOnClickListener{setTools(tools.visibility!=View.VISIBLE,tools)}};header.addView(toolsBtn,LinearLayout.LayoutParams(dp(64),dp(42)))
        root.addFull(header,48,10)
        root.addFull(tools);setTools(false,tools)
        search=EditText(this).apply {hint="Buscar emisora…";setSingleLine(true);textSize=16f;setTextColor(fg);setHintTextColor(muted);background=shape(panel,12,line);setPadding(dp(12),0,dp(12),0);inputType=android.text.InputType.TYPE_CLASS_TEXT}
        tools.addFull(search,46,8)
        region=Spinner(this).apply {background=shape(panel,12,line);setPadding(dp(10),0,dp(10),0)}
        val names=departments.map { when(it){""->"Todos los departamentos";"unknown"->"Sin departamento confirmado";else->it} }
        region.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,names).apply {setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
        tools.addFull(region,46,8)
        val tabs=row();allTab=button("Todas");favTab=button("★ Favoritas");histTab=button("Historial");tabs.addView(allTab,LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(5)});tabs.addView(favTab,LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(5)});tabs.addView(histTab,LinearLayout.LayoutParams(0,dp(44),1f));root.addFull(tabs,44,6)
        sortMode=prefs.getInt("sortMode",0).coerceIn(0,4);sortReverse=prefs.getBoolean("sortReverse",false);loadPlays()
        val sorts=row();sortBtn=button("",fg).apply{background=shape(panel,12,line);contentDescription="Cambiar orden"};dirBtn=button("",fg).apply{background=shape(panel,12,line);textSize=18f;contentDescription="Invertir orden"}
        sorts.addView(sortBtn,LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(5)});sorts.addView(dirBtn,LinearLayout.LayoutParams(dp(56),dp(40)));tools.addFull(sorts,40,9);val copies=row();val saveB=button("Guardar copia",fg).apply{setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_save,0,0,0);compoundDrawableTintList=ColorStateList.valueOf(fg);compoundDrawablePadding=dp(6);setPadding(dp(12),0,dp(12),0);textSize=13f;background=shape(panel,12,line);setOnClickListener{saveBackup()}};val loadB=button("Restaurar copia",fg).apply{setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_restore,0,0,0);compoundDrawableTintList=ColorStateList.valueOf(fg);compoundDrawablePadding=dp(6);setPadding(dp(12),0,dp(12),0);textSize=13f;background=shape(panel,12,line);setOnClickListener{restoreBackup()}};copies.addView(saveB,LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(5)});copies.addView(loadB,LinearLayout.LayoutParams(0,dp(40),1f));tools.addFull(copies,40,9)
        sortBtn.setOnClickListener{sortPlays=null;sortMode=(sortMode+1)%5;prefs.edit().putInt("sortMode",sortMode).apply();updateSortButtons();refresh();if(rows.isNotEmpty())list.setSelection(0)}
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
        histList=ListView(this).apply{divider=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);dividerHeight=dp(3);cacheColorHint=Color.TRANSPARENT;visibility=View.GONE;adapter=histAdapter
            setOnItemClickListener{_,_,pos,_->when(val r=hrows.getOrNull(pos)){is HRow.Head->{if(!histOpen.remove(r.key))histOpen.add(r.key);reloadHistory(false)};is HRow.Item->histAction(r.e);is HRow.Clear->confirmDelete("todo el historial"){true};else->{}}}
                setOnItemLongClickListener{_,_,pos,_->when(val r=hrows.getOrNull(pos)){is HRow.Head->{confirmDelete(r.label.substringBefore("  (")){e->r.pred(e)};true};is HRow.Day->{confirmDelete(r.label){e->r.pred(e)};true};is HRow.Item->{confirmDelete("este renglón"){e->e.time==r.e.time};true};else->false}}}
        root.addView(histList,LinearLayout.LayoutParams(-1,0,1f))
        histTab.setOnClickListener{if(showingHistory){tabPos.remove("h");histList.setSelection(0)} else {saveTabPos();showHistory(true)}}
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
        reopenTab()
    }
    // Al abrir: misma pestaña y mismo lugar que al cerrar; si hay una radio elegida y no se ve, la lista la muestra.
    private fun reopenTab(){
        try{for(e in (prefs.getString("tabPos","")?:"").split(',')){val a=e.split(':');if(a.size==3)tabPos[a[0]]=a[1].toInt() to a[2].toInt()}}catch(_:Exception){}
        when(prefs.getString("tab","a")){
            "f"->{onlyFavorites=true;refresh();restoreTabPos()}
            "h"->showHistory(true)
            else->restoreTabPos()
        }
        list.post{showSelected()}
    }
    private fun showSelected(){
        if(showingHistory||selected.isEmpty())return
        val pos=rows.indexOfFirst{Catalog.id(it)==selected};if(pos<0)return
        if(pos<list.firstVisiblePosition||pos>list.lastVisiblePosition)list.setSelectionFromTop(pos,list.height/3)
    }
    private fun persistTabs(){
        saveTabPos()
        prefs.edit().putString("tab",tabKey()).putString("tabPos",tabPos.entries.joinToString(","){"${it.key}:${it.value.first}:${it.value.second}"}).apply()
    }
    // Cada pestaña (Todas, Favoritas, Historial) recuerda hasta dónde la bajaste.
    // Tocar la pestaña en la que ya estás vuelve al principio.
    private val tabPos=HashMap<String,Pair<Int,Int>>()
    private fun tabKey()=if(showingHistory)"h" else if(onlyFavorites)"f" else "a"
    private fun tabList():ListView=if(showingHistory)histList else list
    private fun saveTabPos(){
        if(!::histList.isInitialized)return
        val lv=tabList();if((lv.adapter?.count?:0)==0)return
        tabPos[tabKey()]=lv.firstVisiblePosition to (lv.getChildAt(0)?.top?:0)
    }
    private fun restoreTabPos(){
        val lv=tabList();val n=lv.adapter?.count?:0;if(n==0)return
        val p=tabPos[tabKey()]
        if(p==null)lv.setSelection(0) else lv.setSelectionFromTop(p.first.coerceAtMost(n-1),p.second)
    }
    private fun mode(f:Boolean){
        val same=!showingHistory&&onlyFavorites==f
        saveTabPos()
        showHistory(false);handler.removeCallbacks(searchRefresh);onlyFavorites=f;changing=true;search.setText("");region.setSelection(0);changing=false;refresh()
        if(same){tabPos.remove(tabKey());if(rows.isNotEmpty())list.setSelection(0)} else restoreTabPos()
    }
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
        if(showingHistory){heading.text="Historial";count.text=if(hrows.isEmpty())"Todavía vacío" else ""}
        else{heading.text=if(onlyFavorites)"Mis favoritas" else "Emisoras";count.text=if(onlyFavorites&&favorites.isEmpty())"Tocá ☆ en una radio" else "${rows.size} radios"}
        allTab.background=if(!onlyFavorites&&!showingHistory)allOn else allOff
        favTab.background=if(onlyFavorites&&!showingHistory)favOn else favOff
        histTab.background=if(showingHistory)allOn else allOff
        adapter.notifyDataSetChanged();updatePlayer()
    }
    private fun flip(id:String){if(!favorites.add(id))favorites.remove(id);prefs.edit().putString("favorites",JSONArray(favorites.toList()).toString()).apply()
        // Mantener la lista en el mismo lugar al marcar o desmarcar una favorita.
        val pos=list.firstVisiblePosition;val top=list.getChildAt(0)?.top?:0
        refresh();list.setSelectionFromTop(pos.coerceAtMost(maxOf(0,rows.size-1)),top)}
    private fun playing()=phase=="playing"||phase=="loading"
    // ---------- Historial: año ▸ mes ▸ semana ▸ día, con grupos plegables ----------
    private lateinit var histTab:Button
    private lateinit var histList:ListView
    private var showingHistory=false
    private val histOpen=HashSet<String>()
    private var histOpenInit=false
    private val histEntries=HashMap<String,List<History.Entry>>()
    private val histCounts=HashMap<String,Int>()
    private sealed class HRow{
        class Head(val key:String,val label:String,val open:Boolean,val level:Int,val pred:(History.Entry)->Boolean):HRow()
        class Day(val label:String,val big:Boolean,val pred:(History.Entry)->Boolean):HRow()
        class Item(val e:History.Entry):HRow()
        object Clear:HRow()
    }
    private var hrows:List<HRow> = emptyList()
    private val histAdapter by lazy{HistAdapter()}
    private val monthNames=arrayOf("Enero","Febrero","Marzo","Abril","Mayo","Junio","Julio","Agosto","Septiembre","Octubre","Noviembre","Diciembre")
    private val dayNames=arrayOf("Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado")
    private fun cal(t:Long)=java.util.Calendar.getInstance().apply{timeInMillis=t}
    private fun weekStart(t:Long):java.util.Calendar{val c=cal(t);c.set(java.util.Calendar.HOUR_OF_DAY,0);c.set(java.util.Calendar.MINUTE,0);c.set(java.util.Calendar.SECOND,0);c.set(java.util.Calendar.MILLISECOND,0);while(c.get(java.util.Calendar.DAY_OF_WEEK)!=java.util.Calendar.MONDAY)c.add(java.util.Calendar.DAY_OF_MONTH,-1);return c}
    private fun weekKey(t:Long):String{val c=weekStart(t);return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.DAY_OF_YEAR)}"}
    private fun weekLabel(t:Long):String{
        if(weekKey(t)==weekKey(System.currentTimeMillis()))return "Esta semana"
        val a=weekStart(t);val b=(a.clone() as java.util.Calendar).apply{add(java.util.Calendar.DAY_OF_MONTH,6)}
        return "Semana del ${a.get(java.util.Calendar.DAY_OF_MONTH)}/${a.get(java.util.Calendar.MONTH)+1} al ${b.get(java.util.Calendar.DAY_OF_MONTH)}/${b.get(java.util.Calendar.MONTH)+1}"
    }
    private fun dayKey(t:Long):String{val c=cal(t);return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.DAY_OF_YEAR)}"}
    private fun dayLabel(t:Long):String{
        val now=System.currentTimeMillis();if(dayKey(t)==dayKey(now))return "Hoy"
        if(dayKey(t)==dayKey(now-86_400_000L))return "Ayer"
        val c=cal(t);return "${dayNames[c.get(java.util.Calendar.DAY_OF_WEEK)-1]} ${c.get(java.util.Calendar.DAY_OF_MONTH)}/${c.get(java.util.Calendar.MONTH)+1}"
    }
    private fun hhmm(t:Long):String{val c=cal(t);return String.format(java.util.Locale.US,"%02d:%02d",c.get(java.util.Calendar.HOUR_OF_DAY),c.get(java.util.Calendar.MINUTE))}
    private fun monthCount(y:Int,m:Int)=histCounts.getOrPut("$y-$m"){History.count(this,y,m)}
    private fun monthEntries(y:Int,m:Int)=histEntries.getOrPut("$y-$m"){History.read(this,y,m)}
    // Hoy y Ayer abiertos arriba; después las semanas del mes, los meses del año y los años anteriores (plegados).
    private fun buildHistory():List<HRow>{
        val now=System.currentTimeMillis();val c=cal(now);val cy=c.get(java.util.Calendar.YEAR);val cm=c.get(java.util.Calendar.MONTH)+1
        val today=dayKey(now);val yest=dayKey(now-86_400_000L)
        fun recent(e:History.Entry)=dayKey(e.time).let{it==today||it==yest}
        val out=ArrayList<HRow>();val months=History.months(this)
        val near=months.take(2).flatMap{monthEntries(it.first,it.second)}.sortedByDescending{it.time}
        for(k in listOf(today,yest)){
            val es=near.filter{dayKey(it.time)==k};if(es.isEmpty())continue
            out.add(HRow.Day(dayLabel(es[0].time),true){e->dayKey(e.time)==k});es.forEach{out.add(HRow.Item(it))}
        }
        fun weeks(es:List<History.Entry>,level:Int){
            for((wk,we) in es.groupBy{weekKey(it.time)}){
                val open=histOpen.contains("w$wk")
                out.add(HRow.Head("w$wk","${weekLabel(we.first().time)}  (${we.size})",open,level){e->weekKey(e.time)==wk&&!recent(e)})
                if(!open)continue
                var last="";for(e in we){val d=dayLabel(e.time);if(d!=last){last=d;val dk=dayKey(e.time);out.add(HRow.Day(d,false){x->dayKey(x.time)==dk})};out.add(HRow.Item(e))}
            }
        }
        fun month(y:Int,m:Int,level:Int){
            val es=monthEntries(y,m).filterNot(::recent);if(es.isEmpty())return
            val key="m$y-$m";val open=histOpen.contains(key)
            out.add(HRow.Head(key,"${monthNames[m-1]} $y  (${es.size})",open,level){e->cal(e.time).let{it.get(java.util.Calendar.YEAR)==y&&it.get(java.util.Calendar.MONTH)+1==m}&&!recent(e)})
            if(open)weeks(es,level+1)
        }
        weeks(monthEntries(cy,cm).filterNot(::recent),0)
        for((y,m) in months.filter{it.first==cy&&it.second!=cm})month(y,m,0)
        for(y in months.map{it.first}.filter{it<cy}.distinct()){
            val ms=months.filter{it.first==y};val key="y$y";val open=histOpen.contains(key)
            out.add(HRow.Head(key,"$y  (${ms.sumOf{monthCount(it.first,it.second)}})",open,0){e->cal(e.time).get(java.util.Calendar.YEAR)==y&&!recent(e)})
            if(open)for((_,m) in ms)month(y,m,1)
        }
        if(out.isNotEmpty())out.add(HRow.Clear)
        return out
    }
    private fun reloadHistory(fromDisk:Boolean){
        if(fromDisk){histEntries.clear();histCounts.clear()}
        val first=histList.firstVisiblePosition;val top=histList.getChildAt(0)?.top?:0
        hrows=buildHistory();histAdapter.notifyDataSetChanged();histList.setSelectionFromTop(first.coerceAtMost(maxOf(0,hrows.size-1)),top);refresh()
    }
    private fun confirmDelete(what:String,pred:(History.Entry)->Boolean){
        android.app.AlertDialog.Builder(this).setTitle("Borrar").setMessage("¿Borrar $what del historial? No se puede deshacer.")
            .setPositiveButton("Borrar"){_,_->Thread{try{History.delete(this,pred)}catch(_:Exception){};runOnUiThread{reloadHistory(true)}}.start()}
            .setNegativeButton("Cancelar",null).show()
    }
    private fun showHistory(on:Boolean){
        if(!::histList.isInitialized)return
        showingHistory=on
        list.visibility=if(on)View.GONE else View.VISIBLE
        histList.visibility=if(on)View.VISIBLE else View.GONE
        if(on){histEntries.clear();histCounts.clear();hrows=buildHistory();histAdapter.notifyDataSetChanged();restoreTabPos()}
        refresh()
    }
    private fun histAction(e:History.Entry){
        val copyText=if(e.type=='p')"${e.station} · ${e.text}" else e.text
        val q=if(e.type=='p')"${e.station} ${e.text.substringBefore(" · ")}" else e.text
        android.app.AlertDialog.Builder(this).setTitle(e.text).setItems(arrayOf("Copiar","Buscar en el navegador","Borrar")){_,w->
            when(w){
                0->RadioService.copyToClipboard(this,copyText)
                1->try{startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.google.com/search?q="+android.net.Uri.encode(q))).addCategory(Intent.CATEGORY_BROWSABLE))}catch(_:Exception){}
                else->confirmDelete("este renglón"){x->x.time==e.time}
            }
        }.show()
    }
    private inner class HistAdapter:BaseAdapter(){
        override fun getCount()=hrows.size
        override fun getItem(p:Int):Any=hrows[p]
        override fun getItemId(p:Int)=p.toLong()
        override fun getViewTypeCount()=4
        override fun getItemViewType(p:Int)=when(hrows[p]){is HRow.Head->0;is HRow.Day->1;is HRow.Item->2;HRow.Clear->3}
        override fun getView(p:Int,cv:View?,parent:ViewGroup?):View=when(val r=hrows[p]){
            is HRow.Head->{
                val t=(cv as? TextView)?:label("",15f,fg,true).apply{background=shape(panel,10)}
                t.text=(if(r.open)"▾  " else "▸  ")+r.label
                t.textSize=if(r.level==0)16f else 14f
                t.setPadding(dp(12+r.level*16),dp(11),dp(12),dp(11));t
            }
            is HRow.Day->{
                val t=(cv as? TextView)?:label("",12f,muted,true)
                t.text=r.label
                if(r.big){t.textSize=16f;t.setTextColor(fg);t.setPadding(dp(8),dp(12),dp(8),dp(4))}else{t.textSize=12f;t.setTextColor(muted);t.setPadding(dp(56),dp(10),dp(8),dp(2))}
                t
            }
            is HRow.Item->{
                val v=(cv as? LinearLayout)?:row().apply{
                    setPadding(dp(8),dp(6),dp(8),dp(6))
                    addView(label("",13f,muted).apply{tag="h"},LinearLayout.LayoutParams(dp(48),-2))
                    val c=col();c.addView(label("",15f,fg).apply{tag="t";maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END});c.addView(label("",12f,muted).apply{tag="s";maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});addView(c,LinearLayout.LayoutParams(0,-2,1f))
                }
                v.findViewWithTag<TextView>("h").text=hhmm(r.e.time)
                v.findViewWithTag<TextView>("t").text=(if(r.e.type=='p')"🎙 " else "♪ ")+r.e.text
                v.findViewWithTag<TextView>("s").text=r.e.station
                v
            }
            HRow.Clear->{
                val t=(cv as? TextView)?:label("Borrar todo el historial",14f,Color.rgb(255,150,150),true).apply{gravity=Gravity.CENTER;background=shape(panel,10,line)}
                t.setPadding(dp(12),dp(12),dp(12),dp(12));t
            }
        }
    }
    private lateinit var toolsBtn:Button
    private lateinit var volBtn:ImageButton
    private fun setTools(open:Boolean,tools:View){tools.visibility=if(open)View.VISIBLE else View.GONE;toolsBtn.text=if(open)"▴" else "▾";prefs.edit().putBoolean("toolsOpen",open).apply()}
    private fun setVolumeRow(open:Boolean,row:View){row.visibility=if(open)View.VISIBLE else View.GONE;volBtn.background=shape(if(open)blue else panel);prefs.edit().putBoolean("volOpen",open).apply()}
    // Copia de seguridad de favoritas e historial en un archivo JSON (sirve para pasar a la versión de F-Droid).
    private fun backupJson()=JSONObject().put("app","radio-rocky35").put("version",1).put("favorites",JSONArray(favorites.toList())).put("plays",JSONObject(prefs.getString("plays","{}")?:"{}")).toString()
    // Guardar: copia al portapapeles y archivo en Descargas, sin pantallas intermedias.
    private fun saveBackup(){
        val text=backupJson();RadioService.copyToClipboard(this,text)
        val fname="RadioRocky35_fav_"+java.text.SimpleDateFormat("yyMMdd-HHmm",java.util.Locale.US).format(java.util.Date())+".json"
        var file=false
        try{
            if(Build.VERSION.SDK_INT>=29){
                val v=android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,fname);put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json");put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS)}
                contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?.let{u->contentResolver.openOutputStream(u)?.use{it.write(text.toByteArray())};file=true}
            }
        }catch(_:Exception){}
        android.app.AlertDialog.Builder(this).setTitle("Copia guardada")
            .setMessage("${favorites.size} favoritas copiadas al portapapeles"+(if(file)" y guardadas en Descargas ($fname)." else ".")+"\n\nAbrí la otra app y tocá «Restaurar copia».")
            .setPositiveButton("OK",null).show()
    }
    // Restaurar: primero del portapapeles; si no hay copia ahí, se elige el archivo.
    private fun restoreBackup(){
        val clip=try{getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()}catch(_:Exception){null}
        if(clip!=null&&clip.contains("\"radio-rocky35\"")&&importBackup(clip))return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),42)
    }
    private fun importBackup(text:String):Boolean{
        return try{
            val o=JSONObject(text)
            val f=o.optJSONArray("favorites")?:JSONArray();var n=0
            for(i in 0 until f.length()){val id=f.getString(i);if(Catalog.station(id).isNotEmpty()&&favorites.add(id))n++}
            prefs.edit().putString("favorites",JSONArray(favorites.toList()).toString()).apply()
            o.optJSONObject("plays")?.let{pl->
                for(k in pl.keys()){val a=pl.getJSONArray(k);val cur=plays.getOrPut(k){longArrayOf(0,0)};cur[0]=maxOf(cur[0],a.getLong(0));cur[1]=maxOf(cur[1],a.getLong(1))}
                val out=JSONObject();for((k,v) in plays)out.put(k,JSONArray().put(v[0]).put(v[1]));prefs.edit().putString("plays",out.toString()).apply()
            }
            sortPlays=null;refresh()
            android.app.AlertDialog.Builder(this).setTitle("Copia restaurada").setMessage("$n favoritas nuevas. Ahora tenés ${favorites.size} favoritas.").setPositiveButton("OK",null).show()
            true
        }catch(e:Exception){false}
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req:Int,res:Int,data:Intent?){
        super.onActivityResult(req,res,data)
        val uri=data?.data;if(res!=RESULT_OK||uri==null||req!=42)return
        val ok=try{importBackup(contentResolver.openInputStream(uri)!!.use{String(it.readBytes())})}catch(_:Exception){false}
        if(!ok)android.widget.Toast.makeText(this,"No se pudo leer el archivo",android.widget.Toast.LENGTH_SHORT).show()
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
        if(song!=snap.title){song=snap.title;showStatus(lastRate);if(showingHistory)handler.postDelayed({if(showingHistory)reloadHistory(true)},800)}
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
        val rx=RadioService.rxBytes(Process.myUid());val now=SystemClock.elapsedRealtime()
        if(rx<0)return
        if(lastRx<0||rx<lastRx){lastRx=rx;lastAt=now;return}
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
        persistTabs()
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
