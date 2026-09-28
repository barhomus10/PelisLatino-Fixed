package com.pelislatinohd.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.pelislatinohd.app.data.CatalogRepository;
import com.pelislatinohd.app.model.Catalog;
import com.pelislatinohd.app.model.Movie;
import com.pelislatinohd.app.ui.MovieAdapter;
import java.util.ArrayList;
import java.util.List;

public class DetailActivity extends AppCompatActivity {
    private CatalogRepository repo;
    private Movie movie;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_detail);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar()!=null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        tb.setNavigationOnClickListener(v->finish());

        progress = findViewById(R.id.progressDetail);
        String slug = getIntent().getStringExtra("slug");
        String type = getIntent().getStringExtra("type");

        repo = new CatalogRepository();
        progress.setVisibility(View.VISIBLE);
        repo.load(new CatalogRepository.Callback() {
            @Override public void onSuccess(Catalog cat) {
                runOnUiThread(()->{
                    progress.setVisibility(View.GONE);
                    movie = repo.findBySlug(cat, slug);
                    if (movie == null) {
                        Toast.makeText(DetailActivity.this,"No encontrado",Toast.LENGTH_SHORT).show();
                        finish(); return;
                    }
                    bind(movie, cat);
                });
            }
            @Override public void onError(String msg){ runOnUiThread(()->{ progress.setVisibility(View.GONE); Toast.makeText(DetailActivity.this,msg,Toast.LENGTH_SHORT).show(); }); }
        });
    }

    private void bind(Movie m, Catalog cat){
        ImageView poster = findViewById(R.id.poster);
        ImageView backdrop = findViewById(R.id.backdrop);
        TextView title = findViewById(R.id.title);
        TextView year = findViewById(R.id.year);
        TextView rating = findViewById(R.id.rating);
        TextView runtime = findViewById(R.id.runtime);
        TextView genres = findViewById(R.id.genres);
        TextView synopsis = findViewById(R.id.synopsis);

        title.setText(m.title);
        year.setText(String.valueOf(m.year));
        rating.setText(String.format("★ %.1f (TMDb %.1f)", m.rating, m.tmdbRating));
        runtime.setText(m.runtime>0? m.runtime+" min" : "");
        synopsis.setText(m.synopsis);

        // géneros
        List<String> gnames = new ArrayList<>();
        if (m.genres!=null) for (Integer id: m.genres){
            String n = cat.genres.get(String.valueOf(id));
            if (n!=null) gnames.add(n);
        }
        genres.setText(String.join(" • ", gnames) + (m.country!=null?" • "+m.country:""));

        Glide.with(this).load(m.poster).into(poster);
        Glide.with(this).load(m.backdrop!=null?m.backdrop:m.poster).into(backdrop);

        findViewById(R.id.btnPlay).setOnClickListener(v-> play(m));
        findViewById(R.id.btnShare).setOnClickListener(v->{
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, m.title + " https://pelislatinohd.pages.dev/detalle/"+m.type+"/"+m.slug+"/");
            startActivity(Intent.createChooser(s,"Compartir"));
        });
        findViewById(R.id.btnFavorite).setOnClickListener(v->{
            // Room insert (simplificado: toast)
            Toast.makeText(this,"Añadido a favoritos ★",Toast.LENGTH_SHORT).show();
        });

        // relacionadas: mismo género
        RecyclerView rvRelated = findViewById(R.id.rvRelated);
        rvRelated.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL,false));
        MovieAdapter rel = new MovieAdapter(movie2->{
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra("slug", movie2.slug); i.putExtra("type", movie2.type);
            startActivity(i);
        });
        List<Movie> related = new ArrayList<>();
        for (Movie other: cat.movies){
            if (other.slug.equals(m.slug)) continue;
            if (other.genres!=null && m.genres!=null && !java.util.Collections.disjoint(other.genres, m.genres)){
                related.add(other); if (related.size()>=10) break;
            }
        }
        rel.set(related);
        rvRelated.setAdapter(rel);

        // Si es serie, mostrar episodios
        if ("series".equals(m.type) && m.episodes!=null && !m.episodes.isEmpty()){
            findViewById(R.id.seriesContainer).setVisibility(View.VISIBLE);
            RecyclerView rvEp = findViewById(R.id.rvEpisodes);
            rvEp.setLayoutManager(new LinearLayoutManager(this));
            EpisodeAdapter epAdapter = new EpisodeAdapter(ep->{
                // Para episodios, el embedded es la url del player de ese episodio
                Intent pi = new Intent(this, PlayerActivity.class);
                pi.putExtra("title", m.title + " T"+ep.season+" E"+ep.episode);
                pi.putExtra("ep_embed", ep.embedded);
                pi.putExtra("slug", m.slug);
                startActivity(pi);
            });
            epAdapter.set(m.episodes);
            rvEp.setAdapter(epAdapter);
        }
    }

    private void play(Movie m){
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("slug", m.slug);
        i.putExtra("type", m.type);
        i.putExtra("embeddedId", m.embeddedId);
        i.putExtra("title", m.title);
        startActivity(i);
    }

    // Adapter interno para episodios
    static class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.VH>{
        interface OnEpClick{ void onClick(com.pelislatinohd.app.model.Episode e); }
        private final OnEpClick click;
        private List<com.pelislatinohd.app.model.Episode> data=new ArrayList<>();
        EpisodeAdapter(OnEpClick c){click=c;}
        void set(List<com.pelislatinohd.app.model.Episode> d){data=d; notifyDataSetChanged();}
        @Override public VH onCreateViewHolder(android.view.ViewGroup p,int t){
            return new VH(android.view.LayoutInflater.from(p.getContext()).inflate(R.layout.item_episode,p,false));
        }
        @Override public void onBindViewHolder(VH h,int pos){
            com.pelislatinohd.app.model.Episode e=data.get(pos);
            h.num.setText("T"+e.season+" E"+e.episode);
            h.title.setText(e.title!=null?e.title:"Episodio "+e.episode);
            h.date.setText(e.date!=null?e.date:"");
            h.itemView.setOnClickListener(v->click.onClick(e));
        }
        @Override public int getItemCount(){return data.size();}
        static class VH extends RecyclerView.ViewHolder{
            TextView num,title,date;
            VH(View v){ super(v); num=v.findViewById(R.id.epNumber); title=v.findViewById(R.id.epTitle); date=v.findViewById(R.id.epDate); }
        }
    }
}
