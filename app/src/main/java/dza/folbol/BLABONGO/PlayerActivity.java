package dza.folbol.BLABONGO;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.CookieManager;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@UnstableApi
public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView playerView;
    private WebView webViewFallback;
    private String urlIframeInicial;
    private View fallbackCustomView;
    private WebChromeClient.CustomViewCallback fallbackCustomCallback;

    // Stream directo recibido del detalle (sin pasar por el resolver/WebView)
    private String streamDirecto;
    private String streamCookies;
    private String streamReferer;
    private String streamOrigin;

    private DefaultHttpDataSource.Factory dataSourceFactory;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executorService;

    private TextView tvDebugLog;
    private ScrollView scrollDebugLog;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private boolean streamReady = false;
    private boolean isResolving = false;
    private int errorRefreshCount = 0;
    private static final int MAX_REFRESH_RETRIES = 5;
    private long lastRefreshTime = 0;
    private static final long MIN_REFRESH_INTERVAL_MS = 2000;

    // --- Controles personalizados ---
    private View controlsOverlay;
    private ImageButton btnPlayPause, btnRewind, btnForward, btnVolume, btnRefresh, btnPip;
    private SeekBar seekBar;
    private boolean isSeeking = false;
    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private Runnable seekRunnable;

    // --- PIP ---
    private boolean isInPipMode = false;
    private boolean entrandoEnPip = false;   // true entre enterPictureInPictureMode() y onPictureInPictureModeChanged()
    private boolean cerrandoDesdePip = false;
    private BroadcastReceiver pipReceiver;

    private static final String ACTION_PIP_CONTROL = "dza.folbol.BLABONGO.PIP_CONTROL";
    private static final String EXTRA_PIP_ACTION = "pip_action";
    private static final int PIP_ACTION_PLAY_PAUSE = 1;
    private static final int PIP_ACTION_CLOSE = 2;

    // Auto-ocultar controles
    private static final long CONTROLS_TIMEOUT = 4000; // 4 segundos
    private final Runnable hideControlsRunnable = () -> {
        if (!isInPipMode && controlsOverlay != null) {
            controlsOverlay.setVisibility(View.GONE);
            stopSeekUpdate();
        }
    };

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // Forzar orientación landscape (siempre horizontal)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        playerView = findViewById(R.id.playerView);
        // FIT en vez de FILL: FILL estira el video y lo deforma en pantallas 16:9/18:9
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setUseController(false);



        urlIframeInicial = getIntent().getStringExtra("url_iframe_inicial");
        streamDirecto = getIntent().getStringExtra("stream_url");
        streamCookies = getIntent().getStringExtra("stream_cookies");
        streamReferer = getIntent().getStringExtra("stream_referer");
        streamOrigin = getIntent().getStringExtra("stream_origin");
        log("URL Inicial: " + urlIframeInicial);
        if (streamDirecto != null && !streamDirecto.isEmpty()) {
            log("Stream directo recibido: " + streamDirecto);
        }

        executorService = Executors.newSingleThreadExecutor();

        configurarSSLInseguroUniversal();
        hideSystemUI();
        setupMedia3Player();
        setupCustomControls();
        setupWebViewFallback();
        registerPipReceiver();
        aplicarParamsPip();          // Android 12+: deja listo el auto-entrar en PiP
        if (streamDirecto != null && !streamDirecto.isEmpty()) {
            resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
        } else {
            resolverStreamEnSegundoPlano();
        }
    }

    // --------------- CONTROLES PERSONALIZADOS ---------------
    @SuppressLint("ClickableViewAccessibility")
    private void setupCustomControls() {
        controlsOverlay = findViewById(R.id.controls);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnRewind = findViewById(R.id.btnRewind);
        btnForward = findViewById(R.id.btnForward);
        btnVolume = findViewById(R.id.btnVolume);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnPip = findViewById(R.id.btnPip);
        seekBar = findViewById(R.id.seekBar);

        // Play/Pause
        btnPlayPause.setOnClickListener(v -> {
            if (player != null) {
                if (player.isPlaying()) player.pause();
                else player.play();
            }
            updatePlayPauseButton();
            actualizarAccionesPip();   // refresca el boton play/pausa del PiP
            resetControlsTimeout();
        });

        // Retroceder 10 segundos
        btnRewind.setOnClickListener(v -> {
            if (player != null) {
                long pos = player.getCurrentPosition() - 10000;
                player.seekTo(Math.max(pos, 0));
            }
            resetControlsTimeout();
        });

        // Adelantar 10 segundos
        btnForward.setOnClickListener(v -> {
            if (player != null) {
                long pos = player.getCurrentPosition() + 10000;
                long dur = player.getDuration();
                if (dur > 0) pos = Math.min(pos, dur);
                player.seekTo(pos);
            }
            resetControlsTimeout();
        });

        // Volumen (toggle silencio)
        btnVolume.setOnClickListener(v -> {
            if (player != null) {
                float vol = player.getVolume();
                if (vol > 0) {
                    player.setVolume(0f);
                    btnVolume.setImageResource(R.drawable.ic_volume_mute);
                } else {
                    player.setVolume(1f);
                    btnVolume.setImageResource(R.drawable.ic_volume);
                }
            }
            resetControlsTimeout();
        });

        // Refrescar stream
        btnRefresh.setOnClickListener(v -> {
            if (streamDirecto != null && !streamDirecto.isEmpty()) {
                resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
            } else {
                resolverStreamEnSegundoPlano();
            }
            resetControlsTimeout();
        });

        // PiP
        btnPip.setOnClickListener(v -> {
            if (!soportaPip()) {
                log("⚠️ Este dispositivo no soporta Picture-in-Picture");
                Toast.makeText(this, "PiP no disponible en este dispositivo", Toast.LENGTH_SHORT).show();
            } else if (!hayVideoNativo()) {
                log("⚠️ Todavía no hay video nativo: no se puede entrar en PiP");
                Toast.makeText(this, "Espera a que cargue el video", Toast.LENGTH_SHORT).show();
            } else {
                entrarEnPip();
            }
            resetControlsTimeout();
        });

        // SeekBar
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
                stopSeekUpdate();
                // Mantener controles visibles mientras arrastra
                mainHandler.removeCallbacks(hideControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                        long newPosition = (duration * seekBar.getProgress()) / 1000L;
                        player.seekTo(newPosition);
                    }
                }
                isSeeking = false;
                startSeekUpdate();
                resetControlsTimeout();
            }
        });

        // Mostrar/ocultar controles al tocar la superficie del video
        playerView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (controlsOverlay.getVisibility() == View.VISIBLE) {
                    hideControlsImmediately();
                } else {
                    showControls();
                }
                return true;
            }
            return false;
        });

        // Mostrar controles al inicio
        showControls();
    }

    private void showControls() {
        if (controlsOverlay != null && !isInPipMode) {
            controlsOverlay.setVisibility(View.VISIBLE);
            updatePlayPauseButton();
            resetControlsTimeout();
            startSeekUpdate();
        }
    }

    private void hideControlsImmediately() {
        mainHandler.removeCallbacks(hideControlsRunnable);
        controlsOverlay.setVisibility(View.GONE);
        stopSeekUpdate();
    }

    private void resetControlsTimeout() {
        mainHandler.removeCallbacks(hideControlsRunnable);
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT);
    }

    private void startSeekUpdate() {
        stopSeekUpdate();
        seekRunnable = new Runnable() {
            @Override
            public void run() {
                if (player != null && !isSeeking && controlsOverlay.getVisibility() == View.VISIBLE) {
                    long duration = player.getDuration();
                    long position = player.getCurrentPosition();
                    if (duration > 0) {
                        int progress = (int) (position * 1000L / duration);
                        seekBar.setProgress(progress);
                    }
                }
                seekHandler.postDelayed(this, 200);
            }
        };
        seekHandler.post(seekRunnable);
    }

    private void stopSeekUpdate() {
        if (seekRunnable != null) {
            seekHandler.removeCallbacks(seekRunnable);
        }
    }

    private void updatePlayPauseButton() {
        if (btnPlayPause != null && player != null) {
            btnPlayPause.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    // --------------- PICTURE IN PICTURE ---------------

    /** El dispositivo soporta PiP (Android 8+ y con la feature del sistema). */
    private boolean soportaPip() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** Solo tiene sentido entrar en PiP si hay video nativo (no el WebView). */
    private boolean hayVideoNativo() {
        return player != null && streamReady
                && (webViewFallback == null || webViewFallback.getVisibility() != View.VISIBLE);
    }

    private boolean estaEnPipSistema() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isInPipMode || entrandoEnPip) return;
        if (soportaPip() && hayVideoNativo() && player != null && player.isPlaying()) {
            entrarEnPip();
        }
    }

    @Override
    public void onBackPressed() {
        // ANTES: si estaba reproduciendo volvia a entrar en PiP -> nunca se cerraba.
        if (isInPipMode || entrandoEnPip) {
            cerrarDesdePip();
            return;
        }
        if (soportaPip() && hayVideoNativo() && player != null && player.isPlaying()) {
            entrarEnPip();       // atras con el video en marcha -> ventana flotante
        } else {
            super.onBackPressed();   // -> finish()
        }
    }

    private void entrarEnPip() {
        if (!soportaPip() || isInPipMode) return;
        try {
            entrandoEnPip = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(construirParamsPip());
            }
        } catch (Exception e) {
            entrandoEnPip = false;
            log("⚠️ No se pudo entrar en PiP: " + e.getMessage());
        }
    }

    @SuppressLint("NewApi")
    private PictureInPictureParams construirParamsPip() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(relacionAspectoVideo())
                .setActions(accionesPip());
        Rect r = rectOrigenPip();
        if (r != null) b.setSourceRectHint(r);      // animacion desde el video
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(hayVideoNativo());  // Android 12+: gesto de inicio -> PiP
            b.setSeamlessResizeEnabled(false);
        }
        return b.build();
    }

    /** Prepara el auto-entrar ANTES de salir de la app (Android 12+). */
    private void aplicarParamsPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !soportaPip()) return;
        try {
            setPictureInPictureParams(construirParamsPip());
        } catch (Exception ignored) { }
    }

    /** Refresca los botones del PiP sin volver a entrar en el modo. */
    private void actualizarAccionesPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (!isInPipMode && !entrandoEnPip) return;
        try {
            setPictureInPictureParams(new PictureInPictureParams.Builder()
                    .setAspectRatio(relacionAspectoVideo())
                    .setActions(accionesPip())
                    .build());
        } catch (Exception ignored) { }
    }

    /** Relacion de aspecto REAL del video (antes era fija 16:9). */
    private Rational relacionAspectoVideo() {
        int w = 16, h = 9;
        if (player != null) {
            androidx.media3.common.VideoSize vs = player.getVideoSize();
            if (vs != null && vs.width > 0 && vs.height > 0) {
                w = vs.width;
                h = vs.height;
            }
        }
        float r = (float) w / (float) h;
        if (r < 0.418410f) r = 0.418410f;   // limites que impone Android
        if (r > 2.390000f) r = 2.390000f;
        return new Rational(Math.round(r * 1000), 1000);
    }

    private Rect rectOrigenPip() {
        if (playerView == null) return null;
        int[] loc = new int[2];
        playerView.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1],
                loc[0] + playerView.getWidth(), loc[1] + playerView.getHeight());
    }

    /** Botones visibles dentro de la ventana flotante. */
    private java.util.List<RemoteAction> accionesPip() {
        java.util.List<RemoteAction> acciones = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return acciones;
        boolean reproduciendo = player != null && player.isPlaying();
        acciones.add(new RemoteAction(
                Icon.createWithResource(this, reproduciendo ? R.drawable.ic_pause : R.drawable.ic_play),
                reproduciendo ? "Pausar" : "Reproducir",
                reproduciendo ? "Pausar" : "Reproducir",
                pendingIntentPip(PIP_ACTION_PLAY_PAUSE)));
        acciones.add(new RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Cerrar",
                "Cierra el reproductor y sale de PiP",
                pendingIntentPip(PIP_ACTION_CLOSE)));
        return acciones;
    }

    private PendingIntent pendingIntentPip(int accion) {
        Intent i = new Intent(ACTION_PIP_CONTROL).setPackage(getPackageName());
        i.putExtra(EXTRA_PIP_ACTION, accion);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(this, accion, i, flags);
    }

    private void registerPipReceiver() {
        pipReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !ACTION_PIP_CONTROL.equals(intent.getAction())) return;
                int accion = intent.getIntExtra(EXTRA_PIP_ACTION, 0);
                if (accion == PIP_ACTION_PLAY_PAUSE) {
                    alternarPlayPause();
                    actualizarAccionesPip();
                } else if (accion == PIP_ACTION_CLOSE) {
                    cerrarDesdePip();
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_PIP_CONTROL);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pipReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(pipReceiver, f);
            }
        } catch (Exception e) {
            log("⚠️ No se pudo registrar el receptor de PiP: " + e.getMessage());
        }
    }

    private void alternarPlayPause() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
        updatePlayPauseButton();
    }

    /** Cierra de verdad el reproductor estando en PiP (antes no habia forma). */
    private void cerrarDesdePip() {
        log("⏹ Cerrando reproductor desde PiP");
        cerrandoDesdePip = true;
        try {
            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
        } catch (Exception ignored) { }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
            else finish();
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                              @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        isInPipMode = isInPictureInPictureMode;
        entrandoEnPip = false;
        if (isInPictureInPictureMode) {
            // En PiP solo se ve el video: fuera controles y panel de depuracion
            if (controlsOverlay != null) {
                controlsOverlay.setVisibility(View.GONE);
                stopSeekUpdate();
            }
            if (playerView != null) {
                playerView.hideController();
                playerView.setUseController(false);
            }
            if (tvDebugLog != null) tvDebugLog.setVisibility(View.GONE);
            if (scrollDebugLog != null) scrollDebugLog.setVisibility(View.GONE);
            hideSystemUI();
            actualizarAccionesPip();
        } else if (!cerrandoDesdePip) {
            // Volvemos a pantalla completa
            hideSystemUI();
            showControls();
            aplicarParamsPip();
        }
    }

    // --------------- CICLO DE VIDA ---------------

    /** Con launchMode="singleTask" sirve para abrir otra peli/canal sin duplicar Activity. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        String nuevaUrl = intent.getStringExtra("stream_url");
        String nuevoIframe = intent.getStringExtra("url_iframe_inicial");
        if (nuevaUrl != null && !nuevaUrl.isEmpty()) {
            streamDirecto = nuevaUrl;
            streamCookies = intent.getStringExtra("stream_cookies");
            streamReferer = intent.getStringExtra("stream_referer");
            streamOrigin = intent.getStringExtra("stream_origin");
            urlIframeInicial = null;
            errorRefreshCount = 0;
            resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
        } else if (nuevoIframe != null && !nuevoIframe.isEmpty()) {
            urlIframeInicial = nuevoIframe;
            streamDirecto = null;
            errorRefreshCount = 0;
            resolverStreamEnSegundoPlano();
        }
    }

    @Override
    protected void onPause() {
        // Si estamos entrando/ya en PiP el video sigue sonando: NO pausar.
        if (!isInPipMode && !entrandoEnPip && !estaEnPipSistema() && player != null) {
            try { player.pause(); } catch (Exception ignored) { }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (!isInPipMode) aplicarParamsPip();
    }

    @Override
    protected void onStop() {
        // ANTES: aqui se liberaba el player al ir a segundo plano, asi que al
        // volver la pantalla quedaba en negro. Solo liberamos si la Activity se
        // esta destruyendo de verdad (y nunca mientras estamos en PiP).
        if (!isInPipMode && !entrandoEnPip && !estaEnPipSistema() && isFinishing() && player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (pipReceiver != null) {
            try { unregisterReceiver(pipReceiver); } catch (Exception ignored) { }
            pipReceiver = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (webViewFallback != null) {
            webViewFallback.stopLoading();
            webViewFallback.loadUrl("about:blank");
            webViewFallback.destroy();
            webViewFallback = null;
        }
        mainHandler.removeCallbacks(hideControlsRunnable);
        stopSeekUpdate();
        super.onDestroy();
    }

    // --------------- MÉTODOS ORIGINALES (SIN CAMBIOS) ---------------
    private void log(String msg) {
        String ts = timeFormat.format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        Log.d("BLABONGO_DEBUG", msg);
        runOnUiThread(() -> {
            if (tvDebugLog != null) {
                tvDebugLog.append(line);
                scrollDebugLog.post(() -> scrollDebugLog.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    private void setupMedia3Player() {
        dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);

        HttpDataSource.Factory loggingDataSourceFactory = new HttpDataSource.Factory() {
            @Override
            public HttpDataSource createDataSource() {
                return new LoggingHttpDataSource(dataSourceFactory.createDataSource());
            }

            @Override
            public HttpDataSource.Factory setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
                dataSourceFactory.setDefaultRequestProperties(defaultRequestProperties);
                return this;
            }
        };

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(loggingDataSourceFactory);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String msg = "❌ ERROR ExoPlayer: " + error.getMessage();
                if (error.getCause() != null) msg += " | " + error.getCause().getMessage();
                log(msg);

                if (!isNetworkAvailable()) {
                    log("🚫 Sin conexión. No se reintentará hasta que vuelva la red.");
                    return;
                }

                long now = System.currentTimeMillis();
                if (now - lastRefreshTime < MIN_REFRESH_INTERVAL_MS) {
                    log("⏱️ Refresco demasiado rápido, esperando...");
                    mainHandler.postDelayed(() -> {
                        if (!isResolving && errorRefreshCount < MAX_REFRESH_RETRIES) {
                            if (streamDirecto != null && !streamDirecto.isEmpty()) {
                                resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
                            } else {
                                resolverStreamEnSegundoPlano();
                            }
                        }
                    }, MIN_REFRESH_INTERVAL_MS - (now - lastRefreshTime));
                    return;
                }

                streamReady = false;
                if (!isResolving && errorRefreshCount < MAX_REFRESH_RETRIES) {
                    errorRefreshCount++;
                    lastRefreshTime = System.currentTimeMillis();
                    log("⚠️ Refrescando stream (intento " + errorRefreshCount + "/" + MAX_REFRESH_RETRIES + ")...");
                    if (streamDirecto != null && !streamDirecto.isEmpty()) {
                        resolverStreamDirecto(streamDirecto, streamCookies, streamReferer, streamOrigin);
                    } else {
                        resolverStreamEnSegundoPlano();
                    }
                } else if (errorRefreshCount >= MAX_REFRESH_RETRIES) {
                    log("❌ Máximo de refrescos alcanzado. Abortando.");
                    mainHandler.postDelayed(() -> errorRefreshCount = 0, 30000);
                    if (webViewFallback != null && webViewFallback.getVisibility() != View.VISIBLE) {
                        log("🌐 Reproducción ExoPlayer fallida, abriendo reproductor web.");
                        isResolving = false;
                        mostrarFallbackWebView();
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING: log("Buffering..."); break;
                    case Player.STATE_READY:
                        log("✅ Ready");
                        errorRefreshCount = 0;
                        updatePlayPauseButton();
                        break;
                    case Player.STATE_ENDED:
                        log("Ended");
                        updatePlayPauseButton();
                        break;
                    case Player.STATE_IDLE: log("Idle"); break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseButton();
                actualizarAccionesPip();
            }

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                // Si el video tiene otra relacion de aspecto (4:3, 21:9...) se
                // reajusta la ventana flotante.
                if (isInPipMode) actualizarAccionesPip();
            }
        });
    }

    private void setupWebViewFallback() {
        webViewFallback = findViewById(R.id.webPlayable);
        if (webViewFallback == null) return;

        WebSettings settings = webViewFallback.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString(StreamResolver.DESKTOP_USER_AGENT);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webViewFallback, true);
        }

        webViewFallback.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        webViewFallback.setWebChromeClient(new WebChromeClient());
    }

    private void mostrarFallbackWebView() {
        runOnUiThread(() -> {
            if (webViewFallback == null) return;
            String url = urlIframeInicial;
            if (url == null || url.isEmpty() || !url.startsWith("http")) {
                log("❌ No hay URL válida para el reproductor web.");
                return;
            }
            log("🌐 Abriendo reproductor web de respaldo: " + url);
            webViewFallback.setVisibility(View.VISIBLE);
            webViewFallback.loadUrl(url);
        });
    }

    /**
     * MEJORA 2026-09-28: elige el resolver según la URL.
     *
     * vsembed (y equivalentes con ds_lang=es o cloudorchestranova) no exponen el
     * m3u8 en el HTML: hay que pasar por vs_src -> cloudorchestranova ->
     * data.vidsrc.sh -> WASM -> token. Eso lo hace PelisStreamResolver.
     * StreamResolver solo usa WebView y con esos dominios se queda en timeout,
     * así que antes el reproductor caía al modo WebView aunque sí había stream.
     */
    private StreamResolver.StreamResult resolverInteligente(String url) throws Exception {
        if (url != null && (url.contains("vsembed") || url.contains("ds_lang=es")
                || url.contains("cloudorchestranova"))) {
            log("Usando PelisStreamResolver (vsembed WASM+Token)...");
            try {
                PelisStreamResolver.StreamResult pr =
                        PelisStreamResolver.resolveSynchronously(PlayerActivity.this, url);
                if (pr != null && pr.m3u8Url != null && !pr.m3u8Url.isEmpty()) {
                    log("✅ PelisStreamResolver devolvió master HLS");
                    return new StreamResolver.StreamResult(pr.m3u8Url, pr.cookies,
                            pr.referer, pr.origin, pr.headers);
                }
            } catch (Throwable t) {
                log("⚠️ PelisStreamResolver falló: " + t.getMessage());
            }
            log("⚠️ Sin stream por el camino rápido, probando resolver genérico...");
        }
        log("Resolviendo stream con StreamResolver (WebView)...");
        return StreamResolver.resolveSynchronously(PlayerActivity.this, url);
    }

    private void resolverStreamEnSegundoPlano() {
        if (isResolving) {
            log("⏳ Ya se está resolviendo, ignoramos llamada duplicada.");
            return;
        }
        isResolving = true;

        executorService.execute(() -> {
            try {
                log("Resolviendo stream con el resolver adecuado...");
                StreamResolver.StreamResult result = resolverInteligente(urlIframeInicial);
                if (result == null || result.m3u8Url == null || result.m3u8Url.isEmpty()) {
                    log("❌ StreamResolver no devolvió stream. Cambiando a reproductor web.");
                    mainHandler.post(() -> {
                        isResolving = false;
                        mostrarFallbackWebView();
                    });
                    return;
                }
                String url = result.m3u8Url;
                String cookies = result.cookies;
                String referer = result.referer;
                String origin = result.origin;
                Map<String, String> capturedHeaders = result.headers;

                log("Stream: " + url);
                log("Cookies: " + (cookies.isEmpty() ? "(vacías)" : cookies));
                log("Referer (wrapper): " + referer);
                log("Origin (iframe real): " + origin);

                if (capturedHeaders != null && !capturedHeaders.isEmpty()) {
                    log("Headers capturados del WebView:");
                    for (Map.Entry<String, String> entry : capturedHeaders.entrySet()) {
                        log("   " + entry.getKey() + ": " + entry.getValue());
                    }
                } else {
                    log("(Sin headers capturados)");
                }

                mainHandler.post(() -> {
                    if (player == null) {
                        isResolving = false;
                        mostrarFallbackWebView();
                        return;
                    }

                    Map<String, String> finalHeaders = new HashMap<>();
                    if (capturedHeaders != null) {
                        finalHeaders.putAll(capturedHeaders);
                    }

                    finalHeaders.put("User-Agent", StreamResolver.DESKTOP_USER_AGENT);

                    boolean hasReferer = false;
                    for (String key : finalHeaders.keySet()) {
                        if (key.equalsIgnoreCase("Referer")) {
                            hasReferer = true;
                            break;
                        }
                    }
                    if (!hasReferer) {
                        finalHeaders.put("Referer", referer != null ? referer : "https://belkaperu.github.io/");
                    }

                    boolean hasOrigin = false;
                    for (String key : finalHeaders.keySet()) {
                        if (key.equalsIgnoreCase("Origin")) {
                            hasOrigin = true;
                            break;
                        }
                    }
                    if (!hasOrigin && origin != null && !origin.isEmpty()) {
                        finalHeaders.put("Origin", origin);
                    } else if (!hasOrigin) {
                        try {
                            java.net.URL urlObj = new java.net.URL(referer);
                            finalHeaders.put("Origin", urlObj.getProtocol() + "://" + urlObj.getHost());
                        } catch (Exception ignored) {}
                    }

                    if (cookies != null && !cookies.isEmpty()) {
                        finalHeaders.put("Cookie", cookies);
                    }

                    log("Headers finales para ExoPlayer:");
                    for (Map.Entry<String, String> entry : finalHeaders.entrySet()) {
                        log("   " + entry.getKey() + ": " + entry.getValue());
                    }

                    player.stop();
                    player.clearMediaItems();

                    dataSourceFactory.setDefaultRequestProperties(finalHeaders);
                    MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.parse(url)).build();
                    player.setMediaItem(mediaItem);
                    player.prepare();
                    player.setPlayWhenReady(true);
                    streamReady = true;
                    isResolving = false;
                    if (webViewFallback != null && webViewFallback.getVisibility() == View.VISIBLE) {
                        webViewFallback.stopLoading();
                        webViewFallback.setVisibility(View.GONE);
                    }
                    log("Reproducción iniciada con headers reales.");
                });
            } catch (Exception e) {
                log("❌ Error en callback: " + e.getMessage());
                mainHandler.post(() -> {
                    isResolving = false;
                    mostrarFallbackWebView();
                });
            }
        });
    }

    private void resolverStreamDirecto(String url, String cookies, String referer, String origin) {
        if (url == null || url.isEmpty()) {
            log("Stream directo vacio");
            return;
        }
        log("Reproduciendo stream m3u8 directo (sin WebView)");
        mainHandler.post(() -> {
            if (player == null) return;
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", StreamResolver.DESKTOP_USER_AGENT);
            if (referer != null && !referer.isEmpty()) headers.put("Referer", referer);
            if (origin != null && !origin.isEmpty()) headers.put("Origin", origin);
            if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
            player.stop();
            player.clearMediaItems();
            dataSourceFactory.setDefaultRequestProperties(headers);
            MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.parse(url)).build();
            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);
            streamReady = true;
            isResolving = false;
            log("Stream directo cargado.");
        });
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();
    }

    private void configurarSSLInseguroUniversal() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {}
    }

    private void hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ctrl = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
        ctrl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ===================== INTERCEPTOR DE RED (LOGS) =====================
    private class LoggingHttpDataSource implements HttpDataSource {
        private final HttpDataSource delegate;

        LoggingHttpDataSource(HttpDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            delegate.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws HttpDataSourceException {
            log("🌍 Petición HTTP a: " + dataSpec.uri);
            try {
                long bytes = delegate.open(dataSpec);
                log("✅ Conexión exitosa (HTTP " + delegate.getResponseCode() + ")");
                return bytes;
            } catch (IOException e) {
                log("❌ Error de red HTTP: " + e.getMessage());
                throw e;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public Uri getUri() {
            return delegate.getUri();
        }

        @Override
        public void close() throws HttpDataSourceException {
            delegate.close();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return delegate.getResponseHeaders();
        }

        @Override
        public void setRequestProperty(String name, String value) {
            delegate.setRequestProperty(name, value);
        }

        @Override
        public void clearRequestProperty(String name) {
            delegate.clearRequestProperty(name);
        }

        @Override
        public void clearAllRequestProperties() {
            delegate.clearAllRequestProperties();
        }

        @Override
        public int getResponseCode() {
            return delegate.getResponseCode();
        }
    }
}