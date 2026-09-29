package dza.folbol.BLABONGO;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.util.Arrays;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Resolver del servidor <b>embed69</b> (opción "Multi"), que es el que más
 * títulos tiene disponibles con audio en español: expone copias
 * <b>LAT</b> (latino), <b>ESP</b> (castellano) y <b>SUB</b> (subtitulada).
 *
 * <p>La página protege los enlaces con un reto Proof-of-Work + AES-CBC, pero el
 * propio HTML trae el algoritmo completo, así que se resuelve sin WebView:</p>
 * <ol>
 *   <li>Se leen del HTML {@code POW_CHALLENGE}, {@code POW_DIFFICULTY},
 *       {@code POW_SALT} y el array {@code dataLink} (con los embeds cifrados
 *       clasificados por idioma).</li>
 *   <li>Se busca un {@code nonce} tal que
 *       {@code sha256(challenge + nonce)} empiece por N ceros.</li>
 *   <li>La clave es {@code aesKey = sha256(challenge + nonce + salt)}; cada
 *       enlace se descifra con AES-CBC (los 16 primeros bytes son el IV).</li>
 *   <li>Se elige primero el idioma LAT, luego ESP, y dentro de cada idioma el
 *       servidor {@code vidhide}, cuyo player va ofuscado con el mismo packer
 *       que ya resolvemos en {@link JsUnpacker} → master.m3u8.</li>
 * </ol>
 *
 * Verificado en vivo 2026-09-29 (The Matrix, Shawshank... títulos que vimeus
 * no tiene).
 */
public final class Embed69Resolver {

    private static final String TAG = "Embed69Resolver";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern P_CHALLENGE =
            Pattern.compile("POW_CHALLENGE\\s*=\\s*'([^']+)'");
    private static final Pattern P_DIFFICULTY =
            Pattern.compile("POW_DIFFICULTY\\s*=\\s*(\\d+)");
    private static final Pattern P_SALT =
            Pattern.compile("POW_SALT\\s*=\\s*'([^']+)'");
    private static final Pattern M3U8 =
            Pattern.compile("https?://[^\"'\\ ]+\\.m3u8[^\"'\\ ]*");

    private static final long MAX_NONCE = 4_000_000L;

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            // Algunos CDNs de vídeo siguen usando TLS "clásico": sin esto habría
            // fallos de handshake en según qué móvil.
            .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .build();

    private Embed69Resolver() { }

    public static boolean isEmbed69(String url) {
        return url != null && url.toLowerCase(Locale.US).contains("embed69.org");
    }

    /**
     * @param url la URL tipo {@code https://embed69.org/f/tt0133093/}
     */
    public static PelisStreamResolver.StreamResult resolve(android.content.Context ctx, String url)
            throws Exception {
        long t0 = System.currentTimeMillis();

        // ---- 1) constantes del reto + enlaces cifrados ----
        String html = get(url, "https://playpaste.link/player/");
        Matcher mc = P_CHALLENGE.matcher(html);
        Matcher md = P_DIFFICULTY.matcher(html);
        Matcher ms = P_SALT.matcher(html);
        if (!mc.find() || !md.find() || !ms.find()) {
            throw new Exception("embed69: sin reto PoW en la página");
        }
        String challenge = mc.group(1);
        int dificultad = Integer.parseInt(md.group(1));
        String salt = ms.group(1);

        String json = extraerDataLink(html);
        if (json == null) throw new Exception("embed69: sin dataLink");
        JSONArray dataLink = new JSONArray(json);
        if (dataLink.length() == 0) throw new Exception("embed69: dataLink vacío");
        Log.d(TAG, "dataLink con " + dataLink.length() + " idiomas, dificultad=" + dificultad);

        // ---- 2) resolver el PoW y derivar la clave AES ----
        String prefijo = new String(new char[dificultad]).replace('\0', '0');
        long nonce = -1;
        for (long n = 0; n < MAX_NONCE; n++) {
            if (sha256Hex(challenge + n).startsWith(prefijo)) { nonce = n; break; }
        }
        if (nonce < 0) throw new Exception("embed69: PoW no resuelto");
        byte[] aesKey = sha256(challenge + nonce + salt);
        Log.d(TAG, "PoW resuelto nonce=" + nonce + " en " + (System.currentTimeMillis() - t0) + "ms");

        // ---- 3) idiomas ordenados: LAT -> ESP -> otro -> SUB ----
        List<JSONObject> idiomas = new ArrayList<>();
        for (int i = 0; i < dataLink.length(); i++) {
            JSONObject o = dataLink.optJSONObject(i);
            if (o != null) idiomas.add(o);
        }
        idiomas.sort((a, b) -> puntos(a) - puntos(b));

        // ---- 4) para cada idioma, probar servidores hasta dar con un m3u8 ----
        Exception ultimo = null;
        for (JSONObject idioma : idiomas) {
            String lang = idioma.optString("video_language", "");
            JSONArray servidores = servidoresOrdenados(idioma.optJSONArray("sortedEmbeds"));
            for (int s = 0; s < servidores.length(); s++) {
                JSONObject emb = servidores.optJSONObject(s);
                if (emb == null) continue;
                String nombre = emb.optString("servername", "");
                String cifrado = emb.optString("link", "");
                if (cifrado.isEmpty()) continue;
                String enlace;
                try {
                    enlace = descifrar(cifrado, aesKey);
                } catch (Exception e) {
                    ultimo = e;
                    continue;
                }
                if (enlace == null || !enlace.startsWith("http")) {
                    Log.d(TAG, "  " + lang + "/" + nombre + " -> enlace no usable: " + enlace);
                    continue;
                }
                Log.d(TAG, "  " + lang + "/" + nombre + " -> " + enlace);
                try {
                    String m3u8 = m3u8DelEmbed(enlace);
                    if (m3u8 == null) continue;
                    Map<String, String> headers = new HashMap<>();
                    headers.put("User-Agent", UA);
                    headers.put("Referer", origen(enlace));
                    headers.put("Origin", origen(enlace).substring(0, origen(enlace).length() - 1));
                    headers.put("Accept", "*/*");
                    Log.d(TAG, "OK idioma=" + lang + " servidor=" + nombre + " ("
                            + (System.currentTimeMillis() - t0) + "ms)");
                    return new PelisStreamResolver.StreamResult(
                            m3u8, "", origen(enlace), sinBarra(origen(enlace)), headers);
                } catch (Exception e) {
                    Log.w(TAG, "  falló " + lang + "/" + nombre + ": " + e.getMessage());
                    ultimo = e;
                }
            }
        }
        throw new Exception("embed69: ningún enlace sirvió"
                + (ultimo != null ? " (" + ultimo.getMessage() + ")" : ""));
    }

