package com.pelislatinohd.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.pelislatinohd.app.data.CatalogRepository;
import com.pelislatinohd.app.model.Catalog;
import com.pelislatinohd.app.model.Movie;
import com.pelislatinohd.app.ui.MovieAdapter;
import com.pelislatinohd.app.ui.SliderAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private CatalogRepository repo;
    private Catalog catalog;
    private ProgressBar progress;
    private ViewPager2 slider;
    private SliderAdapter sliderAdapter;
    private MovieAdapter moviesAdapter, seriesAdapter, animesAdapter;
    private Handler handler = new Handler();
    private Runnable sliderRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progress = findViewById(R.id.progress);
        slider = findViewById(R.id.slider);
        TabLayout dots = findViewById(R.id.sliderDots);
        RecyclerView rvMovies = findViewById(R.id.rvMovies);
        RecyclerView rvSeries = findViewById(R.id.rvSeries);
        RecyclerView rvAnimes = findViewById(R.id.rvAnimes);

        sliderAdapter = new SliderAdapter(this::openDetail);
        slider.setAdapter(sliderAdapter);
        new TabLayoutMediator(dots, slider, (tab, pos) -> {}).attach();

        moviesAdapter = new MovieAdapter(this::openDetail);
        seriesAdapter = new MovieAdapter(this::openDetail);
        animesAdapter = new MovieAdapter(this::openDetail);

        rvMovies.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvSeries.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvAnimes.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvMovies.setAdapter(moviesAdapter);
        rvSeries.setAdapter(seriesAdapter);
        rvAnimes.setAdapter(animesAdapter);

        findViewById(R.id.searchInput).setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) startActivity(new Intent(this, SearchActivity.class));
        });
        findViewById(R.id.searchInput).setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        findViewById(R.id.btnFavorites).setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class).putExtra("mode","fav")));

        repo = new CatalogRepository();
        loadCatalog();
    }

    private void loadCatalog() {
        progress.setVisibility(View.VISIBLE);
        repo.load(new CatalogRepository.Callback() {
            @Override public void onSuccess(Catalog cat) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    catalog = cat;
                    render(cat);
                    startAutoSlider();
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Error: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void render(Catalog cat) {
        // Slider: top rated
        List<Movie> all = new ArrayList<>();
        all.addAll(cat.movies);
        all.addAll(cat.series);
        Collections.sort(all, (a,b)-> Double.compare(b.rating, a.rating));
        List<Movie> sliderItems = all.subList(0, Math.min(10, all.size()));
        sliderAdapter.set(sliderItems);

        // Películas (type movie)
        List<Movie> movies = cat.movies;
        // limitar a 20 recientes (por fecha)
        Collections.sort(movies, (a,b)-> b.date.compareTo(a.date));
        moviesAdapter.set(movies.subList(0, Math.min(20, movies.size())));

        // Series
        seriesAdapter.set(cat.series.subList(0, Math.min(20, cat.series.size())));

        // Animes: filtrar por genre id? En catalog genres no tenemos mapping exacto, usamos fallback: series con país Japan
        List<Movie> animes = new ArrayList<>();
        for (Movie s : cat.series) {
            if ("Japan".equals(s.country)) animes.add(s);
        }
        if (animes.isEmpty()) animes = cat.series.subList(0, Math.min(10, cat.series.size()));
        animesAdapter.set(animes.subList(0, Math.min(20, animes.size())));
    }

    private void startAutoSlider() {
        sliderRunnable = new Runnable() {
            @Override public void run() {
                if (sliderAdapter.getItemCount() == 0) return;
                int next = (slider.getCurrentItem() + 1) % sliderAdapter.getItemCount();
                slider.setCurrentItem(next, true);
                handler.postDelayed(this, 5000);
            }
        };
        handler.postDelayed(sliderRunnable, 5000);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (sliderRunnable != null) handler.removeCallbacks(sliderRunnable);
    }

    private void openDetail(Movie m) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("slug", m.slug);
        i.putExtra("type", m.type);
        startActivity(i);
    }
}
