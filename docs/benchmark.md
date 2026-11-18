# Comparación de las tres variantes
Probar en el mismo Samsung A15, misma emisora, misma red y volumen, ahorro de batería y brillo equivalentes. No reproducir simultáneamente. Repetir cada medición al menos 3 veces.

Paquetes:
- Estable: com.rocky35.radio
- Kotlin/Rust: com.rocky35.radio.rustnative
- Slint/Rust: com.rocky35.radio.slint

Métricas y comandos orientativos (ADB):
- APK: bytes reales del archivo.
- Arranque en frío: cerrar la aplicación de prueba y medir con `adb shell am start -W -n PAQUETE/com.rocky35.radio.MainActivity` (usar actividad efectiva de cada APK). Repetir y registrar TotalTime.
- RAM: `adb shell dumpsys meminfo PAQUETE`, sumar procesos secundarios de la aplicación si corresponde (incluido WebView).
- CPU: `adb shell top -b -n 1` durante reproducción; repetir varias veces y considerar procesos secundarios.
- Energía: registrar porcentaje/contador de carga y tiempo al inicio y fin de sesiones de 60 minutos, con pantalla apagada. Comparar con reposo del equipo. No usar carga USB durante la prueba, ni confundir el porcentaje con una medición precisa de energía.
- Verificar búsqueda, filtros, favoritos, cambio de emisora, alternativos, volumen, notificación, salida de la app, bloqueo de pantalla y desconexión de auriculares.
- Informar errores de conexión y cierres inesperados.

Sin mediciones en dispositivo todavía. Tamaño del APK no equivale a consumo de RAM.
