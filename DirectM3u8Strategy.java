package dza.folbol.BLABONGO;

public class DirectM3u8Strategy implements StreamResolverStrategy {
    @Override
    public void resolve(String url, StreamResolverCallback callback) {
        if (url != null && url.contains(".m3u8")) {
            callback.onStreamFound(new StreamResult(url));
        } else {
            callback.onError("No es .m3u8 directo");
        }
    }
}