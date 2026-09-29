package dza.folbol.BLABONGO;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class CanalAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_CANAL = 0;
    private static final int VIEW_TYPE_ANUNCIO = 1;

    private final List<Object> items;   // Canal o "ANUNCIO"
    private final Context context;

    public CanalAdapter(List<Object> items, Context context) {
        this.items = items;
        this.context = context;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Canal ? VIEW_TYPE_CANAL : VIEW_TYPE_ANUNCIO;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_ANUNCIO) {
            //  ⚡ Aquí inflamos TU layout exacto (el mismo de partidos)
            View view = LayoutInflater.from(context).inflate(R.layout.item_ad_nativo, parent, false);
            return new AnuncioViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_canal, parent, false);
            return new CanalViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof CanalViewHolder) {
            Canal canal = (Canal) items.get(position);
            CanalViewHolder cHolder = (CanalViewHolder) holder;
            cHolder.txtNombre.setText(canal.title);
            Glide.with(context).load(canal.image).into(cHolder.imgCanal);
            cHolder.itemView.setOnClickListener(v -> mostrarOpcionesCanal(canal));
        } else if (holder instanceof AnuncioViewHolder) {
            // El MREC se carga automáticamente, no hay nada más que hacer
        }
    }

    private void mostrarOpcionesCanal(Canal canal) {
        if (canal.options == null || canal.options.isEmpty()) {
            Toast.makeText(context, "No hay opciones disponibles", Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetOpcionesFragment bottomSheet = BottomSheetOpcionesFragment.newInstance(canal);

        // El contexto debe ser una FragmentActivity para obtener el FragmentManager
        if (context instanceof androidx.appcompat.app.AppCompatActivity) {
            bottomSheet.show(((androidx.appcompat.app.AppCompatActivity) context).getSupportFragmentManager(),
                    "opciones_canal");
        } else if (context instanceof androidx.fragment.app.FragmentActivity) {
            bottomSheet.show(((androidx.fragment.app.FragmentActivity) context).getSupportFragmentManager(),
                    "opciones_canal");
        } else {
            Toast.makeText(context, "Error al mostrar opciones", Toast.LENGTH_SHORT).show();
        }
    }

    @OptIn(markerClass = UnstableApi.class) private void lanzarReproductor(String url) {
        String urlFinal = normalizarUrlCanal(url);
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra("url_iframe_inicial", urlFinal);
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private String extraerToken(String url) {
        if (url.contains("?r=")) return url.substring(url.indexOf("?r=") + 3);
        return url;
    }

    /**
     * El JSON de canales no entrega URLs completas, sino rutas como esta:
     *
     *   /p/foxrepro1.html?r=https://belkaperu.github.io/belkafut/repron.html?r=<embed>
     *
     * Dos problemas:
     *   1) es una ruta RELATIVA: ni el WebView ni OkHttp saben abrirla.
     *   2) el envoltorio "/p/*.html" YA NO EXISTE en el servidor del proveedor
     *      (responde 404 aunque se le ponga la base delante), asi que tampoco
     *      vale con completarla.
     *
     * Lo que si responde 200 es saltarse el envoltorio y abrir directamente la
     * URL interna que va despues de "?r=", que siempre es absoluta.
     */
    String normalizarUrlCanal(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith("http")) return u;
        int i = u.indexOf("?r=");
        if (i >= 0) {
            String interna = u.substring(i + 3).trim();
            if (interna.startsWith("http")) return interna;
        }
        // Ultimo recurso: se le pone la base del sitio del listado.
        return "https://belkaperu.github.io" + u;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ─── ViewHolders ───
    public static class CanalViewHolder extends RecyclerView.ViewHolder {
        ImageView imgCanal;
        TextView txtNombre;
        public CanalViewHolder(View itemView) {
            super(itemView);
            imgCanal = itemView.findViewById(R.id.imgCanal);
            txtNombre = itemView.findViewById(R.id.txtNombreCanal);
        }
    }

    public static class AnuncioViewHolder extends RecyclerView.ViewHolder {
        public AnuncioViewHolder(View itemView) {
            super(itemView);
            // El MREC se encarga solo
        }
    }
}