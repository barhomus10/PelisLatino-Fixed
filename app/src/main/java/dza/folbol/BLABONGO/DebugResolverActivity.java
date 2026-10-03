package dza.folbol.BLABONGO;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

/**
 * Actividad de debug para probar el resolver de películas moderno
 * Permite probar diferentes ID de películas y ver los resultados
 */
public class DebugResolverActivity extends AppCompatActivity {

    private static final String TAG = "DebugResolver";
    
    private TextView txtResultados;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.activity_debug_resolver);
        
        txtResultados = findViewById(R.id.txtResultados);
        
        // Configurar botones de prueba
        setupTestButtons();
        
        Log.d(TAG, "🛠️ Actividad de debug iniciada");
    }

    private void setupTestButtons() {
        // Botón para probar con una película de ejemplo
        Button btnPeliEjemplo = findViewById(R.id.btnPeliEjemplo);
        btnPeliEjemplo.setOnClickListener(v -> probarPeliculaEjemplo());
        
        // Botón para probar con un ID específico
        Button btnPeliId = findViewById(R.id.btnPeliId);
        btnPeliId.setOnClickListener(v -> probarConId("tt1234567"));
        
        // Botón para ver logs
        Button btnVerLogs = findViewById(R.id.btnVerLogs);
        btnVerLogs.setOnClickListener(v -> mostrarLogs());
    }

    private void probarPeliculaEjemplo() {
        txtResultados.setText("🔍 Probando con ID: tt1234567 (ejemplo)...\n");
        
        ModernPelisResolver.resolverAsync("tt1234567", null, null, new ModernPelisResolver.Callback() {
            @Override
            public void onOk(ModernPelisResolver.MediaInfo info) {
                runOnUiThread(() -> {
                    String mensaje = "✅ ÉXITO!\n\n";
                    mensaje += "🎬 Título: " + info.titulo + "\n";
                    mensaje += "📅 Año: " + info.anio + "\n";
                    mensaje += "⭐ Rating: " + info.rating + "\n";
                    mensaje += "📝 Sinopsis: " + info.sinopsis + "\n";
                    mensaje += "\n📺 " + info.servidores.size() + " servidores:\n";
                    
                    for (int i = 0; i < info.servidores.size(); i++) {
                        ModernPelisResolver.StreamInfo s = info.servidores.get(i);
                        mensaje += (i + 1) + ". " + s.nombre + " [" + s.idioma + "]\n";
                        mensaje += "   " + s.url + "\n\n";
                    }
                    
                    txtResultados.setText(mensaje);
                });
            }

            @Override
            public void onError(String mensaje) {
                runOnUiThread(() -> {
                    txtResultados.setText("❌ ERROR:\n\n" + mensaje);
                    Toast.makeText(DebugResolverActivity.this, 
                        "Error: " + mensaje, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void probarConId(String imdbId) {
        txtResultados.setText("🔍 Probando con ID: " + imdbId + "...\n");
        
        ModernPelisResolver.resolverAsync(imdbId, null, null, new ModernPelisResolver.Callback() {
            @Override
            public void onOk(ModernPelisResolver.MediaInfo info) {
                runOnUiThread(() -> {
                    String mensaje = "✅ ÉXITO!\n\n";
                    mensaje += "🎬 Título: " + info.titulo + "\n";
                    mensaje += "📅 Año: " + info.anio + "\n";
                    mensaje += "⭐ Rating: " + info.rating + "\n";
                    mensaje += "\n📺 " + info.servidores.size() + " servidores:\n";
                    
                    for (int i = 0; i < info.servidores.size(); i++) {
                        ModernPelisResolver.StreamInfo s = info.servidores.get(i);
                        mensaje += (i + 1) + ". " + s.nombre + " [" + s.idioma + "]\n";
                    }
                    
                    txtResultados.setText(mensaje);
                });
            }

            @Override
            public void onError(String mensaje) {
                runOnUiThread(() -> {
                    txtResultados.setText("❌ ERROR:\n\n" + mensaje);
                });
            }
        });
    }

    private void mostrarLogs() {
        StringBuilder sb = new StringBuilder("📋 LOGS DE DEBUG:\n\n");
        
        // Probar todos los dominios
        for (String dominio : ModernPelisResolver.DOMINIOS) {
            sb.append("🌐 ").append(dominio).append("\n");
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Debug Logs");
        builder.setMessage(sb.toString());
        builder.setPositiveButton("OK", null);
        builder.show();
    }
}