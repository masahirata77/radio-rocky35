# Kotlin + Rust 0.1.1 — comparación preliminar
Emulador Android 15 x86-64 Pixel 6 (4 vCPU, SwiftShader), cachy35, Azul FM 101.9. Mismo emulador y paquete (actualizado 0.1.0 -> 0.1.1), compilaciones x86-64 únicamente para las pruebas.
| Métrica | 0.1.0 | 0.1.1 |
|---|---:|---:|
| APK ARM64 (bytes) | 676405 | 585743 |
| PSS reproducción pantalla encendida (MiB) | 28.19 | 24.86 |
| PSS pantalla apagada (MiB) | 25.83 | 25.18 |
| CPU pantalla encendida (% un núcleo) | 2.13 | 0.14 |
| CPU pantalla apagada (% un núcleo) | 0.28 | 0.28 |
| Arranque mediano (ms) | 948 | 970 |

PSS: promedio de dos muestras por condición; CPU: intervalo de 7 s por condición; arranque: mediana de tres lanzamientos tras force-stop. Solo proceso de la app, no incluye decodificador/servicios compartidos ni host. No equivale a medición de batería. Sesión MediaSession PLAYING en ambas condiciones, no verificación auditiva. Favorito Carve Deportiva conservado al actualizar, búsqueda 'oceano' devuelve Océano FM, catálogo 113 radios. Tecla de volumen con reproducción: 6/15 (40%) a 7/15 (46%), verificado en dumpsys y UI. Prueba prolongada, rendimiento en dispositivo ARM64 real y todas las emisoras pendientes. No extrapolar CPU ni PSS del emulador al A15 como cifra exacta.
