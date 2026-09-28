package dza.folbol.BLABONGO;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;

public class NotificationReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID   = "canal_partidos";
    public static final String CHANNEL_NAME = "Alertas de Partidos";

    @OptIn(markerClass = UnstableApi.class) @Override
    public void onReceive(Context context, Intent intent) {
        // Solo reaccionar a nuestra acción
        if (intent == null || !NotificationScheduler.ACTION_MATCH_NOTIFICATION.equals(intent.getAction()))
            return;

        String titulo  = intent.getStringExtra("titulo");
        String mensaje = intent.getStringExtra("mensaje");
        String urlCanal = intent.getStringExtra("url_iframe_inicial");

        if (titulo  == null || titulo.isEmpty())  titulo  = "Partido próximo";
        if (mensaje == null || mensaje.isEmpty())  mensaje = "Un partido está a punto de comenzar";

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        // Crear canal de notificación (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,

                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Avisos de partidos próximos a comenzar");
            channel.enableVibration(true);
            channel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null);
            manager.createNotificationChannel(channel);
        }

        // Intent que se ejecutará al pulsar la notificación
        Intent playerIntent = new Intent(context, PlayerActivity.class);
        playerIntent.putExtra("url_iframe_inicial", urlCanal);
        playerIntent.putExtra("match_title", titulo);   // opcional, para mostrar en el reproductor
        playerIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, playerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Construir notificación con logo grande y color
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ds)                       // icono pequeño (blanco)
                .setLargeIcon(BitmapFactory.decodeResource(
                        context.getResources(), R.mipmap.ic_launcher)) // logo de la app
                .setColor(ContextCompat.getColor(context, com.google.android.material.R.color.design_default_color_on_primary)) // color de fondo del icono
                .setContentTitle(titulo)
                .setContentText(mensaje)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(mensaje))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(new long[]{0, 300, 200, 300});

        manager.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), builder.build());
    }
}