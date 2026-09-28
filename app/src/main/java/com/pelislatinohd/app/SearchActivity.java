package com.pelislatinohd.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.pelislatinohd.app.data.CatalogRepository;
import com.pelislatinohd.app.model.Catalog;
import com.pelislatinohd.app.model.Movie;
import com.pelislatinohd.app.ui.MovieAdapter;
import java.util.List;

public class SearchActivity extends AppCompatActivity {
    private Catalog catalog;
    private MovieAdapter adapter;
    private EditText input;
    private TextView count;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(R.layout.activity_search);
        input = findViewById(R.id.searchInput);
        count = findViewById(R.id.count);
        ProgressBar progress = findViewById(R.id.progress);
        RecyclerView rv = findViewById(R.id.rv);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new MovieAdapter(m->{
            Intent i=new Intent(this, DetailActivity.class);
            i.putExtra("slug", m.slug); i.putExtra("type", m.type);
            startActivity(i);
        });
        rv.setAdapter(adapter);

        CatalogRepository repo = new CatalogRepository();
        progress.setVisibility(View.VISIBLE);
        repo.load(new CatalogRepository.Callback(){
            @Override public void onSuccess(Catalog c){
                runOnUiThread(()->{
                    progress.setVisibility(View.GONE);
                    catalog=c;
                    adapter.set(repo.filterMovies(c, ""));
                    count.setText(c.movies.size()+c.series.size()+" títulos");
                    if ("fav".equals(getIntent().getStringExtra("mode"))){
                        count.setText("Favoritos — próximamente con Room");
                    }
                });
            }
            @Override public void onError(String msg){
                runOnUiThread(()->{ progress.setVisibility(View.GONE); count.setText("Error: "+msg); });
            }
        });

        input.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void afterTextChanged(Editable s){
                if (catalog==null) return;
                CatalogRepository r=new CatalogRepository();
                List<Movie> filtered = r.filterMovies(catalog, s.toString());
                adapter.set(filtered);
                count.setText(filtered.size()+" resultados");
                findViewById(R.id.empty).setVisibility(filtered.isEmpty()?View.VISIBLE:View.GONE);
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(v->finish());
    }
}
