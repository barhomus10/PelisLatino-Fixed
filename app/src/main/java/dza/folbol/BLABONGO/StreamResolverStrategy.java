package dza.folbol.BLABONGO;

public interface StreamResolverStrategy {
    void resolve(String url, StreamResolverCallback callback);
}