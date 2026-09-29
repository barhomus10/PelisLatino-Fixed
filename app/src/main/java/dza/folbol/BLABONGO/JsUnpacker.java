package dza.folbol.BLABONGO;

/**
 * Descompresor "Dean Edwards" (el packer <code>eval(function(p,a,c,k,e,r){...})</code>)
 * que usan los reproductores tipo JW (vimeos.net, muchos clones de pelisplus...).
 *
 * Es Java puro (sin dependencias de Android): se puede compilar y probar con javac.
 *
 * Verificado 2026-09-28: la salida es IDENTICA a la de ejecutar el packer en Node
 * para el embed de vimeos.net.
 */
public final class JsUnpacker {

    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz";

    private JsUnpacker() { }

    /** true si el HTML contiene un bloque empaquetado. */
    public static boolean isPacked(String html) {
        return html != null && html.contains("eval(function(p,a,c,k,e");
    }

    /**
     * Extrae el bloque <code>eval(function(...)('...',62,123,'a|b|c'.split('|'),0,{}))</code>
     * completo, respetando el anidamiento de paréntesis.
     */
    public static String extractPackedBlock(String html) {
        if (html == null) return null;
        int idx = html.indexOf("eval(function(p,a,c,k,e");
        if (idx < 0) return null;
        int start = idx + 4;                       // justo después de "eval"
        int open = html.indexOf('(', start);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return html.substring(start, i + 1);
            }
        }
        return null;
    }

    /** Desempaqueta el bloque y devuelve el JavaScript original. */
    public static String unpack(String packed) {
        if (packed == null) return null;
        try {
            // --- localizar la lista de argumentos: ...}(ARGS)
            int brace = packed.indexOf('{', packed.indexOf("function("));
            if (brace < 0) return packed;
            int close = matchingBrace(packed, brace);
            if (close < 0) return packed;
            int argsOpen = packed.indexOf('(', close);
            if (argsOpen < 0) return packed;
            int argsClose = matchingParen(packed, argsOpen);
            if (argsClose < 0) return packed;
            String argsSrc = packed.substring(argsOpen + 1, argsClose);

            // --- parsear argumentos: 'payload', base, count, diccionario [, ...]
            Object[] args = parseArgs(argsSrc);
            if (args == null || args.length < 4) return packed;
            String payload = (String) args[0];
            int base = ((Number) args[1]).intValue();
            int count = ((Number) args[2]).intValue();
            String[] dict = (String[]) args[3];

            String out = payload;
            // while(c--) if(k[c]) p = p.replace(/\be(c)\b/g, k[c]);
            for (int i = count - 1; i >= 0; i--) {
                String word = (i < dict.length) ? dict[i] : null;
                if (word == null || word.isEmpty()) continue;
                String key = encode(i, base);
                out = replaceWord(out, key, word);
            }
            return out;
        } catch (Exception e) {
            return packed;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** e(c) del packer: representación en base <code>base</code> (36+ con mayúsculas). */
    static String encode(long n, int base) {
        StringBuilder out = new StringBuilder();
        while (true) {
            long rest = n % base;
            out.insert(0, rest > 35 ? (char) (rest + 29) : DIGITS.charAt((int) rest));
            n = n / base;
            if (n <= 0) break;
        }
        return out.toString();
    }

    /** Reemplaza <code>\bkey\b</code> por <code>word</code> (reemplazo literal). */
    static String replaceWord(String src, String key, String word) {
        if (key == null || key.isEmpty()) return src;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < src.length()) {
            int idx = src.indexOf(key, i);
            if (idx < 0) { out.append(src.substring(i)); break; }
            int end = idx + key.length();
            boolean before = idx == 0 || !isWordChar(src.charAt(idx - 1));
            boolean after = end >= src.length() || !isWordChar(src.charAt(end));
            if (before && after) {
                out.append(src, i, idx).append(word);
                i = end;
            } else {
                out.append(src, i, idx + 1);
                i = idx + 1;
            }
        }
        return out.toString();
    }

    /**
     * \b de JavaScript = [A-Za-z0-9_]. OJO: '$' NO cuenta como caracter de
     * palabra en JS, asi que en "$1d" si hay frontera antes del 1.
     */
    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Parsea la lista de argumentos del packer sin usar un intérprete JS. */
    private static Object[] parseArgs(String src) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < src.length() && out.size() < 6) {
            char c = src.charAt(i);
            if (c == ',' || c == ' ' || c == '\n' || c == '\r' || c == '\t') { i++; continue; }
            if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                char q = c;
                i++;
                while (i < src.length()) {
                    char d = src.charAt(i);
                    if (d == '\\' && i + 1 < src.length()) { sb.append(src.charAt(i + 1)); i += 2; continue; }
                    if (d == q) { i++; break; }
                    sb.append(d);
                    i++;
                }
                String value = sb.toString();
                // puede venir como 'a|b|c'.split('|')
                int splitIdx = src.indexOf(".split(", i);
                if (splitIdx >= 0 && splitIdx == i) {
                    int p1 = src.indexOf('(', splitIdx);
                    int p2 = src.indexOf(')', p1 + 1);
                    String sep = src.substring(p1 + 1, p2).replace("'", "").replace("\"", "").trim();
                    if (sep.isEmpty()) sep = "|";
                    out.add(splitEscaped(value, sep));
                    i = p2 + 1;
                } else {
                    // diccionario como cadena separada por |
                    out.add(value);
                }
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < src.length() && Character.isDigit(src.charAt(j))) j++;
                out.add(Long.parseLong(src.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '[') {   // diccionario como array literal
                int close = src.indexOf(']', i);
                if (close < 0) return null;
                String inner = src.substring(i + 1, close);
                java.util.List<String> items = new java.util.ArrayList<>();
                for (String piece : inner.split(",")) {
                    String t = piece.trim();
                    if (t.length() >= 2 && (t.startsWith("'") || t.startsWith("\""))) {
                        t = t.substring(1, t.length() - 1);
                    }
                    items.add(t);
                }
                out.add(items.toArray(new String[0]));
                i = close + 1;
                continue;
            }
            i++;   // cualquier otra cosa (0, {}, funciones...) la ignoramos
        }
        // normalizar: el cuarto elemento puede ser String (diccionario sin split)
        if (out.size() >= 4 && out.get(3) instanceof String) {
            out.set(3, splitEscaped((String) out.get(3), "|"));
        }
        return out.toArray();
    }

    private static String[] splitEscaped(String value, String sep) {
        if (sep.equals("|")) {
            return value.split("\\|", -1);
        }
        return value.split(java.util.regex.Pattern.quote(sep), -1);
    }
}
