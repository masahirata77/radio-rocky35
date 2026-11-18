# Radio Rocky 35
Reproductor local de radios uruguayas. Ejecutar `python3 -m http.server 8765 --bind 127.0.0.1` desde esta carpeta y abrir http://localhost:8765.
Catálogo: Radio Browser (https://api.radio-browser.info/), filtrado por UY y última comprobación positiva; no garantiza reproducción actual. Se excluyen HLS y URLs repetidas. El audio se recibe directamente de cada radio. Favoritos guardados en el navegador.

## Android
Aplicación WebView autónoma: incluye la web y el catálogo, sin servidor externo propio.
Paquete: com.rocky35.radio. Requiere Android 8+ e Internet.
Compilar: `./gradlew :app:assembleDebug`
APK: app/build/outputs/apk/debug/app-debug.apk.
HTTP permitido para las radios que aún no usan HTTPS. No se garantiza audio en segundo plano con la pantalla apagada. Icono adaptativo vectorial basado en el diseño aprobado.

## Catálogo 0.2
Revisión manual: 10 entradas agrupadas en 9 grupos de duplicados, con URLs alternativas y migración de favoritos. Radio Carve 850 y Carve Deportiva son distintas. Departamento sin confirmar se muestra explícitamente. Regenerar: `node tools/review-catalog.js tools/data/catalog-original.json`. Fuentes de contraste: https://radios-uruguayas.com/fm, https://andebu.org/asociados/, https://www.radiofm991.com/, https://radios.com.uy/dias-de-gloria/, https://emisoras.com.uy/396-cordialidad-fm.html, URSEC (Columbia FM 88.5 en Rivera, FM del Este en Florida). La revisión es preliminar; no certifica todos los departamentos ni la disponibilidad de los streams.

## Android 0.2.4
Reproducción en segundo plano mediante MediaPlayer, MediaSession y servicio en primer plano de tipo mediaPlayback, con notificación multimedia y wake lock durante la reproducción. La versión web conserva HTMLAudio. No se incorporó ahorro de datos ni cambio de bitrate. APK optimizado: ./gradlew :app:assembleOptimized. La prueba de reproducción con pantalla bloqueada queda pendiente en el dispositivo.
