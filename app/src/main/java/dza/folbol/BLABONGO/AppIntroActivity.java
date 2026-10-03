package dza.folbol.BLABONGO;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;

/**
 * Actividad de introducción con 3 slides para presentar la aplicación
 * Se muestra solo la primera vez que se abre la app
 */
public class AppIntroActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AppIntroPrefs";
    private static final String KEY_HAS_SEEN_INTRO = "has_seen_intro";

    // Datos de los slides
    private final IntroSlide[] slides = {
            new IntroSlide(
                    "¡Bienvenido a BLABONGO!",
                    "Tu aplicación todo-en-uno para deportes, películas y canales en vivo",
                    R.drawable.intro_sports // Puedes crear estos drawables después
            ),
            new IntroSlide(
                    "Deportes en Vivo",
                    "Agenda deportiva, resultados en tiempo real y canales de streaming",
                    R.drawable.intro_pelis
            ),
            new IntroSlide(
                    "Películas y Series",
                    "Catálogo con miles de películas y series con múltiples servidores",
                    R.drawable.intro_tv
            )
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Verificar si ya vio la intro
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean hasSeenIntro = prefs.getBoolean(KEY_HAS_SEEN_INTRO, false);

        if (hasSeenIntro) {
            // Si ya vio la intro, ir directamente a Main
            Intent intent = new Intent(this, Main.class);
            startActivity(intent);
            finish();
            return;
        }

        setContentView(R.layout.activity_app_intro);

        setupViewPager();
        setupButtons();
    }

    private void setupViewPager() {
        ViewPager2 viewPager = findViewById(R.id.viewPager);

        int[] imagenes = new int[slides.length];
        String[] titulos = new String[slides.length];
        String[] descripciones = new String[slides.length];
        for (int i = 0; i < slides.length; i++) {
            imagenes[i] = slides[i].iconRes;
            titulos[i] = slides[i].title;
            descripciones[i] = slides[i].description;
        }

        AppIntroAdapter adapter = new AppIntroAdapter(imagenes, titulos, descripciones);
        viewPager.setAdapter(adapter);
    }

    private void setupButtons() {
        MaterialButton btnSkip = findViewById(R.id.btnSkip);
        MaterialButton btnNext = findViewById(R.id.btnNext);
        MaterialButton btnFinish = findViewById(R.id.btnFinish);
        ImageView ivDotCurrent = findViewById(R.id.ivDotCurrent);
        ImageView ivDotPrevious = findViewById(R.id.ivDotPrevious);
        ImageView ivDotNext = findViewById(R.id.ivDotNext);

        btnSkip.setOnClickListener(v -> finishIntro());

        btnNext.setOnClickListener(v -> {
            ViewPager2 viewPager = findViewById(R.id.viewPager);
            int currentItem = viewPager.getCurrentItem();
            if (currentItem < slides.length - 1) {
                viewPager.setCurrentItem(currentItem + 1);
                updateDots(ivDotPrevious, ivDotCurrent, ivDotNext, currentItem + 1);
            }
        });

        btnFinish.setOnClickListener(v -> finishIntro());

        // Listener para actualizar los dots
        ViewPager2 viewPager = findViewById(R.id.viewPager);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(ivDotPrevious, ivDotCurrent, ivDotNext, position);
                updateButtons(btnNext, btnFinish, position);
            }
        });
    }

    private void updateDots(ImageView dotPrevious, ImageView dotCurrent, 
                          ImageView dotNext, int position) {
        if (position == 0) {
            // Primer slide
            dotPrevious.setVisibility(ImageView.INVISIBLE);
            dotCurrent.setVisibility(ImageView.VISIBLE);
            dotCurrent.setImageResource(R.drawable.dot_active);
            dotNext.setImageResource(R.drawable.dot_inactive);
            dotNext.setVisibility(ImageView.VISIBLE);
        } else if (position == slides.length - 1) {
            // Último slide
            dotPrevious.setImageResource(R.drawable.dot_active);
            dotPrevious.setVisibility(ImageView.VISIBLE);
            dotCurrent.setImageResource(R.drawable.dot_active);
            dotCurrent.setVisibility(ImageView.VISIBLE);
            dotNext.setVisibility(ImageView.INVISIBLE);
        } else {
            // Slide del medio
            dotPrevious.setImageResource(R.drawable.dot_active);
            dotPrevious.setVisibility(ImageView.VISIBLE);
            dotCurrent.setImageResource(R.drawable.dot_active);
            dotCurrent.setVisibility(ImageView.VISIBLE);
            dotNext.setImageResource(R.drawable.dot_inactive);
            dotNext.setVisibility(ImageView.VISIBLE);
        }
    }

    private void updateButtons(MaterialButton btnNext, MaterialButton btnFinish, int position) {
        if (position == slides.length - 1) {
            btnNext.setVisibility(View.GONE);
            btnFinish.setVisibility(View.VISIBLE);
        } else {
            btnNext.setVisibility(View.VISIBLE);
            btnFinish.setVisibility(View.GONE);
        }
    }

    private void finishIntro() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_HAS_SEEN_INTRO, true).apply();

        Intent intent = new Intent(this, Main.class);
        startActivity(intent);
        finish();
    }

    // Clase interna para representar un slide
    private static class IntroSlide {
        String title;
        String description;
        int iconRes;

        IntroSlide(String title, String description, int iconRes) {
            this.title = title;
            this.description = description;
            this.iconRes = iconRes;
        }
    }
}
