'use strict';
const $=id=>document.getElementById(id),audio=$('audio'),nativePlayback=window.RockyAndroidPlayback;
let pendingNative=null;
let stations=[],visible=[],current=null,onlyFav=false,streamIndex=0,timer,attempt=0,phase='idle';
let fav=new Set();try{fav=new Set(JSON.parse(localStorage.getItem('rocky-fav')||'[]'))}catch{}
const norm=s=>(s||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase();
const departments=['Artigas','Canelones','Cerro Largo','Colonia','Durazno','Flores','Florida','Lavalleja','Maldonado','Montevideo','Paysandú','Río Negro','Rivera','Rocha','Salto','San José','Soriano','Tacuarembó','Treinta y Tres'];
const save=()=>localStorage.setItem('rocky-fav',JSON.stringify([...fav]));
function stars(btn,s){const active=!!s&&fav.has(s.id);btn.textContent=active?'★':'☆';btn.setAttribute('aria-pressed',String(active));btn.setAttribute('aria-label',active?'Quitar de favoritas':'Agregar a favoritas');btn.disabled=!s}
function flip(s){if(!s)return;fav.has(s.id)?fav.delete(s.id):fav.add(s.id);save();render()}
function render(){const q=norm($('search').value.trim()),d=$('region').value;visible=stations.filter(s=>(!onlyFav||fav.has(s.id))&&(!d||(d==='unknown'?!s.department:s.department===d))&&norm([s.name,s.department,s.tags].join(' ')).includes(q));
$('count').textContent=visible.length+' radios';$('heading').textContent=onlyFav?'Mis favoritas':'Emisoras';$('grid').replaceChildren();
for(const s of visible){const card=document.createElement('article');const selected=current?.id===s.id;const playing=selected&&['playing','loading'].includes(phase);card.className='station'+(selected?' current':'');const play=document.createElement('button');play.className='play';play.setAttribute('aria-label',(playing?'Pausar ':'Escuchar ')+s.name);const icon=document.createElement('span');icon.className='play-icon';icon.textContent=playing?'Ⅱ':'▶';const labels=document.createElement('span');labels.className='station-name';const name=document.createElement('strong');name.textContent=s.name;const meta=document.createElement('small');meta.textContent=s.department||'Departamento sin confirmar';labels.append(name,meta);play.append(icon,labels);play.onclick=()=>playing?pause():start(s);const star=document.createElement('button');star.className='star';stars(star,s);star.setAttribute('aria-label',(fav.has(s.id)?'Quitar de favoritas: ':'Agregar a favoritas: ')+s.name);star.onclick=()=>flip(s);card.append(play,star);$('grid').append(card)}
if(!visible.length){const msg=document.createElement('p');msg.className='empty';msg.textContent=onlyFav?'No hay favoritas con estos filtros.':'No hay emisoras con estos filtros.';$('grid').append(msg)}
stars($('player-fav'),current);$('toggle').textContent=['playing','loading'].includes(phase)?'Ⅱ':'▶';$('toggle').setAttribute('aria-label',['playing','loading'].includes(phase)?'Pausar':'Reproducir')}
function status(p,t){phase=p;$('status').textContent=t;render()}
function urls(){return [...new Set((current?.streams||[current?.url,current?.original]).filter(Boolean))]}
function start(s){current=s;streamIndex=0;$('now').textContent=s.name;if(nativePlayback){pendingNative={id:s.id,phase:'loading',until:Date.now()+1500};status('loading','Conectando…');nativePlayback.play(s.id);return}if('mediaSession'in navigator)navigator.mediaSession.metadata=new MediaMetadata({title:s.name,artist:'Radio Rocky 35'});load()}
function load(){clearTimeout(timer);const token=++attempt;const url=urls()[streamIndex];if(!url){status('error','No se pudo conectar');return}status('loading','Conectando…');audio.src=url;audio.play().catch(()=>{if(token===attempt&&phase==='loading')failed()});timer=setTimeout(()=>{if(token===attempt&&phase==='loading')failed()},16000)}
function failed(){if(!['loading','playing'].includes(phase))return;clearTimeout(timer);if(++streamIndex<urls().length){load();return}++attempt;status('error','No se pudo conectar');audio.pause()}
function pause(){if(nativePlayback){pendingNative={id:current?.id,phase:'paused',until:Date.now()+1500};status('paused','En pausa');nativePlayback.pause();return}++attempt;clearTimeout(timer);status('paused','En pausa');audio.pause()}
function syncNative(){
    if(!nativePlayback||!stations.length)return;
    try{
        const state=JSON.parse(nativePlayback.state());
        if(pendingNative){
            const matches=state.id===pendingNative.id&&(pendingNative.phase==='loading'?['loading','playing','error'].includes(state.phase):['paused','error'].includes(state.phase));
            if(!matches&&Date.now()<pendingNative.until)return;
            if(!matches&&pendingNative.phase==='loading'&&(!state.id||state.phase==='idle')){
                pendingNative=null;status('error','No se pudo iniciar la radio');return;
            }
            pendingNative=null;
        }
        const s=stations.find(s=>s.id===state.id);
        if(!s)return;
        const changed=current?.id!==s.id||phase!==state.phase||$('status').textContent!==state.message;
        current=s;$('now').textContent=s.name;
        if(changed)status(state.phase,state.message);
    }catch{}
}
if(nativePlayback){
    setInterval(()=>{if(!document.hidden)syncNative()},500);
    document.addEventListener('visibilitychange',()=>{if(!document.hidden)syncNative()});
}
audio.addEventListener('playing',()=>{if(phase!=='loading'&&phase!=='playing')return;clearTimeout(timer);status('playing','En vivo')});
audio.addEventListener('error',failed);
audio.addEventListener('waiting',()=>{if(phase==='playing')$('status').textContent='Reconectando…'});
const vol=$('volume'), nativeVolume=window.RockyAndroidVolume;
if(nativeVolume){
    audio.volume=1;
    const syncVolume=()=>{try{const min=nativeVolume.getMin(),max=nativeVolume.getMax(),level=nativeVolume.getVolume();vol.min=min;vol.max=max;vol.step=1;vol.value=level;$('vol-value').textContent=Math.round((level-min)*100/Math.max(1,max-min))+'%'}catch{}};
    vol.setAttribute('aria-label','Volumen multimedia del celular');
    vol.oninput=()=>{nativeVolume.setVolume(Number(vol.value));$('vol-value').textContent=Math.round((Number(vol.value)-Number(vol.min))*100/Math.max(1,Number(vol.max)-Number(vol.min)))+'%'};
    syncVolume();setInterval(()=>{if(!document.hidden&&!vol.matches(':active'))syncVolume()},400);
    document.addEventListener('visibilitychange',()=>{if(!document.hidden)syncVolume()});
}else{
    const setVolume=()=>{audio.volume=Number(vol.value);$('vol-value').textContent=Math.round(Number(vol.value)*100)+'%'};
    vol.oninput=setVolume;setVolume();
}
$('player-fav').onclick=()=>flip(current);$('toggle').onclick=()=>['playing','loading'].includes(phase)?pause():current?start(current):visible.length?start(visible[0]):null;
$('search').oninput=render;$('region').onchange=render;
function mode(v){onlyFav=v;$('search').value='';$('region').value='';for(const [id,active] of [['tab-all',!v],['tab-fav',v]]){$(id).classList.toggle('active',active);$(id).setAttribute('aria-pressed',String(active))}render();$('scroll').scrollTop=0}
$('tab-all').onclick=()=>mode(false);$('tab-fav').onclick=()=>mode(true);
fetch('stations.json').then(r=>{if(!r.ok)throw Error();return r.json()}).then(data=>{stations=data;for(const s of stations){for(const id of s.aliases||[])if(fav.delete(id))fav.add(s.id)}save();for(const d of departments.filter(d=>stations.some(s=>s.department===d))){const o=document.createElement('option');o.value=o.textContent=d;$('region').append(o)}if(stations.some(s=>!s.department)){const o=document.createElement('option');o.value='unknown';o.textContent='Sin departamento confirmado';$('region').append(o)}render();syncNative()}).catch(()=>{$('count').textContent='No se pudo cargar el catálogo'});
