package com.rocky35.radio

import android.app.Activity
import android.os.*
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
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
    private lateinit var adapter: StationAdapter
    private val favorites = linkedSetOf<String>()
    private val departments = mutableListOf("", "unknown")
    private var onlyFavorites = false
    private var selected = ""
    private var phase = "idle"
    private var currentName = ""
    private var rows = JSONArray()
    private var allStations = JSONArray()
    private var changing = false
    private var draggingVolume = false
    private val prefs by lazy { getSharedPreferences("radio", MODE_PRIVATE) }
    private val poll = object : Runnable {
        override fun run() { sync(); handler.postDelayed(this, 500) }
    }
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
        allStations=JSONArray(RustCore.filter("","",false,"[]"))
        val ds=JSONArray(RustCore.departments())
        departments.clear();departments.add("");for(i in 0 until ds.length())departments.add(ds.getString(i))
        if((0 until allStations.length()).any{allStations.getJSONObject(it).isNull("department")})departments.add("unknown")
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
        adapter=StationAdapter();list=ListView(this).apply {divider=null;dividerHeight=dp(6);adapter=this@MainActivity.adapter;cacheColorHint=Color.TRANSPARENT}
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val player=col().apply {background=shape(Color.rgb(32,46,66));setPadding(dp(10),dp(8),dp(10),dp(5))}
        val playing=row();toggle=button("▶",bg).apply{background=shape(blue);textSize=21f}
        playing.addView(toggle,LinearLayout.LayoutParams(dp(50),dp(50)).apply{marginEnd=dp(10)})
        val info=col();now=label("Elegí una emisora",16f,fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};status=label("Lista para escuchar",12f,muted);info.addFull(now);info.addFull(status);playing.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        favorite=button("☆",muted).apply{textSize=25f};playing.addView(favorite,LinearLayout.LayoutParams(dp(45),dp(46)));player.addFull(playing,54)
        val volRow=row();volRow.addView(label("Volumen",13f,muted));volume=SeekBar(this).apply{max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);progress=audio.getStreamVolume(AudioManager.STREAM_MUSIC);progressTintList=ColorStateList.valueOf(blue);thumbTintList=ColorStateList.valueOf(blue)}
        volRow.addView(volume,LinearLayout.LayoutParams(0,dp(40),1f));percent=label("",13f,muted);volRow.addView(percent);player.addFull(volRow,40);root.addFull(player)
        setContentView(root);root.requestApplyInsets()
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!changing)refresh()};override fun afterTextChanged(s:Editable?) {}})
        region.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(parent:AdapterView<*>?,v:View?,position:Int,id:Long){if(!changing)refresh()};override fun onNothingSelected(parent:AdapterView<*>?){}}
        allTab.setOnClickListener{mode(false)};favTab.setOnClickListener{mode(true)}
        toggle.setOnClickListener{if(selected.isNotEmpty())listen(selected) else if(rows.length()>0)listen(rows.getJSONObject(0).getString("id"))}
        favorite.setOnClickListener{if(selected.isNotEmpty())flip(selected)}
        volume.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(bar:SeekBar?,p:Int,fromUser:Boolean){if(fromUser)audio.setStreamVolume(AudioManager.STREAM_MUSIC,p,0);percent.text="${p*100/volume.max.coerceAtLeast(1)}%"}
            override fun onStartTrackingTouch(bar:SeekBar?){draggingVolume=true}
            override fun onStopTrackingTouch(bar:SeekBar?){draggingVolume=false}
        })
        refresh();sync()
    }
    private fun mode(f:Boolean){onlyFavorites=f;changing=true;search.setText("");region.setSelection(0);changing=false;refresh();list.setSelection(0)}
    private fun refresh(){
        if(!::adapter.isInitialized)return
        rows=JSONArray(RustCore.filter(search.text.toString(),departments.getOrElse(region.selectedItemPosition){""},onlyFavorites,JSONArray(favorites.toList()).toString()))
        heading.text=if(onlyFavorites)"Mis favoritas" else "Emisoras"
        count.text="${rows.length()} radios"
        allTab.background=shape(if(!onlyFavorites)Color.rgb(48,72,107) else panel)
        favTab.background=shape(if(onlyFavorites)Color.rgb(48,72,107) else panel)
        adapter.notifyDataSetChanged();updatePlayer()
    }
    private fun flip(id:String){if(!favorites.add(id))favorites.remove(id);prefs.edit().putString("favorites",JSONArray(favorites.toList()).toString()).apply(); refresh()}
    private fun playing()=phase=="playing"||phase=="loading"
    private fun listen(id:String){
        if(id==selected && playing()){startService(RadioService.pauseIntent(this));phase="paused"}
        else {selected=id;phase="loading";startForegroundService(RadioService.playIntent(this,id))}
        updatePlayer();adapter.notifyDataSetChanged()
    }
    private fun updatePlayer(){
        if(!::now.isInitialized)return
        toggle.text=if(playing())"Ⅱ" else "▶"
        favorite.text=if(favorites.contains(selected))"★" else "☆"
        favorite.setTextColor(if(favorites.contains(selected))yellow else muted)
        favorite.isEnabled=selected.isNotEmpty()
    }
    private fun sync(){
        val snap=JSONObject(RadioService.snapshot())
        val id=snap.optString("id");val p=snap.optString("phase")
        if(id.isNotEmpty()){
            val change=id!=selected||phase!=p;selected=id;phase=p
            currentName=(0 until allStations.length()).map{allStations.getJSONObject(it)}.firstOrNull{it.optString("id")==id}?.optString("name") ?: ""
            now.text=currentName;status.text=snap.optString("message")
            if(change){adapter.notifyDataSetChanged();updatePlayer()}
        }
        if(!draggingVolume){volume.max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);volume.progress=audio.getStreamVolume(AudioManager.STREAM_MUSIC);percent.text="${volume.progress*100/volume.max.coerceAtLeast(1)}%"}
    }
    override fun onResume(){super.onResume();handler.removeCallbacks(poll);handler.post(poll)}
    override fun onPause(){handler.removeCallbacks(poll);super.onPause()}
    private inner class StationAdapter:BaseAdapter(){
        override fun getCount()=rows.length()
        override fun getItem(position:Int)=rows.getJSONObject(position)
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View{
            val s=getItem(position);val id=s.getString("id")
            val item=row().apply{background=shape(panel,15,if(id==selected)blue else line);setPadding(dp(8),dp(5),dp(5),dp(5))}
            val icon=button(if(id==selected&&playing())"Ⅱ" else "▶",blue)
            item.addView(icon,LinearLayout.LayoutParams(dp(44),dp(46)).apply{marginEnd=dp(9)})
            val labels=col();labels.addFull(label(s.getString("name"),15f,fg,true));labels.addFull(label(if(s.isNull("department"))"Departamento sin confirmar" else s.getString("department"),12f,muted))
            item.addView(labels,LinearLayout.LayoutParams(0,dp(52),1f));icon.setOnClickListener{listen(id)};labels.setOnClickListener{listen(id)}
            val star=button(if(favorites.contains(id))"★" else "☆",if(favorites.contains(id))yellow else muted).apply{textSize=25f;contentDescription=if(favorites.contains(id))"Quitar de favoritas" else "Agregar a favoritas"}
            star.setOnClickListener{flip(id)};item.addView(star,LinearLayout.LayoutParams(dp(45),dp(46)));return item
        }
    }
}
