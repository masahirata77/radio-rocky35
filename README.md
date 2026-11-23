# Radio Rocky 35

App de Android para escuchar radios de Uruguay. Liviana, sin publicidad, sin rastreadores y sin librerías de Google.

## Funciones

- 263 emisoras uruguayas, con búsqueda, filtro por departamento y favoritas.
- Orden por nombre, popularidad, últimas escuchadas o más escuchadas.
- Muestra el tema que suena (cuando la radio lo envía), la programación de algunas radios y la velocidad en kB/s.
- Reproducción en segundo plano con controles en la notificación: pausa, detener, copiar y buscar el tema.
- Reconexión automática.

## Arquitectura

- Kotlin puro con Android Views. Sin WebView ni código nativo.
- `minSdk` 26.
- El catálogo `app/src/main/java/com/rocky35/radio/Catalog.kt` se genera con `tools/gen-catalog.py` a partir de `stations.json`. No editarlo a mano.

## Compilar

```
./gradlew :app:assembleRelease
```

## Licencia

Copyright (C) 2026 Andrés (Rocky).

Este programa es software libre: se distribuye bajo la Licencia Pública General de GNU, versión 3 o posterior (GPL-3.0-or-later). Ver el archivo `LICENSE`.
