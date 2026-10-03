package dza.folbol.BLABONGO;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotificationScheduler {

    private static final int MINUTOS_ANTES = 5;
    private static final long MILISEGUNDOS_POR_MINUTO = 60 * 1000L;
    public static final String ACTION_MATCH_NOTIFICATION = "dza.folbol.BLABONGO.MATCH_NOTIFICATION";

    /**
     * Programa alarmas para todos los partidos cuyo inicio sea futuro.
     * Para cada uno, escoge el primer canal que no contenga "HD", "LATAM" (ni palabras similares)
     * y lanza la alarma con ese enlace único.
     */
    public static void programarNotificaciones(Context context, java.util.List<Partido> partidos) {
        // Cancelar alarmas antiguas (no es 100% exacto con request codes distintos, pero no daña)
        cancelarTodasLasAlarmas(context);

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        long ahora = System.currentTimeMillis();

        for (Partido partido : partidos) {
            if (partido.attributes == null) continue;
            long inicioMs = partido.attributes.getStartTimeMillis();
            long alarmaMs = inicioMs - (MINUTOS_ANTES * MILISEGUNDOS_POR_MINUTO);

            if (alarmaMs <= ahora) continue; // ya pasó

            // Obtener el mejor canal (sin HD ni LATAM)
            String canalUrl = obtenerMejorCanal(partido);
            if (canalUrl == null) continue;   // ignorar si no hay canal válido

            String titulo = "⚽ " + extraerEquipos(partido.attributes.diary_description);
            String mensaje = "El partido empieza en " + MINUTOS_ANTES + " minutos. ¡No te lo pierdas!";

            Intent intent = new Intent(context, NotificationReceiver.class);
            intent.setAction(ACTION_MATCH_NOTIFICATION);
            intent.putExtra("titulo", titulo);
            intent.putExtra("mensaje", mensaje);
            intent.putExtra("url_iframe_inicial", canalUrl);

            int requestCode = (int) (inicioMs % Integer.MAX_VALUE);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmaMs, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmaMs, pendingIntent);
            }
        }
    }

    /**
     * Devuelve la URL absoluta del primer canal que NO contenga "HD", "LATAM",
     * "LATINO" u otras palabras que quieras excluir.
     */
    private static String obtenerMejorCanal(Partido partido) {
        if (partido.attributes == null || partido.attributes.embeds == null
                || partido.attributes.embeds.data == null) return null;

        for (Partido.EmbedData embed : partido.attributes.embeds.data) {
            if (embed.attributes == null) continue;
            String name = embed.attributes.embed_name;
            String iframeValue = embed.attributes.embed_iframe;

            if (name == null || iframeValue == null) continue;

            // Excluir canales con HD, LATAM, LATINO (puedes añadir más)
            String upperName = name.toUpperCase();
            if (upperName.contains("HD") || upperName.contains("LATAM") || upperName.contains("LATINO"))
                continue;

            // Convertir URL relativa a absoluta
            return "https://belkaperu.github.io/belkafut/repro.html?r=" + extractToken(iframeValue);
        }
        return null;
    }

    /** Extrae el token del parámetro 'r' de la URL relativa */
    private static String extractToken(String iframeValue) {
        if (iframeValue == null || iframeValue.isEmpty()) return "";
        Pattern p = Pattern.compile("[?&]r=([^&]+)");
        Matcher m = p.matcher(iframeValue);
        if (m.find()) return m.group(1);
        return iframeValue;
    }

    /** Extrae los equipos de la descripción (ej: "Copa Argentina: Racing Club vs Defensa y Justicia") */
    private static String extraerEquipos(String descripcion) {
        if (descripcion == null) return "Partido";
        if (descripcion.contains(":")) {
            return descripcion.split(":")[1].trim();
        }
        return descripcion;
    }

    /** Cancela todas las alarmas con la misma acción (aproximado) */
    private static void cancelarTodasLasAlarmas(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, NotificationReceiver.class);
        intent.setAction(ACTION_MATCH_NOTIFICATION);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
        }
    }
}