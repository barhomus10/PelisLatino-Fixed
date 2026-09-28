# PelisLatinoHD — App Android Nativa (Java)

Conversión completa de https://pelislatinohd.pages.dev/ a app Android Java con **StreamResolver** integrado.

## Características
- **Home nativo**: Slider auto-play (top rated) + secciones horizontales (Películas, Series, Animes) leyendo `data/catalog.json` en vivo.
- **Detalle**: poster, backdrop, rating, géneros, sinopsis, reparto, relacionadas. Botón **Reproducir** usa `dza.folbol.BLABONGO.StreamResolver` para resolver el m3u8 real (playpaste → vsembed → cloudorchestra → petrichorparallax) y lo reproduce con **ExoPlayer (Media3 HLS)** con headers correctos.
- **Series**: lista de temporadas/episodios, cada episodio resuelve su propio embed.
- **Búsqueda**: Grid 3 columnas, filtrado instantáneo.
- **Favoritos / Historial**: estructura Room lista (DAO en `FavoritesDao.java`, solo falta UI).
- **Player**: `PlayerActivity` en landscape, con intercept de m3u8, retry, y manejo de token expirado.

## Estructura
```
app/src/main/java/
  dza.folbol.BLABONGO/StreamResolver.java  ← tu resolver parcheado (30s timeout, #bigPlay, petrichor whitelist)
  com.pelislatinohd.app/
    MainActivity.java
    DetailActivity.java
    PlayerActivity.java  ← usa StreamResolver + ExoPlayer
    SearchActivity.java
    data/CatalogRepository.java  ← fetch catalog.json
    model/ (Catalog, Movie, Episode)
    ui/ (MovieAdapter, SliderAdapter)
```

## Cómo abrir
1. Android Studio Hedgehog+ → Open → selecciona carpeta `PelisLatinoHD-App`
2. Sync Gradle (descarga OkHttp, Glide, Media3, Room)
3. Run en emulador o dispositivo (minSdk 21)

## Probar Tokyo Revengers
- Home → buscar "Tokyo Revengers" → Detalle → Reproducir
- Logcat: `StreamResolver: 🎯 [intercept] m3u8: https://petrichorparallax.space/.../master.m3u8?token=...`
- El token dura ~4h y es IP-bound. El player ya pone `Referer: https://cloudorchestranova.com/` automático.

## Personalizar
- Cambia `applicationId` en `app/build.gradle` (ahora `com.pelislatinohd.app`)
- Cambia `CATALOG_URL` en `CatalogRepository.java` si usas tu mirror
- Añade AdMob en `MainActivity` si quieres monetizar (ya está el slot `div-gpt` removido)

## Notas
- El catálogo se cachea en memoria (`cached`). Para offline, añade `Room` o `SharedPreferences`.
- `StreamResolver` hace carrera `OkHttp + WebView` con `invokeAny` y 30s timeout. OkHttp solo llega hasta `playerUrl`, WebView hace el click WASM y captura el m3u8.
- Si `embed69` devuelve `[]`, el resolver lo salta y usa `vsembed` (ya priorizado).

Hecho para ti — listo para compilar APK.
