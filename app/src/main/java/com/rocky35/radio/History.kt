package com.rocky35.radio

import android.content.Context
import java.io.File
import java.util.Calendar
import java.util.Locale

// Historial de temas y programas: un archivo de texto por mes (hist/AAAA-MM.txt), una línea por cambio.
object History {
    data class Entry(val time:Long, val type:Char, val station:String, val text:String)
    private fun dir(c:Context)=File(c.filesDir,"hist").apply{mkdirs()}
    fun file(c:Context,y:Int,m:Int)=File(dir(c),String.format(Locale.US,"%04d-%02d.txt",y,m))
    private fun clean(s:String)=s.replace('\t',' ').replace('\n',' ').trim()
    fun append(c:Context,type:Char,station:String,text:String){
        val now=System.currentTimeMillis();val cal=Calendar.getInstance().apply{timeInMillis=now}
        file(c,cal.get(Calendar.YEAR),cal.get(Calendar.MONTH)+1).appendText("$now\t$type\t${clean(station)}\t${clean(text)}\n")
    }
    fun months(c:Context):List<Pair<Int,Int>> =
        (dir(c).listFiles()?:emptyArray()).mapNotNull{f->Regex("(\\d{4})-(\\d{2})\\.txt").matchEntire(f.name)?.let{it.groupValues[1].toInt() to it.groupValues[2].toInt()}}
            .sortedWith(compareByDescending<Pair<Int,Int>>{it.first}.thenByDescending{it.second})
    fun count(c:Context,y:Int,m:Int):Int{
        val f=file(c,y,m);if(!f.exists())return 0
        var n=0;f.inputStream().buffered().use{s->while(true){val b=s.read();if(b<0)break;if(b==10)n++}};return n
    }
    fun read(c:Context,y:Int,m:Int):List<Entry>{
        val f=file(c,y,m);if(!f.exists())return emptyList()
        return f.readLines().mapNotNull{l->val p=l.split('\t');if(p.size<4)null else p[0].toLongOrNull()?.let{Entry(it,p[1].firstOrNull()?:'s',p[2],p[3])}}.sortedByDescending{it.time}
    }
}
