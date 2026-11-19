package com.rocky35.radio

import android.content.SharedPreferences

// Fallos seguidos de cada radio en los chequeos. 1-2: "Sin señal" al final; HIDE o más: oculta hasta que vuelva.
object Health {
    const val HIDE = 3
    fun read(p:SharedPreferences):HashMap<String,Int>{
        val m=HashMap<String,Int>()
        for(e in (p.getString("fails","")?:"").split(';')){val k=e.substringBefore('=');val v=e.substringAfter('=',"").toIntOrNull();if(k.isNotEmpty()&&v!=null&&v>0)m[k]=v}
        return m
    }
    fun write(p:SharedPreferences,m:Map<String,Int>){p.edit().putString("fails",m.entries.joinToString(";"){"${it.key}=${it.value}"}).remove("down").apply()}
}
