package dza.folbol.BLABONGO;

import android.util.Base64;
import android.util.Log;

public class Base64DecodeStrategy implements StreamResolverStrategy {
    private static final String TAG = "Base64Decode";

    @Override
    public void resolve(String url, StreamResolverCallback callback) {
        if (url != null && url.contains("?r=")) {
            String encoded = url.substring(url.indexOf("?r=") + 3);
            while (encoded.length() % 4 != 0) encoded += "=";
            try {
                byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                String decodedUrl = new String(decoded, "UTF-8");
                if (decodedUrl.contains(".m3u8")) {
                    callback.onStreamFound(new StreamResult(decodedUrl));
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error decodificando: " + e.getMessage());
            }
        }
        callback.onError("No es URL con ?r=");
    }
}