    /** Descifra el embed y saca el master.m3u8 validándolo. */
    private static String m3u8DelEmbed(String embedUrl) throws Exception {
        String html = get(embedUrl, "https://embed69.org/");
        String m3u8 = null;
        if (JsUnpacker.isPacked(html)) {
            String packed = JsUnpacker.extractPackedBlock(html);
            String js = packed == null ? null : JsUnpacker.unpack(packed);
            if (js != null) {
                Matcher m = M3U8.matcher(js);
                if (m.find()) m3u8 = m.group();
            }
        }
        if (m3u8 == null) {
            Matcher m = M3U8.matcher(html);
            if (m.find()) m3u8 = m.group();
        }
        if (m3u8 == null) throw new Exception("sin m3u8 en el embed");

        String master = get(m3u8, origen(embedUrl));
        if (!master.contains("#EXTM3U")) throw new Exception("master inválido");
        Log.d(TAG, "      master OK (" + master.length() + "B, "
                + (master.split("#EXT-X-STREAM-INF", -1).length - 1) + " variantes)");
        return m3u8;
    }

    /** AES-CBC, IV = 16 primeros bytes del binario descodificado en base64. */
    private static String descifrar(String base64, byte[] aesKey) throws Exception {
        byte[] raw = Base64.decode(base64, Base64.DEFAULT);
        if (raw.length <= 16) return null;
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, 0, 32, "AES"),
                new IvParameterSpec(raw, 0, 16));
        byte[] claro = c.doFinal(raw, 16, raw.length - 16);
        return new String(claro, StandardCharsets.UTF_8).trim();
    }

    /** Extrae el array JSON <code>dataLink = [ ... ]</code> respetando anidamientos. */
    private static String extraerDataLink(String html) {
        int i = html.indexOf("dataLink");
        if (i < 0) return null;
        int start = html.indexOf('[', i);
        if (start < 0) return null;
        int depth = 0;
        boolean enCadena = false, escapado = false;
        for (int j = start; j < html.length(); j++) {
            char c = html.charAt(j);
            if (enCadena) {
                if (escapado) escapado = false;
                else if (c == '\\') escapado = true;
                else if (c == '"') enCadena = false;
                continue;
            }
            if (c == '"') enCadena = true;
            else if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) return html.substring(start, j + 1);
            }
        }
        return null;
    }

    /** Primero vidhide (se descomprime con JsUnpacker), luego voe, después el resto. */
    private static JSONArray servidoresOrdenados(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr == null) return out;
        String[] preferencia = {"vidhide", "voe", "streamwish", "doodstream", "mixdrop", "filemoon"};
        for (String p : preferencia) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && p.equals(o.optString("servername", "").toLowerCase(Locale.US))) {
                    out.put(o);
                }
            }
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("servername", "").toLowerCase(Locale.US);
            boolean ya = false;
            for (String p : preferencia) if (p.equals(n)) ya = true;
            if (!ya) out.put(o);
        }
        return out;
    }

    /** 0 = LAT, 1 = ESP, 2 = otro, 3 = SUB. */
    private static int puntos(JSONObject o) {
        String l = o.optString("video_language", "").toUpperCase(Locale.US);
        if (l.contains("LAT")) return 0;
        if (l.contains("ESP") || l.contains("CAST")) return 1;
        if (l.contains("SUB")) return 3;
        return 2;
    }

    private static String origen(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getProtocol() + "://" + u.getHost() + "/";
        } catch (Exception e) {
            return "https://embed69.org/";
        }
    }

    private static String sinBarra(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] d = sha256(s);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String get(String url, String referer) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", referer)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "es-ES,es;q=0.9")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("HTTP " + resp.code() + " en " + url);
            }
            return resp.body().string();
        }
    }
}
