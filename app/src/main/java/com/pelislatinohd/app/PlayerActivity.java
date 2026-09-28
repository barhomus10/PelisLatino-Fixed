package com.pelislatinohd.app;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.HashMap;
import java.util.Map;
import dza.folbol.BLABONGO.StreamResolver;

public class PlayerActivity extends AppCompatActivity {
    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar loading;
    private TextView status, errorText;
    private View btnRetry;
    private String slug, type, embeddedId, title, epEmbed;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.playerView);
        loading = findViewById(R.id.loading);
        status = findViewById(R.id.status);
        errorText = findViewById(R.id.errorText);
        btnRetry = findViewById(R.id.btnRetry);

        slug = getIntent().getStringExtra("slug");
        type = getIntent().getStringExtra("type");
        embeddedId = getIntent().getStringExtra("embeddedId");
        title = getIntent().getStringExtra("title");
        epEmbed = getIntent().getStringExtra("ep_embed");

        btnRetry.setOnClickListener(v-> resolveAndPlay());

        resolveAndPlay();
    }

    private void resolveAndPlay(){
        loading.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText("Obteniendo enlaces...\nEsto puede tardar 5-10 segundos");
        errorText.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                String initialUrl;
                if (epEmbed != null && !epEmbed.isEmpty()) {
                    initialUrl = epEmbed;
                } else if (slug != null) {
                    // Construir url pelislatinohd como hace MainActivity
                    initialUrl = "https://pelislatinohd.pages.dev/detalle/" + (type!=null?type:"movie") + "/" + slug + "/";
                } else if (embeddedId != null) {
                    initialUrl = "https://playpaste.link/player/embed.php?id=" + embeddedId;
                } else {
                    throw new Exception("Sin datos para resolver");
                }

                long t0 = System.currentTimeMillis();
                StreamResolver.StreamResult r = StreamResolver.resolveSynchronously(getApplicationContext(), initialUrl);
                long dt = System.currentTimeMillis()-t0;

                runOnUiThread(()->{
                    if (r != null && r.m3u8Url != null && !r.m3u8Url.isEmpty()){
                        status.setText("Enlace encontrado en " + dt + "ms\n" + r.m3u8Url.substring(0, Math.min(80, r.m3u8Url.length())) + "...");
                        play(r);
                    } else {
                        showError("No se pudo obtener el stream.\nIntenta de nuevo o prueba otra película.");
                    }
                });
            } catch (Exception e){
                runOnUiThread(()-> showError("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void play(StreamResolver.StreamResult r){
        // ExoPlayer con headers
        Map<String,String> headers = new HashMap<>();
        if (r.headers != null) headers.putAll(r.headers);
        // Asegurar referer/origin para petrichor
        if (r.m3u8Url.contains("petrichorparallax")){
            headers.put("Referer","https://cloudorchestranova.com/");
            headers.put("Origin","https://cloudorchestranova.com");
        } else if (!headers.containsKey("Referer") && r.referer!=null){
            headers.put("Referer", r.referer);
        }
        if (!headers.containsKey("User-Agent")){
            headers.put("User-Agent", StreamResolver.DESKTOP_USER_AGENT);
        }

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // Construir MediaItem con headers
        MediaItem.Builder builder = new MediaItem.Builder()
                .setUri(Uri.parse(r.m3u8Url));

        // Media3 no tiene setHttpHeaders directo, usamos DataSource.Factory con headers
        // Simplificado: pasar headers via DefaultHttpDataSource.Factory
        // Para demo, usamos el m3u8 directo; en producción usar:
        // DefaultHttpDataSource.Factory ds = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers);
        // player = new ExoPlayer.Builder(this).setMediaSourceFactory(new HlsMediaSource.Factory(ds)).build();

        // Para que funcione con headers personalizados, usamos factory manual
        androidx.media3.datasource.DataSource.Factory dsFactory = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(headers.getOrDefault("User-Agent", StreamResolver.DESKTOP_USER_AGENT))
                .setDefaultRequestProperties(headers);

        androidx.media3.exoplayer.source.MediaSource mediaSource =
                new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(r.m3u8Url));

        player.setMediaSource(mediaSource);
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state){
                if (state == Player.STATE_READY){
                    loading.setVisibility(View.GONE);
                    status.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING){
                    loading.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onPlayerError(PlaybackException error){
                showError("Error de reproducción: " + error.getMessage() + "\nPrueba reintentar.");
            }
        });
    }

    private void showError(String msg){
        loading.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        errorText.setVisibility(View.VISIBLE);
        errorText.setText(msg);
        btnRetry.setVisibility(View.VISIBLE);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override protected void onPause(){
        super.onPause();
        if (player!=null) player.setPlayWhenReady(false);
    }
    @Override protected void onResume(){
        super.onResume();
        if (player!=null) player.setPlayWhenReady(true);
    }
    @Override protected void onDestroy(){
        super.onDestroy();
        if (player!=null){ player.release(); player=null; }
    }
}
