package com.nekkora.client.pack;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura ESTATICA de um {@code requirements.txt} (AGENT-CLIENT-PREPARACAO-002 T04): so texto. Nada e instalado, nada e
 * resolvido na rede, nenhum {@code setup.py} roda, nenhum arquivo incluido ({@code -r}, {@code -c}) e nenhum endereco remoto sao
 * seguidos.
 *
 * <p>O manifesto do pacote so representa {@code nome==versao} (com extras opcionais), sem repetir. Por isso cada linha do
 * arquivo recebe uma situacao: ou e uma dependencia exata, ou e dita POR LINHA como algo que o pacote nao representa (faixa,
 * sem versao, marcador de ambiente, endereco remoto, caminho local, opcao do pip, hash, inclusao de outro arquivo). Nenhuma linha e
 * descartada em silencio, nenhuma versao e inventada, e um arquivo com problema nunca e tratado como "sem dependencias".
 */
public final class RequirementsFile {
    private RequirementsFile() {}

    /** Tamanho maximo lido. */
    public static final int MAX_BYTES = 1024 * 1024;

    /** A situacao de uma linha. */
    public enum Kind {
        EXACT("dependência com versão exata", false),
        /** A mesma dependencia, na mesma versao, outra vez: usada uma vez so. */
        DUPLICATE("repetida com a mesma versão: usada uma vez", false),
        CONFLICT("a mesma dependência aparece com versões ou extras diferentes: deixe uma só", true),
        RANGE("faixa de versões: o pacote exige a versão exata (nome==1.2.3)", true),
        NO_VERSION("sem versão: o pacote exige a versão exata (nome==1.2.3)", true),
        MARKER("marcador de ambiente (texto depois de ';'): o pacote não o representa", true),
        REMOTE("instalação por endereço remoto ou repositório: o pacote não a representa", true),
        LOCAL_PATH("caminho local ou arquivo: o pacote não o representa", true),
        INCLUDE("inclui outro arquivo (-r ou -c): o aplicativo não segue inclusões; traga as linhas para este arquivo", true),
        OPTION("opção do pip: o pacote não a representa", true),
        HASH("fixa um hash (--hash): o pacote não o registra", true),
        UNKNOWN("linha não reconhecida", true);

        private final String label;
        private final boolean problem;

        Kind(String label, boolean problem) {
            this.label = label;
            this.problem = problem;
        }

        public String label() {
            return label;
        }

        /** A linha impede o uso do arquivo como origem das dependencias ate ser ajustada. */
        public boolean problem() {
            return problem;
        }
    }

    /**
     * Uma linha com conteudo (comentarios e linhas em branco nao aparecem).
     *
     * @param number numero da linha no arquivo (a primeira, quando a linha continua com {@code \})
     * @param text a linha como esta no arquivo, sem o comentario, ja saneada para a tela
     * @param dependency {@code nome[extras]==versao} quando a linha e uma dependencia exata; senao {@code null}
     */
    public record Line(int number, String text, Kind kind, String dependency) {
        public boolean problem() {
            return kind.problem();
        }
    }

    /**
     * O arquivo lido.
     *
     * @param lines as linhas com conteudo, na ordem
     * @param dependencies as dependencias exatas, sem repeticao, na ordem em que aparecem
     */
    public record Parsed(List<Line> lines, List<String> dependencies) {
        public Parsed {
            lines = List.copyOf(lines);
            dependencies = List.copyOf(dependencies);
        }

        public List<Line> problems() {
            return lines.stream().filter(Line::problem).toList();
        }

        /** Todas as linhas sao representaveis: a lista pode ir para o manifesto como esta. */
        public boolean resolved() {
            return lines.stream().noneMatch(Line::problem);
        }

        /** O arquivo nao declara nada (so comentarios ou vazio). Nao quer dizer que o projeto nao usa bibliotecas. */
        public boolean empty() {
            return lines.isEmpty();
        }
    }

