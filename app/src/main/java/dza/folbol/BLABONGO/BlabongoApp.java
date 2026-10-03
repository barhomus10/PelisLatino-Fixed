package dza.folbol.BLABONGO;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Application de la app. Su única misión es llevar la cuenta de las
 * Activities que están vivas para poder <b>ocultar la app detrás de la
 * ventanita flotante (PiP)</b> sin tocar el reproductor.
 *
 * Antes se intentaba con moveTaskToBack(), pero en algunos móviles eso hace
 * que el sistema CIERRE el PiP en vez de mandar la app al fondo. Cerrando las
 * pantallas que quedan por debajo se consigue el efecto deseado (la app
 * desaparece y solo se ve el vídeo flotando) sin apagar el PiP.
 */
public class BlabongoApp extends Application {

    private static final List<WeakReference<Activity>> vivas = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle s) {
                vivas.add(new WeakReference<Activity>(a));
            }
            @Override public void onActivityDestroyed(Activity a) { limpiar(); }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle out) { }
        });
    }

    private static void limpiar() {
        for (int i = vivas.size() - 1; i >= 0; i--) {
            Activity a = vivas.get(i).get();
            if (a == null || a.isFinishing() || a.isDestroyed()) vivas.remove(i);
        }
    }

    /**
     * Cierra todas las Activities menos la indicada. Se usa al entrar en PiP
     * para que la app no se quede visible por detrás de la ventanita.
     */
    public static void cerrarTodasExcepto(Activity excepto) {
        try {
            limpiar();
            List<Activity> copia = new ArrayList<Activity>();
            for (WeakReference<Activity> r : vivas) {
                Activity a = r.get();
                if (a != null && a != excepto && !a.isFinishing()) copia.add(a);
            }
            for (Activity a : copia) {
                try { a.finish(); } catch (Exception ignored) { }
            }
            limpiar();
        } catch (Exception ignored) { }
    }
}
