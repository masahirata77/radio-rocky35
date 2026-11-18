# Radio Rocky 35 — rust-native

Rama experimental, derivada del snapshot estable 0.2.4 (4a322fc).
- Arquitectura: Kotlin + Android Views + Rust (JNI). No WebView en el APK.
- Identificador independiente: `com.rocky35.radio.rustnative`.
- Catálogo de 113 emisoras idéntico al estable; filtros y búsqueda implementados en Rust.
- Favoritos mediante SharedPreferences (cada variante guarda su propio conjunto).
- MediaPlayer, servicio de reproducción, MediaSession y volumen del sistema en Kotlin.
- Compilación ARM64 (Samsung A15), Android NDK 27.2.12479018, Rust aarch64-linux-android.
- Compilar: `./build-android.sh` (Gradle offline tras descargar sus dependencias).
- APK: `app/build/outputs/apk/optimized/app-optimized.apk`.
- Compilación y firma verificadas. Pruebas de uso real, pantalla apagada y rendimiento pendientes.
- No reemplaza ni modifica la versión estable.

## Historial de la versión estable (referencia)

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

## 0.1.1 — optimización
Catálogo e índice normalizado generados en compilación de Rust, sin JSON en runtime ni copia de assets; JNI con índices enteros y campos bajo demanda. Filas recicladas, lista liberada al ocultar UI y reconstruida al volver; observadores de estado multimedia y volumen en vez de sondeo periódico, búsqueda con 100 ms de debounce. Favoritos conservan formato de almacenamiento 0.1.0. Perfil Rust opt-level=z, LTO y panic=abort.
Comparación preliminar documentada en docs/benchmark-0.1.1.md.
