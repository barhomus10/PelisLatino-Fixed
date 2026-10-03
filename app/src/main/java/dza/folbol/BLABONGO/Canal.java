package dza.folbol.BLABONGO;

import java.io.Serializable;
import java.util.List;

public class Canal implements Serializable {
    // Se recomienda agregar serialVersionUID para evitar advertencias
    private static final long serialVersionUID = 1L;

    public String title;
    public String image;
    public String category;
    public List<OpcionCanal> options;

    public static class OpcionCanal implements Serializable {
        private static final long serialVersionUID = 1L;
        public String label;
        public String url;
    }

    public static class RespuestaCanales {
        public List<Canal> canales;
    }
}