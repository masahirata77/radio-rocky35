#!/usr/bin/env python3
"""Genera app/src/main/java/com/rocky35/radio/Catalog.kt desde stations.json."""
import json,unicodedata,os
os.chdir(os.path.join(os.path.dirname(__file__),'..'))
def norm(s): return ''.join(c for c in unicodedata.normalize('NFD',s) if not unicodedata.combining(c)).lower()
def k(s): return json.dumps(s,ensure_ascii=False).replace('$','\\$')
arr=json.load(open('stations.json'))
ids,names,depts,keys,urls,quals,pops=[],[],[],[],[],[],[]
dset=set();unknown=False
for s in arr:
    d=s.get('department') or ''
    if d: dset.add(d)
    else: unknown=True
    us=[]
    for u in (s.get('streams') or [])+[s.get('url'),s.get('original')]:
        if isinstance(u,str) and u.startswith(('http://','https://')) and u not in us: us.append(u)
    ids.append(s['id']);names.append(s['name']);depts.append(d)
    c=(s.get('codec') or '').upper().replace('UNKNOWN','');br=int(s.get('bitrate') or 0)
    quals.append(f'{c} · {br} kbps' if c and br else f'{br} kbps' if br else c or 'Calidad desconocida')
    pops.append(int(s.get('popularity') or 0))
    keys.append(norm(f"{s['name']} {d} {s.get('tags') or ''}"));urls.append(us)
dl=['']+sorted(dset,key=norm)+(['unknown'] if unknown else [])
L=lambda xs:'arrayOf('+','.join(k(x) for x in xs)+')'
out=f'''package com.rocky35.radio

import java.text.Normalizer

// Generado por tools/gen-catalog.py desde stations.json. No editar a mano.
object Catalog {{
    private val ids={L(ids)}
    private val names={L(names)}
    private val depts={L(depts)}
    private val keys={L(keys)}
    private val quals={L(quals)}
    private val pops=intArrayOf({','.join(map(str,pops))})
    private val urls=arrayOf({','.join(L(u) for u in urls)})
    private val marks=Regex("\\\\p{{Mn}}+")
    private fun normalize(s:String)=marks.replace(Normalizer.normalize(s,Normalizer.Form.NFD),"").lowercase()
    fun departments():Array<String> = {L(dl)}
    fun filterIndices(query:String,department:String,onlyFavorites:Boolean,favorites:Array<String>):IntArray{{
        val q=normalize(query.trim());val fav=if(onlyFavorites)favorites.toHashSet() else emptySet()
        val out=IntArray(ids.size);var n=0
        for(i in ids.indices)if((!onlyFavorites||ids[i] in fav)&&(department.isEmpty()||(department=="unknown"&&depts[i].isEmpty())||depts[i]==department)&&keys[i].contains(q))out[n++]=i
        return out.copyOf(n)
    }}
    private val byId by lazy{{HashMap<String,Int>(ids.size*2).apply{{ids.forEachIndexed{{i,s->put(s,i)}}}}}}
    val size get()=ids.size
    fun id(i:Int)=ids[i]
    fun name(i:Int)=names[i]
    fun quality(i:Int)=quals[i]
    fun popularity(i:Int)=pops[i]
    private val coll=java.text.Collator.getInstance(java.util.Locale("es")).apply{{strength=java.text.Collator.PRIMARY}}
    fun compareNames(a:Int,b:Int)=coll.compare(names[a],names[b])
    fun qualityOf(id:String)=byId[id]?.let{{quals[it]}}?:""
    fun row(index:Int):Array<String> = if(index in ids.indices) arrayOf(ids[index],names[index],depts[index]) else emptyArray()
    fun station(id:String):Array<String>{{val i=byId[id]?:-1;return if(i<0) emptyArray() else arrayOf(names[i])+urls[i]}}
}}
'''
open('app/src/main/java/com/rocky35/radio/Catalog.kt','w').write(out)
print(len(ids),'radios,',len(dl),'opciones de departamento,',sum(1 for d in depts if not d),'sin departamento')
