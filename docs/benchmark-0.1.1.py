import subprocess,time,re,json,statistics,os,xml.etree.ElementTree as ET
ADB=['adb','-s','emulator-5556']
PKG='com.rocky35.radio.rustnative'
act=PKG+'/com.rocky35.radio.MainActivity'
def run(*args):
    p=subprocess.run(ADB+list(args),capture_output=True,text=True,timeout=35)
    return p.stdout.strip()
def sh(*args):return run('shell',*args)
def wake():
    sh('input','keyevent','224');sh('wm','dismiss-keyguard');time.sleep(.5)
def launch():
    s=sh('am','start','-W','-n',act);m=re.search(r'TotalTime:\s*(\d+)',s)
    if not m:raise RuntimeError(s)
    return int(m[1])
def playing():
    s=sh('dumpsys','media_session')
    m=re.search(r'RadioRocky35 '+re.escape(PKG)+r'/RadioRocky35/[^\n]+\n(.*?)(?=\n    \S|\Z)',s,re.S)
    return bool(m and 'state=PLAYING(3)' in m.group(1) and 'Azul FM 101.9' in m.group(1))
def pid():return sh('pidof',PKG).split()[0]
def tick(p):
    a=sh('cat',f'/proc/{p}/stat').rsplit(')',1)[1].split()
    return int(a[11])+int(a[12])
def pss(p):
    return int(re.search(r'TOTAL PSS:\s*(\d+)',sh('dumpsys','meminfo',p)).group(1))
def measure():
    p=pid(); m1=pss(p);t=time.monotonic();a=tick(p);time.sleep(7);b=tick(p);dt=time.monotonic()-t;m2=pss(p)
    return {'pss_kib':(m1+m2)/2,'samples_kib':[m1,m2],'cpu_one_core_pct':round((b-a)/dt,2),'playing_after':playing()}
def nodes():
    sh('uiautomator','dump','/data/local/tmp/rocky-opt.xml')
    data=sh('cat','/data/local/tmp/rocky-opt.xml')
    return list(ET.fromstring(data).iter('node'))
def find(n,text=None,desc=None):
    return next((x for x in n if (text is not None and x.get('text')==text) or (desc is not None and x.get('content-desc')==desc)),None)
def tap(n):
    if n is None:raise RuntimeError('UI element not found')
    a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')))
    sh('input','tap',str((a+c)//2),str((b+d)//2));time.sleep(.4)
def wait_play():
    for i in range(12):
        if playing():return True
        time.sleep(1)
    return False
base=os.path.expanduser('~/.cache/radio-rocky35')
results=[]
while sh('getprop','sys.boot_completed')!='1':time.sleep(2)
wake()
for label,path in [('0.1.0',base+'/benchmark-x86/rust-native/app/build/outputs/apk/optimized/app-optimized.apk'),('0.1.1',base+'/benchmark-opt-x86/app/build/outputs/apk/optimized/app-optimized.apk')]:
    sh('am','force-stop',PKG)
    out=run('install','-r',path);print('INSTALL',label,out,flush=True)
    if 'Success' not in out:break
    launch();time.sleep(2)
    times=[]
    for i in range(3):
        sh('am','force-stop',PKG);time.sleep(.5);times.append(launch());time.sleep(1.2)
    time.sleep(2)
    n=nodes();assert find(n,'113 radios') is not None,'catalog missing'
    tap(find(n,'Azul FM 101.9'))
    assert wait_play(),'playback did not start'
    time.sleep(2)
    print('PLAYING',label,times,flush=True)
    on=measure();sh('input','keyevent','223');time.sleep(3)
    off=measure();off['screen_asleep']='mWakefulness=Asleep' in sh('dumpsys','power')
    row={'version':label,'startup_ms':times,'startup_median_ms':statistics.median(times),'screen_on':on,'screen_off':off}
    results.append(row);print('RESULT',json.dumps(row),flush=True)
    with open('/tmp/rocky-opt-results.json','w') as f:json.dump(results,f,indent=2)
    wake();time.sleep(1)
    # Stop audio, test migration of favorites and filters in upgrade.
    if label=='0.1.0':
        n=nodes();tap(find(n,'☆',None));print('OLD FAVORITE SET',flush=True)
    else:
        n=nodes()
        tap(find(n,'★ Favoritas'))
        n=nodes(); print('FAVORITES',[(x.get('text'),x.get('content-desc')) for x in n if 'radios' in (x.get('text')or'') or 'Carve' in (x.get('text')or'')],flush=True)
        tap(find(n,'Todas'))
        n=nodes()
        search=next(x for x in n if x.get('class')=='android.widget.EditText')
        tap(search);sh('input','text','oceano');time.sleep(.5);sh('input','keyevent','4');time.sleep(.6)
        n=nodes();print('SEARCH',[(x.get('text'),x.get('content-desc')) for x in n if 'radios' in (x.get('text')or'') or 'Océano' in (x.get('text')or'')],flush=True)
        sh('input','keyevent','24');time.sleep(.6);n=nodes()
        print('VOLUME',[(x.get('text'),x.get('bounds')) for x in n if '%' in (x.get('text')or'')],flush=True)
        print('PLAYING AFTER RETURN',playing(),flush=True)
    sh('am','force-stop',PKG)
print('FINAL',json.dumps(results),flush=True)
