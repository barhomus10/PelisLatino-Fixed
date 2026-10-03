package dza.folbol.BLABONGO;

public interface StreamResolverCallback {
    void onStreamFound(StreamResult result);
    void onError(String error);
}