package dza.folbol.BLABONGO;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.navigation.NavigationView;
import com.google.gson.Gson;
import com.startapp.sdk.ads.banner.Banner;
import com.startapp.sdk.adsbase.StartAppSDK;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Main extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private static final String URL_CONFIG = "https://raw.githubusercontent.com/belkaperu/json/main/config_app.json";

    private long downloadID;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15,    java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final ActivityResultLauncher<String> permisoNotifLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this,
                            "Sin permiso de notificaciones no recibirás alertas de partidos",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ⚡ Inicializar Start.io (antes de setContentView)
        StartAppSDK.init(this, "205773556", false);
        StartAppSDK.setTestAdsEnabled(false);   // Cambiar a false en producción

        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        Toolbar toolbar = findViewById(R.id.toolbar);

        setSupportActionBar(toolbar);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        navigationView.setNavigationItemSelectedListener(this);

        // Banner de Start.io (fijo en la parte inferior)
        Banner startAppBanner = new Banner(this);
        FrameLayout bannerContainer = findViewById(R.id.bannerContainer);
        if (bannerContainer != null) {
            bannerContainer.addView(startAppBanner);
        }

        if (savedInstanceState == null) {
            loadFragment(new AgendaDeportivaFragment(), R.id.nav_sports_agenda);
        }

        solicitarPermisoNotificaciones();
        hideSystemUI();
        verificarActualizacion();   // <-- verificación de actualización unificada
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_live_channels) {
            loadFragment(new CanalesEnVivoFragment(), id);
        } else if (id == R.id.nav_sports_agenda) {
            loadFragment(new AgendaDeportivaFragment(), id);
        } else if (id == R.id.nav_pelis) {
            loadFragment(new PelisFragment(), id);
        }
        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    private void loadFragment(Fragment fragment, int menuItemId) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
        navigationView.setCheckedItem(menuItemId);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    // ======================================================================
    // ACTUALIZACIÓN (ESTRATEGIA EXACTA DEL PRIMER MAIN)
    // ======================================================================
    private void verificarActualizacion() {
        Request request = new Request.Builder()
                .url(URL_CONFIG)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // Si falla el internet, no hacemos nada
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    ConfigApp config = new Gson().fromJson(json, ConfigApp.class);

                    runOnUiThread(() -> {
                        if (config != null) {
                            int versionActual = obtenerVersionCode();
                            if (config.version_android > versionActual) {
                                evaluarForzadoYMostrar(config);
                            }
                        }
                    });
                }
            }
        });
    }

    private int obtenerVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 1; // Valor por defecto
        }
    }

    private void evaluarForzadoYMostrar(ConfigApp config) {
        SharedPreferences prefs = getSharedPreferences("AppConfig", MODE_PRIVATE);
        int versionGuardada = prefs.getInt("version_detectada", -1);
        long tiempoPrimeraDeteccion = prefs.getLong("tiempo_primera_deteccion", 0);
        long tiempoActual = System.currentTimeMillis();

        if (versionGuardada != config.version_android) {
            prefs.edit()
                    .putInt("version_detectada", config.version_android)
                    .putLong("tiempo_primera_deteccion", tiempoActual)
                    .apply();
            tiempoPrimeraDeteccion = tiempoActual;
        }

        long tiempoTranscurrido = tiempoActual - tiempoPrimeraDeteccion;
        long veinticuatroHoras = 24 * 60 * 60 * 1000L;

        boolean esForzado = tiempoTranscurrido >= veinticuatroHoras;
        mostrarDialogoUpdate(config.link_actualizacion, esForzado);
    }

    private void mostrarDialogoUpdate(String urlApk, boolean esForzado) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        if (esForzado) {
            builder.setTitle("¡Actualización Obligatoria!");
            builder.setMessage("El período de gracia de 24 horas ha terminado. Es necesario instalar la nueva versión para continuar.");
            builder.setCancelable(false);
        } else {
            builder.setTitle("Nueva Actualización Disponible");
            builder.setMessage("Hay mejoras listas para la aplicación. Se volverá obligatoria en un plazo de 24 horas.");
            builder.setCancelable(true);
            builder.setNegativeButton("Más tarde", (dialog, which) -> dialog.dismiss());
        }

        builder.setPositiveButton("Actualizar Ahora", (dialog, which) -> {
            descargarEInstalarApk(urlApk, esForzado);
        });

        builder.show();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void descargarEInstalarApk(String urlApk, boolean esForzado) {
        Toast.makeText(this, "Iniciando descarga en segundo plano...", Toast.LENGTH_LONG).show();

        String nombreArchivo = "actualizacion_agenda.apk";

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(urlApk));
        request.setTitle("Actualizando App");
        request.setDescription("Descargando el archivo de instalación...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombreArchivo);

        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) {
            downloadID = manager.enqueue(request);
        }

        BroadcastReceiver onDownloadComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (downloadID == id) {
                    ejecutarInstalador(nombreArchivo);
                    unregisterReceiver(this);

                    if (esForzado) {
                        finish();  // Cierra la app para forzar la instalación
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(onDownloadComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void ejecutarInstalador(String nombreArchivo) {
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), nombreArchivo);

        if (file.exists()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");

            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo abrir el instalador.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ──────────────────── PERMISOS Y UI ────────────────────
    private void solicitarPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permisoNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ctrl =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
        ctrl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ──────────────────── MODELO CONFIG APP ────────────────────
    public static class ConfigApp {
        public int    version_android;
        public String link_actualizacion;
    }
}