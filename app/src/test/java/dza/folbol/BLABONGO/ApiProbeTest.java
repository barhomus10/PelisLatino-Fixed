package dza.folbol.BLABONGO;

import static org.junit.Assert.fail;

import org.junit.Test;

/** Sonda temporal: descubre de dónde salen los datos del sitio. */
public class ApiProbeTest {

    @Test
    public void probe() {
        String content;
        try {
            content = PelisApi.get("https://pelislatinohd.pages.dev/js/app.js",
                    "https://pelislatinohd.pages.dev/");
        } catch (Exception e) {
            content = "FAIL -> " + e.getMessage();
        }
        int n = Math.min(5000, content.length());
        fail("APPJS len=" + content.length() + "\n" + content.substring(0, n));
    }
}
