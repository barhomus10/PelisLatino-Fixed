package com.pelislatinohd.app.data;

import android.util.Log;
import com.google.gson.Gson;
import com.pelislatinohd.app.model.Catalog;
import com.pelislatinohd.app.model.Movie;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CatalogRepository {
    private static final String TAG = "CatalogRepo";
    public static final String CATALOG_URL = "https://pelislatinohd.pages.dev/data/catalog.json";
    private static Catalog cached;
    private final OkHttpClient client;
    private final Gson gson;

    public CatalogRepository() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
    }

    public interface Callback {
        void onSuccess(Catalog catalog);
        void onError(String msg);
    }

    public void load(Callback cb) {
        if (cached != null) {
            cb.onSuccess(cached);
            return;
        }
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(CATALOG_URL)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124")
                        .addHeader("Referer", "https://pelislatinohd.pages.dev/")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        cb.onError("HTTP " + resp.code());
                        return;
                    }
                    String json = resp.body().string();
                    Catalog cat = gson.fromJson(json, Catalog.class);
                    cached = cat;
                    Log.d(TAG, "Catalog cargado: " + cat.movies.size() + " movies, " + cat.series.size() + " series");
                    cb.onSuccess(cat);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error catalog", e);
                cb.onError(e.getMessage());
            }
        }).start();
    }

    public List<Movie> filterMovies(Catalog cat, String query) {
        if (query == null || query.trim().isEmpty()) return cat.movies;
        String q = query.toLowerCase().trim();
        List<Movie> out = new ArrayList<>();
        for (Movie m : cat.movies) {
            if (m.title.toLowerCase().contains(q) || (m.originalTitle != null && m.originalTitle.toLowerCase().contains(q))) out.add(m);
        }
        for (Movie s : cat.series) {
            if (s.title.toLowerCase().contains(q)) out.add(s);
        }
        return out;
    }

    public Movie findBySlug(Catalog cat, String slug) {
        for (Movie m : cat.movies) if (slug.equals(m.slug)) return m;
        for (Movie s : cat.series) if (slug.equals(s.slug)) return s;
        return null;
    }

    public Movie findByEmbeddedId(Catalog cat, String embeddedId) {
        for (Movie m : cat.movies) if (embeddedId.equals(m.embeddedId)) return m;
        for (Movie s : cat.series) if (embeddedId.equals(s.embeddedId)) return s;
        return null;
    }
}