    private static final Pattern EXACT = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})\\s*(\\[\\s*[A-Za-z0-9,._\\s-]{1,80}])?\\s*==\\s*([0-9][0-9A-Za-z.+!-]{0,40})");
    private static final Pattern NAME_ONLY = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})\\s*(\\[[^\\]]*])?");
    private static final Pattern NAME_THEN_SPEC = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})\\s*(\\[[^\\]]*])?\\s*(===|==|~=|!=|>=|<=|>|<).*");
    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:[\\\\/].*");

    /** Decodifica como UTF-8 estrito (com ou sem BOM). Vazio: nao e texto UTF-8 ou passa do limite. */
    public static Optional<String> decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) return Optional.empty();
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (s.indexOf('\u0000') >= 0) return Optional.empty();
            return Optional.of(s.startsWith("﻿") ? s.substring(1) : s);
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    public static Parsed parse(String text) {
        List<Line> lines = new ArrayList<>();
        String[] raw = (text == null ? "" : text).split("\\r?\\n|\\r", -1);
        for (int i = 0; i < raw.length; i++) {
            int number = i + 1;
            StringBuilder logical = new StringBuilder();
            // continuacao de linha do pip: "\" no fim junta com a proxima
            while (true) {
                String part = raw[i];
                if (part.endsWith("\\") && i + 1 < raw.length) {
                    logical.append(part, 0, part.length() - 1).append(' ');
                    i++;
                } else {
                    logical.append(part);
                    break;
                }
            }
            String content = stripComment(logical.toString()).strip();
            if (content.isEmpty()) continue;
            lines.add(classify(number, content));
        }
        return dedupe(lines);
    }

    /** Comentario do pip: {@code #} no comeco da linha ou depois de um espaco (um {@code #} dentro de um endereco nao e comentario). */
    private static String stripComment(String line) {
        if (line.stripLeading().startsWith("#")) return "";
        for (int i = 1; i < line.length(); i++) {
            if (line.charAt(i) == '#' && Character.isWhitespace(line.charAt(i - 1))) return line.substring(0, i);
        }
        return line;
    }

    private static Line classify(int number, String content) {
        String shown = ZipNames.display(content.length() > 200 ? content.substring(0, 200) + "…" : content);
        String low = content.toLowerCase(Locale.ROOT);
        if (low.startsWith("-r ") || low.startsWith("-r\t") || low.startsWith("--requirement") || low.startsWith("-c ") || low.startsWith("-c\t")
                || low.startsWith("--constraint") || low.equals("-r") || low.equals("-c")) {
            return new Line(number, shown, Kind.INCLUDE, null);
        }
        if (low.startsWith("-e ") || low.startsWith("-e\t") || low.startsWith("--editable")) return new Line(number, shown, Kind.LOCAL_PATH, null);
        if (content.startsWith("-")) return new Line(number, shown, Kind.OPTION, null);
        if (low.contains("--hash")) return new Line(number, shown, Kind.HASH, null);
        if (low.contains("://") || low.startsWith("git+") || content.contains(" @ ") || content.matches("[^\\s;]+@\\S+.*") && content.contains("/")) {
            return new Line(number, shown, Kind.REMOTE, null);
        }
        if (content.startsWith(".") || content.startsWith("/") || content.startsWith("\\") || content.startsWith("~") || DRIVE.matcher(content).matches()
                || low.endsWith(".whl") || low.endsWith(".tar.gz") || low.endsWith(".zip")) {
            return new Line(number, shown, Kind.LOCAL_PATH, null);
        }
        if (content.contains(";")) return new Line(number, shown, Kind.MARKER, null);
        if (low.contains(" --") || content.matches(".*\\s-[A-Za-z]\\b.*")) return new Line(number, shown, Kind.OPTION, null);
        Matcher exact = EXACT.matcher(content);
        if (exact.matches()) {
            String extras = exact.group(2) == null ? "" : exact.group(2).replaceAll("\\s+", "");
            String dependency = exact.group(1) + extras + "==" + exact.group(3);
            if (ExecutionPackage.validDependency(dependency)) return new Line(number, shown, Kind.EXACT, dependency);
            return new Line(number, shown, Kind.UNKNOWN, null);
        }
        if (NAME_ONLY.matcher(content).matches()) return new Line(number, shown, Kind.NO_VERSION, null);
        if (NAME_THEN_SPEC.matcher(content).matches()) return new Line(number, shown, Kind.RANGE, null);
        return new Line(number, shown, Kind.UNKNOWN, null);
    }

    /** Mesmo nome (normalizado): igual em tudo, a segunda e so "repetida"; diferente em versao ou extras, as duas sao conflito. */
    private static Parsed dedupe(List<Line> lines) {
        Map<String, Integer> first = new LinkedHashMap<>();
        Map<String, String> canonical = new LinkedHashMap<>();
        Line[] out = lines.toArray(new Line[0]);
        java.util.Set<String> conflicted = new java.util.HashSet<>();
        for (int i = 0; i < out.length; i++) {
            Line l = out[i];
            if (l.kind() != Kind.EXACT) continue;
            String key = key(l.dependency());
            String same = sameness(l.dependency());
            if (!first.containsKey(key)) {
                first.put(key, i);
                canonical.put(key, same);
            } else if (canonical.get(key).equals(same) && !conflicted.contains(key)) {
                out[i] = new Line(l.number(), l.text(), Kind.DUPLICATE, null);
            } else {
                conflicted.add(key);
                out[i] = new Line(l.number(), l.text(), Kind.CONFLICT, null);
                Line f = out[first.get(key)];
                out[first.get(key)] = new Line(f.number(), f.text(), Kind.CONFLICT, null);
            }
        }
        List<String> dependencies = new ArrayList<>();
        for (Line l : out) {
            if (l.kind() == Kind.EXACT) dependencies.add(l.dependency());
        }
        return new Parsed(List.of(out), dependencies);
    }

    private static String key(String dependency) {
        return ExecutionPackage.normalized(dependency.replaceAll("\\[.*", "").split("==", 2)[0]);
    }

    /** Nome normalizado + extras (sem caixa, ordenados) + versao: duas linhas com isto igual sao a mesma declaracao. */
    private static String sameness(String dependency) {
        String[] nv = dependency.split("==", 2);
        String extras = "";
        int at = nv[0].indexOf('[');
        if (at >= 0) {
            List<String> e = new ArrayList<>(List.of(nv[0].substring(at + 1, nv[0].length() - 1).toLowerCase(Locale.ROOT).split(",")));
            e.sort(null);
            extras = String.join(",", e);
        }
        return key(dependency) + "[" + extras + "]==" + nv[1];
    }
}
