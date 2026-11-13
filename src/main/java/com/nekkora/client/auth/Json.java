package com.nekkora.client.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor JSON minimo e estrito para as respostas pequenas da API da entrada (o aplicativo nao leva biblioteca de JSON).
 * Objetos viram {@link Map}, listas {@link List}, textos {@link String}, numeros {@link Double}, e ha {@code true},
 * {@code false} e {@code null}. Qualquer coisa fora da gramatica, chave repetida ou sobra depois do valor:
 * {@link IllegalArgumentException} - resposta malformada nunca e interpretada "pelo que deu para ler".
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        if (text == null || text.length() > 16_384) {
            throw new IllegalArgumentException("json");
        }
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("json");
        }
        return v;
    }

    /** O objeto da raiz, ou erro se a raiz nao for um objeto. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("json");
        }
        return (Map<String, Object>) v;
    }

    private Object value(int depth) {
        if (depth > 8 || i >= s.length()) {
            throw new IllegalArgumentException("json");
        }
        char c = s.charAt(i);
        if (c == '{') {
            return object(depth);
        }
        if (c == '[') {
            return array(depth);
        }
        if (c == '"') {
            return string();
        }
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (!peek('"')) {
                throw new IllegalArgumentException("json");
            }
            String k = string();
            ws();
            expect(':');
            ws();
            if (m.containsKey(k)) {
                throw new IllegalArgumentException("json");
            }
            m.put(k, value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return l;
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) {
                throw new IllegalArgumentException("json");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("json");
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw new IllegalArgumentException("json");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw new IllegalArgumentException("json");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw new IllegalArgumentException("json");
                    }
                    i += 4;
                }
                default -> throw new IllegalArgumentException("json");
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String n = s.substring(start, i);
        if (!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException("json");
        }
        return Double.valueOf(n);
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new IllegalArgumentException("json");
        }
        i++;
    }
}
