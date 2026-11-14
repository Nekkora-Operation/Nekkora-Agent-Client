package com.nekkora.client.pack;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O requisito de Python de um pacote externo: a UNICA leitura do campo {@code python} do {@code nekkora-external.json}
 * (validador, empacotador, executor e formulario usam esta classe; ninguem valida o texto por conta propria).
 *
 * <p>Formas do descritor:
 * <ul>
 *   <li>1.0: {@code >=X.Y}, {@code ==X.Y}, {@code ==X.Y.*}, {@code ~=X.Y}, cada uma com {@code ,<X.Y} opcional. Comparam
 *       so a versao maior e a menor;</li>
 *   <li>1.1 (AGENT-CLIENT-PREPARACAO-002): alem dessas, {@code ==X.Y.Z} - uma versao EXATA, comparada inteira.</li>
 * </ul>
 * O requisito e OPCIONAL a partir do descritor 1.1: ausente quer dizer "o autor nao declarou restricao", nunca "todos os
 * Pythons foram testados". Sem requisito, o ambiente ainda precisa responder a sondagem do executor e ter as dependencias
 * declaradas.
 *
 * <p>O que a pessoa digita no formulario passa por {@link #interpret}: {@code 3.12} vira a familia {@code ==3.12.*},
 * {@code 3.12.0} vira a versao exata {@code ==3.12.0}, e um especificador do descritor fica como esta. A interpretacao e
 * mostrada antes de gerar; texto que nao e nenhuma dessas formas e recusado (nunca trocado por um padrao).
 */
public final class PythonRequirement {

    /** As formas do descritor 1.0 (texto igual ao do Nekkora Agent 9b1e7e1 e ao do validador da Platform). */
    private static final Pattern RANGE = Pattern.compile("(>=|==|~=)([0-9]{1,2})\\.([0-9]{1,2})(\\.\\*)?(,<([0-9]{1,2})\\.([0-9]{1,2}))?");
    /** Versao exata, so no descritor 1.1. */
    private static final Pattern EXACT = Pattern.compile("==([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{1,3})");
    private static final Pattern TYPED_FAMILY = Pattern.compile("([0-9]{1,2})\\.([0-9]{1,2})(\\.\\*)?");
    private static final Pattern TYPED_EXACT = Pattern.compile("([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{1,3})");

    private final String text;
    private final String operator;
    private final int major;
    private final int minor;
    private final int patch;
    private final int upperMajor;
    private final int upperMinor;

    private PythonRequirement(String text, String operator, int major, int minor, int patch, int upperMajor, int upperMinor) {
        this.text = text;
        this.operator = operator;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.upperMajor = upperMajor;
        this.upperMinor = upperMinor;
    }

    /** Le o texto do descritor. Vazio: nao e nenhuma das formas aceitas. */
    public static Optional<PythonRequirement> parse(String text) {
        if (text == null) return Optional.empty();
        Matcher e = EXACT.matcher(text);
        if (e.matches()) {
            return Optional.of(new PythonRequirement(text, "==", Integer.parseInt(e.group(1)), Integer.parseInt(e.group(2)), Integer.parseInt(e.group(3)), -1, -1));
        }
        Matcher m = RANGE.matcher(text);
        if (!m.matches()) return Optional.empty();
        boolean upper = m.group(5) != null;
        return Optional.of(new PythonRequirement(text, m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), -1,
                upper ? Integer.parseInt(m.group(6)) : -1, upper ? Integer.parseInt(m.group(7)) : -1));
    }

    /** O texto e um requisito do descritor (1.0 ou 1.1)? */
    public static boolean valid(String text) {
        return parse(text).isPresent();
    }

    /** O texto, como vai no descritor. */
    public String text() {
        return text;
    }

    /** Versao exata ({@code ==X.Y.Z}): so existe a partir do descritor 1.1. */
    public boolean exact() {
        return patch >= 0;
    }

    /** A versao {@code major.minor.patch} atende? */
    public boolean accepts(int vMajor, int vMinor, int vPatch) {
        if (exact()) return vMajor == major && vMinor == minor && vPatch == patch;
        int cmp = Integer.compare(vMajor * 100 + vMinor, major * 100 + minor);
        boolean ok = switch (operator) {
            case ">=" -> cmp >= 0;
            case "==" -> cmp == 0;
            default -> vMajor == major && cmp >= 0; // ~=X.Y: mesma versao maior, menor >= Y
        };
        if (ok && upperMajor >= 0) ok = vMajor * 100 + vMinor < upperMajor * 100 + upperMinor;
        return ok;
    }

    /** {@code version}: {@code X.Y.Z} (o que a sondagem do interpretador responde). Texto fora disso nunca atende. */
    public boolean accepts(String version) {
        if (version == null || !version.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) return false;
        String[] v = version.split("\\.");
        return accepts(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]));
    }

    /** O requisito em palavras, para a tela. */
    public String meaning() {
        String base;
        if (exact()) {
            base = "exatamente o Python " + major + "." + minor + "." + patch;
        } else {
            base = switch (operator) {
                case ">=" -> "Python " + major + "." + minor + " ou mais novo";
                case "==" -> "qualquer Python " + major + "." + minor + " (" + major + "." + minor + ".x)";
                default -> "Python " + major + "." + minor + " ou mais novo, dentro da série " + major + ".x";
            };
        }
        return upperMajor < 0 ? base : base + ", abaixo do " + upperMajor + "." + upperMinor;
    }

    /**
     * O que a pessoa digitou no formulario.
     *
     * @param requirement o requisito entendido; vazio com {@code valid}: nada digitado (sem restricao declarada)
     * @param valid falso: o texto nao e uma forma aceita e precisa ser corrigido ou apagado
     */
    public record Typed(Optional<PythonRequirement> requirement, boolean valid) {
        /** O texto do descritor, ou {@code null} sem restricao declarada. */
        public String text() {
            return requirement.map(PythonRequirement::text).orElse(null);
        }
    }

    /**
     * Interpreta o texto digitado. Em branco: sem restricao. {@code 3.12} ou {@code 3.12.*}: a familia
     * {@code ==3.12.*}. {@code 3.12.0}: a versao exata {@code ==3.12.0}. Um especificador do descritor (com ou sem
     * espacos) fica como esta. Qualquer outra coisa: invalido.
     */
    public static Typed interpret(String typed) {
        String t = typed == null ? "" : typed.replaceAll("\\s+", "");
        if (t.isEmpty()) return new Typed(Optional.empty(), true);
        Matcher exact = TYPED_EXACT.matcher(t);
        if (exact.matches()) return of("==" + t);
        Matcher family = TYPED_FAMILY.matcher(t);
        if (family.matches()) return of("==" + family.group(1) + "." + family.group(2) + ".*");
        return of(t);
    }

    private static Typed of(String text) {
        Optional<PythonRequirement> r = parse(text);
        return new Typed(r, r.isPresent());
    }

    @Override
    public String toString() {
        return text;
    }
}
