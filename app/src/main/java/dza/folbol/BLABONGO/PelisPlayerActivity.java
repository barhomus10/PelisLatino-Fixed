package dza.folbol.BLABONGO;

import androidx.media3.common.util.UnstableApi;

/**
 * Reproductor de video específico para PELIS (películas y series).
 *
 * Reutiliza toda la lógica de {@link PlayerActivity} (ExoPlayer + Media3,
 * controles personalizados, Picture-in-Picture, fallback a WebView y
 * resolución de streams) pero se declara como una Activity separada en el
 * AndroidManifest para poder tener su propia entrada/task independiente del
 * reproductor de canales.
 *
 * Recibe los mismos extras que PlayerActivity:
 * <ul>
 *     <li>{@code stream_url}   - m3u8 ya resuelto (reproducción directa)</li>
 *     <li>{@code stream_cookies} - cookies necesarias para el stream</li>
 *     <li>{@code stream_referer} - Referer HTTP del stream</li>
 *     <li>{@code stream_origin}  - Origin HTTP del stream</li>
 *     <li>{@code url_iframe_inicial} - embed para el modo WebView</li>
 * </ul>
 */
@UnstableApi
public class PelisPlayerActivity extends PlayerActivity {
    // Toda la funcionalidad se hereda de PlayerActivity.
}
