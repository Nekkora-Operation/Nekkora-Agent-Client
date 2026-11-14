package com.nekkora.client.pack;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pacote local inspecionado: o que e, o que precisa e a sua IMPRESSAO DIGITAL. Este aplicativo aceita UM formato: o
 * pacote Python externo ({@code nekkora-external.json}, esquema {@code nekkora-agent/external-python-package}, regra de
 * impressao {@code nekkora-agent/external-python-fingerprint/1}). Pacote do Studio ({@code manifest.json}) e de
 * laboratorio ({@code nekkora-agent-lab.json}) sao RECUSADOS com {@link ErrorCode#EXEC_PACKAGE_UNSUPPORTED}.
 *
 * <p>O descritor diz como o pacote foi gerado e o que exige; ele NAO autoriza a execucao (a aprovacao e um ato da pessoa,
 * por impressao digital). So os arquivos listados contam e so eles sao copiados.
 *
 * <p>PROCEDENCIA: o formato nao prova quem gerou o pacote (o descritor nao e assinado). O campo {@code generator} e
 * guardado so como DECLARACAO do pacote ({@link #declaredGenerator()}) e a interface o mostra como nao verificado.
 *
 * <p>Portado de {@code com.nekkora.agent.exec.ExecutionPackage} (Nekkora-Agent 9b1e7e1): formato do descritor, hash por
 * arquivo, texto da regra da impressao e regra dos arquivos proibidos sao os mesmos, byte a byte.
 *
 * @param dir pasta conferida (caminho real)
 * @param name nome DECLARADO no descritor
 * @param files caminho relativo para hash ({@code sha256:...}), em ordem
 * @param fingerprint impressao digital {@code sha256:...}
 * @param entry o {@code .py} de entrada
 * @param generator gerador declarado, ou {@code null}
 * @param pythonRequirement versao de Python exigida, no formato do descritor
 * @param dependencies dependencias com versao exata
 * @param packageVersion versao DECLARADA do pacote
 */
public record ExecutionPackage(Path dir, String name, Map<String, String> files, String fingerprint, String entry, String generator,
                               String pythonRequirement, List<String> dependencies, String packageVersion) {

    /** O unico tipo aceito. O nome do valor entra no texto da impressao digital: nao pode mudar. */
    public enum Kind { EXTERNAL_PYTHON }

    /** Nomes reservados dos formatos NAO aceitos: reconhecidos so para recusar com um motivo claro. */
    public static final String STUDIO_MANIFEST = "manifest.json";
    public static final String STUDIO_SCHEMA = "nekkora-studio/codegen-manifest";
    public static final String LAB_DESCRIPTOR = "nekkora-agent-lab.json";
    public static final String LAB_SCHEMA = "nekkora-agent/lab-package";
    /** Pacote Python externo (sem framework Nekkora) e a regra PROPRIA da impressao dele. */
    public static final String EXTERNAL_DESCRIPTOR = "nekkora-external.json";
    public static final String EXTERNAL_SCHEMA = "nekkora-agent/external-python-package";
    public static final String EXTERNAL_RULE = "nekkora-agent/external-python-fingerprint/1";
    private static final Pattern EXTERNAL_PYTHON_REQ = Pattern.compile("(>=|==|~=)([0-9]{1,2})\\.([0-9]{1,2})(\\.\\*)?(,<([0-9]{1,2})\\.([0-9]{1,2}))?");
    private static final Pattern EXTERNAL_DEP = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})(\\[[A-Za-z0-9,._-]{1,80}])?==([0-9][0-9A-Za-z.+!-]{0,40})");
    static final int MAX_FILES = 500;
    static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Pattern SHA = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    /** Gerador declarado exibivel (ex.: {@code nekkora-agent-assistente/1.0.0}); fora disso nao e mostrado. */
    private static final Pattern GENERATOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,79}");

    public ExecutionPackage {
        files = java.util.Collections.unmodifiableMap(new TreeMap<>(files));
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    public Kind kind() {
        return Kind.EXTERNAL_PYTHON;
    }

    /** As 12 primeiras posicoes da impressao digital, para a interface. */
    public String shortFingerprint() {
        return shortFingerprint(fingerprint);
    }

    public static String shortFingerprint(String fingerprint) {
        return fingerprint.substring("sha256:".length(), "sha256:".length() + 12);
    }

    /** O texto tem o formato de uma impressao digital ({@code sha256:} + 64 hexadecimais minusculos)? */
    public static boolean validFingerprint(String fingerprint) {
        return fingerprint != null && FINGERPRINT.matcher(fingerprint).matches();
    }

    /** Le e confere a pasta. Qualquer problema: {@link ExecRefused} com o codigo da lista fechada. */
    public static ExecutionPackage inspect(Path folder) throws ExecRefused {
        Path dir;
        try {
            dir = folder.toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_NOT_FOUND);
        }
        if (!Files.isDirectory(dir)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_NOT_FOUND);
        Path studio = dir.resolve(STUDIO_MANIFEST);
        Path lab = dir.resolve(LAB_DESCRIPTOR);
        Path external = dir.resolve(EXTERNAL_DESCRIPTOR);
        if (Files.isRegularFile(external, LinkOption.NOFOLLOW_LINKS)) {
            // um pacote nao passa por dois tipos
            if (Files.exists(studio, LinkOption.NOFOLLOW_LINKS) || Files.exists(lab, LinkOption.NOFOLLOW_LINKS)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
            return external(dir, read(external));
        }
        // formatos que existem e que este aplicativo NAO aceita: recusa com motivo proprio, nunca aceitacao silenciosa
        if (declares(lab, LAB_SCHEMA) || declares(studio, STUDIO_SCHEMA)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSUPPORTED);
        throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNKNOWN);
    }

    /** O arquivo da raiz e um descritor do formato {@code schema}? (um {@code manifest.json} de outra ferramenta nao e) */
    private static boolean declares(Path descriptor, String schema) {
        try {
            if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS) || Files.size(descriptor) > JsonReader.MAX_CHARS) return false;
            return schema.equals(JsonReader.text(JsonReader.object(Files.readString(descriptor, StandardCharsets.UTF_8)), "schema"));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return false;
        }
    }

    private static Map<String, Object> read(Path descriptor) throws ExecRefused {
        try {
            if (Files.size(descriptor) > JsonReader.MAX_CHARS) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
            return JsonReader.object(Files.readString(descriptor, StandardCharsets.UTF_8));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
    }

    /**
     * Pacote Python externo. Descritor 1.0.x com nome, versao do projeto, entrada relativa {@code .py} listada, versao de
     * Python, dependencias com versao EXATA e os arquivos com hash. Ambiente virtual, credenciais e cache nunca entram
     * (recusa). O pacote nao pede segredo nem framework: o processo roda com o Python preparado pela pessoa.
     */
    private static ExecutionPackage external(Path dir, Map<String, Object> m) throws ExecRefused {
        String version = JsonReader.text(m, "version");
        if (!EXTERNAL_SCHEMA.equals(JsonReader.text(m, "schema")) || version == null || !version.matches("1\\.0\\.[0-9]{1,3}")) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
        String name = JsonReader.text(m, "name");
        String pkgVersion = JsonReader.text(m, "packageVersion");
        String entry = JsonReader.text(m, "entry");
        String python = JsonReader.text(m, "python");
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") || pkgVersion == null || !pkgVersion.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,6}")
                || python == null || !EXTERNAL_PYTHON_REQ.matcher(python).matches()) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
        List<?> deps = JsonReader.list(m, "dependencies");
        if (deps == null || deps.size() > 200) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        List<String> dependencies = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object d : deps) {
            Matcher dm = d instanceof String s ? EXTERNAL_DEP.matcher(s) : null;
            if (dm == null || !dm.matches() || !seen.add(normalized(dm.group(1)))) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
            dependencies.add((String) d);
        }
        Map<String, String> files = files(JsonReader.obj(m, "files"));
        if (files.containsKey(EXTERNAL_DESCRIPTOR) || files.containsKey(STUDIO_MANIFEST) || files.containsKey(LAB_DESCRIPTOR)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
        for (String rel : files.keySet()) if (forbidden(rel)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
        if (entry == null || !entry.endsWith(".py") || !entry.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}") || entry.contains("..") || entry.startsWith("/")) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE); // entrada fora da raiz
        }
        if (!files.containsKey(entry)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        verify(dir, files);
        return new ExecutionPackage(dir, name, files, fingerprint(dir, files), entry, generator(m), python, dependencies, pkgVersion);
    }

    /** Nome de distribuicao normalizado (PEP 503): comparacao de dependencias sem diferenca de caixa, "_" ou ".". */
    public static String normalized(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace('_', '-').replace('.', '-');
    }

    /** Nunca no pacote externo: ambiente virtual, cache, controle de versao, credenciais. A mesma regra vale no empacotador. */
    public static boolean forbidden(String rel) {
        for (String seg : rel.split("/")) {
            String l = seg.toLowerCase(java.util.Locale.ROOT);
            if (List.of(".venv", "venv", "env", "__pycache__", ".git", ".hg", ".svn", ".idea", ".vscode", "node_modules").contains(l) || l.startsWith(".env")
                    || l.endsWith(".pem") || l.endsWith(".key") || l.endsWith(".pfx") || l.endsWith(".p12") || l.startsWith("id_rsa") || l.startsWith("id_ed25519")
                    || l.equals(".netrc") || l.equals(".pypirc") || l.endsWith(".pyc")) {
                return true;
            }
        }
        return false;
    }

    /** {@code python} no formato do descritor externo (o mesmo que {@link #inspect} aceita). */
    public static boolean validPythonRequirement(String requirement) {
        return requirement != null && EXTERNAL_PYTHON_REQ.matcher(requirement).matches();
    }

    /** Dependencia no formato do descritor externo ({@code nome==versao}, extras opcionais). */
    public static boolean validDependency(String dependency) {
        return dependency != null && EXTERNAL_DEP.matcher(dependency).matches();
    }

    /** {@code python} do descritor ({@code >=3.10,<3.14}, {@code ==3.12.*}, {@code ~=3.11}) atende a versao {@code major.minor}? */
    public static boolean pythonSatisfies(String requirement, int major, int minor) {
        Matcher m = EXTERNAL_PYTHON_REQ.matcher(requirement == null ? "" : requirement);
        if (!m.matches()) return false;
        int rM = Integer.parseInt(m.group(2));
        int rm = Integer.parseInt(m.group(3));
        int cmp = Integer.compare(major * 100 + minor, rM * 100 + rm);
        boolean ok = switch (m.group(1)) {
            case ">=" -> cmp >= 0;
            case "==" -> cmp == 0;
            default -> major == rM && cmp >= 0; // ~=X.Y: mesma versao maior, menor >= Y
        };
        if (ok && m.group(5) != null) ok = major * 100 + minor < Integer.parseInt(m.group(6)) * 100 + Integer.parseInt(m.group(7));
        return ok;
    }

    private static Map<String, String> files(Map<String, Object> declared) throws ExecRefused {
        if (declared == null || declared.isEmpty()) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        if (declared.size() > MAX_FILES) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : declared.entrySet()) {
            String rel = e.getKey();
            if (!(e.getValue() instanceof String sha) || !SHA.matcher(sha).matches()) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
            // caminho relativo simples, com barras normais, sem subir de pasta nem ser absoluto
            if (!rel.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}") || rel.contains("..") || rel.contains("//") || rel.startsWith("/")) {
                throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
            }
            out.put(rel, sha);
        }
        return out;
    }

    /** Confere cada arquivo listado: dentro da pasta, arquivo comum (sem ligacao), dentro dos limites e com o hash. */
    public static void verify(Path dir, Map<String, String> files) throws ExecRefused {
        long total = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path f = dir.resolve(e.getKey()).normalize();
            if (!f.startsWith(dir)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
            try {
                if (Files.isSymbolicLink(f) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || !f.toRealPath().startsWith(dir)) {
                    throw new ExecRefused(Files.exists(f, LinkOption.NOFOLLOW_LINKS) ? ErrorCode.EXEC_PACKAGE_UNSAFE : ErrorCode.EXEC_PACKAGE_CHANGED);
                }
                total += Files.size(f);
                if (total > MAX_BYTES) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
                if (!e.getValue().equals(fileHash(Files.readAllBytes(f)))) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
            } catch (IOException | RuntimeException ex) {
                throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
            }
        }
    }

    /**
     * Hash de um arquivo do pacote: sobre o TEXTO UTF-8 com LF (um checkout do Windows pode ter gravado CRLF). Conteudo
     * que nao e UTF-8 valido entra em bytes. Uma unica regra para o validador e para o empacotador.
     */
    public static String fileHash(byte[] data) {
        byte[] bytes = data;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data)).toString();
            bytes = text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            // binario: os bytes como estao
        }
        return "sha256:" + HexFormat.of().formatHex(sha256(bytes));
    }

    /** Impressao digital pela pasta: regra + tipo + descritor + cada arquivo e hash (ordem fixa). Qualquer arquivo mudado a muda. */
    public static String fingerprint(Path dir, Map<String, String> files) throws ExecRefused {
        try {
            return canonical(fileHash(Files.readAllBytes(dir.resolve(EXTERNAL_DESCRIPTOR))), files);
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_INVALID);
        }
    }

    /**
     * A impressao do pacote externo a partir dos BYTES do descritor e dos hashes listados: a MESMA conta de
     * {@link #fingerprint}, para o empacotador calcular antes de gravar (e conferir depois, pela pasta).
     */
    public static String externalFingerprint(byte[] descriptor, Map<String, String> files) {
        return canonical(fileHash(descriptor), files);
    }

    private static String canonical(String descriptorHash, Map<String, String> files) {
        // a primeira linha e a regra; a segunda, o tipo: os dois textos sao os do Nekkora Agent, sem mudanca
        StringBuilder b = new StringBuilder(EXTERNAL_RULE).append('\n').append(Kind.EXTERNAL_PYTHON).append('\n');
        b.append(EXTERNAL_DESCRIPTOR).append('\t').append(descriptorHash).append('\n');
        new TreeMap<>(files).forEach((k, v) -> b.append(k).append('\t').append(v).append('\n'));
        return "sha256:" + HexFormat.of().formatHex(sha256(b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O {@code generator} que o descritor DECLARA (nao verificado); vazio se ausente ou fora do formato exibivel. */
    public Optional<String> declaredGenerator() {
        return Optional.ofNullable(generator);
    }

    private static String generator(Map<String, Object> m) {
        String g = JsonReader.text(m, "generator");
        return g != null && GENERATOR.matcher(g).matches() ? g : null;
    }
}
