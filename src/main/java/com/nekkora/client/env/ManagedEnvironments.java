package com.nekkora.client.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nekkora.client.pack.ExecutionPackage;
import com.nekkora.client.pack.JsonReader;
import com.nekkora.client.run.PythonProbe;
import com.nekkora.client.run.SafeTree;

/**
 * AMBIENTE-PYTHON-001 - o AMBIENTE GERENCIADO POR EXECUCAO (contrato, secao 4). E a UNICA camada do aplicativo que cria um
 * ambiente virtual e chama o {@code pip}; o Python da maquina nunca recebe nada: o ambiente e temporario, criado A PARTIR de uma
 * instalacao do computador, e apagado depois.
 *
 * <ul>
 * <li>{@link #resolve}: a lista EFETIVA de dependencias, com as indiretas, so com wheels, no indice publico, para o Python e o
 * sistema desta maquina. Um ambiente temporario em {@code remota\resolucoes\<id>\venv}, o {@code pip} dele com
 * {@code --dry-run --report}; o lock (nome, versao, SHA-256 e arquivo) sai do relatorio. A pasta e apagada sempre;</li>
 * <li>{@link #prepare}: o ambiente da tentativa em {@code remota\tentativas\<t>\ambiente}, com EXATAMENTE o lock aceito
 * ({@code --no-deps --require-hashes --only-binary=:all:}), conferido (versoes instaladas iguais as do lock e a lista efetiva
 * satisfeita) e com as verificacoes externas pedidas (Playwright: o navegador existe; nada e instalado);</li>
 * <li>o cache do {@code pip} ({@code remota\cache-pip}), com limite configuravel e poda dos arquivos mais antigos.</li>
 * </ul>
 *
 * <p>Todo processo e lancado por {@link EnvCommand}: lista de argumentos, {@code -I}, ambiente reduzido, grupo de processos proprio.
 * O {@code pip} roda sempre com {@code --isolated} (sem configuracao pessoal, sem variaveis {@code PIP_*}) e {@code --no-input}.
 * Proxy: este aplicativo nao tem configuracao de proxy; o {@code pip} vai direto ao indice. Nada aqui fala com a Platform.
 */
public final class ManagedEnvironments {

    /** O unico indice do ambiente gerenciado (D3). */
    public static final String PYPI = "https://pypi.org/simple";
    /** Propriedade de LABORATORIO: outro indice, so quando a API configurada e deste computador ({@link #index}). */
    public static final String LAB_INDEX_PROPERTY = "nekkora.client.managedIndexUrl";
    public static final int DEFAULT_CACHE_MIB = 2048;
    public static final int MAX_CACHE_MIB = 20480;
    /** O {@code --report} do pip existe a partir da 22.2. */
    static final int[] MIN_PIP = {22, 2};
    public static final Duration RESOLUTION_TIMEOUT = Duration.ofMinutes(8);
    public static final Duration PREPARATION_TIMEOUT = Duration.ofMinutes(15);
    static final int MAX_LOCK = 400;
    static final List<String> CHECKS = List.of("PLAYWRIGHT_CHROMIUM", "PLAYWRIGHT_FIREFOX", "PLAYWRIGHT_WEBKIT");

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?$");
    private static final Pattern VERSION = Pattern.compile("^[0-9][0-9A-Za-z.+!_-]{0,63}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** Um item do lock: o nome como o pacote o declara, a versao, o SHA-256 do arquivo e o nome do arquivo (wheel). */
    public record LockItem(String name, String version, String sha256, String fileName) {
        /** Dentro do formato do contrato. */
        public boolean valid() {
            return name != null && NAME.matcher(name).matches() && version != null && VERSION.matcher(version).matches() && sha256 != null
                    && SHA.matcher(sha256).matches() && fileName != null && fileName.length() <= 200 && fileName.endsWith(".whl")
                    && !fileName.contains("/") && !fileName.contains("\\") && !fileName.contains("..");
        }
    }

    /** Uma falha: o item a que se refere (pode ser nulo), a fase do contrato e um resumo de ate 500 caracteres, sem segredos. */
    public record Failure(String requirement, String phase, String summary) {
    }

    /** {@code outcome}: RESOLVED, INCOMPATIBLE ou NOT_RESOLVED; {@code failure} nula so em RESOLVED. */
    public record Resolution(String outcome, String pythonVersion, String pipVersion, List<LockItem> lock, Failure failure) {
    }

    /**
     * {@code outcome}: READY, FAILED ou CANCELLED. {@code refusal}: o motivo de recusa da maquina quando FAILED
     * ({@code ENVIRONMENT_SETUP_FAILED}, {@code DEPENDENCY_INSTALL_FAILED} ou {@code EXTERNAL_PREREQUISITE_MISSING}). {@code log}: o
     * registro do preparo, ja sem segredos e com ate 64 KiB. {@code python}: o interpretador do ambiente pronto (so em READY).
     */
    public record Preparation(String outcome, String refusal, String pythonVersion, String pipVersion, List<LockItem> installed, Failure failure,
                              long createMs, long installMs, String log, Path python) {
    }

    /** Quem acompanha as etapas do preparo ({@code ENV_CREATING}, {@code DEPS_INSTALLING}). Tem de voltar logo e nao lancar. */
    public interface Stages {
        void reached(String stage);
    }

    private final Path root;
    private final String index;
    private final IntSupplier cacheMiB;
    private final Set<Path> active = ConcurrentHashMap.newKeySet();
    private volatile Duration resolutionTimeout = RESOLUTION_TIMEOUT;
    private volatile Duration preparationTimeout = PREPARATION_TIMEOUT;

    /**
     * {@code remoteRoot}: a pasta {@code remota} dos dados do aplicativo. {@code indexUrl}: o indice ({@link #index}).
     * {@code cacheLimitMiB}: o limite vigente do cache (lido a cada uso; fora de 0..20480 vale o padrao).
     */
    public ManagedEnvironments(Path remoteRoot, String indexUrl, IntSupplier cacheLimitMiB) {
        this.root = remoteRoot.toAbsolutePath().normalize();
        this.index = indexUrl;
        this.cacheMiB = cacheLimitMiB;
    }

    /**
     * O indice a usar: o PyPI publico, sempre - exceto quando a API configurada e DESTE computador ({@code apiOnThisComputer}) e a
     * propriedade de laboratorio traz um endereco {@code http(s)} sem credencial. Em qualquer outro caso a propriedade e ignorada.
     */
    public static String index(boolean apiOnThisComputer, String labIndex) {
        if (!apiOnThisComputer || labIndex == null || labIndex.isBlank()) {
            return PYPI;
        }
        String v = labIndex.strip();
        return v.matches("^https?://[A-Za-z0-9.\\[\\]:-]+(/[A-Za-z0-9._~/-]*)?$") ? v : PYPI;
    }

    /** O indice em uso. */
    public String indexUrl() {
        return index;
    }

    /** Para testes: prazos menores. */
    public void timeouts(Duration resolution, Duration preparation) {
        this.resolutionTimeout = resolution;
        this.preparationTimeout = preparation;
    }

    public Path resolutionsDir() {
        return root.resolve("resolucoes");
    }

    public Path cacheDir() {
        return root.resolve("cache-pip");
    }

    /** O ambiente de uma tentativa: {@code <pasta da tentativa>\ambiente}. */
    public static Path environmentDir(Path attemptDir) {
        return attemptDir.resolve("ambiente");
    }

    /** Ha resolucao ou preparo em andamento neste aplicativo. */
    public boolean busy() {
        return !active.isEmpty();
    }

    /** Esta pasta e de uma resolucao ou preparo em andamento (nao pode ser apagada agora). */
    public boolean active(Path dir) {
        return active.contains(dir.toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------------------------------------ resolucao

    /**
     * Resolve a lista efetiva ({@code nome==versao}) no Python {@code basePython} (uma INSTALACAO deste computador). Nunca lanca:
     * qualquer falha vira um resultado com a fase. A pasta da resolucao e apagada no fim, sempre.
     */
    public Resolution resolve(String resolutionId, Path basePython, List<String> dependencies, BooleanSupplier cancel) {
        if (resolutionId == null || !ID.matcher(resolutionId).matches()) {
            return notResolved(null, null, "OTHER", "Pedido de resolução fora do formato.");
        }
        Path work = resolutionsDir().resolve(resolutionId);
        active.add(work);
        long deadline = System.nanoTime() + resolutionTimeout.toNanos();
        String pythonVersion = null;
        String pipVersion = null;
        try {
            for (String d : dependencies) {
                if (!ExecutionPackage.validDependency(d)) {
                    return notResolved(null, null, "OTHER", "Dependência fora do formato nome==versão.");
                }
            }
            SafeTree.delete(work);
            Files.createDirectories(work);
            Path venv = work.resolve("venv");
            EnvCommand.Ran v = EnvCommand.run(basePython, List.of("-m", "venv", venv.toString()), work, "venv", left(deadline), cancel, 16 * 1024);
            if (v.timedOut() || expired(deadline)) {
                return notResolved(null, null, "TIMEOUT", "O ambiente temporário não ficou pronto no prazo.");
            }
            if (v.cancelled()) {
                return notResolved(null, null, "OTHER", "A resolução foi interrompida neste computador.");
            }
            if (!v.ok()) {
                boolean noPip = v.output().contains("ensurepip") || v.output().toLowerCase(Locale.ROOT).contains("pip");
                return notResolved(null, null, noPip ? "PIP_UNAVAILABLE" : "OTHER", PipText.summary("Não foi possível criar o ambiente temporário.", v.output()));
            }
            Path py = python(venv);
            pythonVersion = pythonVersion(py, work, cancel);
            pipVersion = pipVersion(py, work, deadline, cancel);
            if (pipVersion == null || !atLeast(pipVersion, MIN_PIP)) {
                return notResolved(pythonVersion, pipVersion, "PIP_UNAVAILABLE",
                        pipVersion == null ? "O pip não está disponível neste Python." : "O pip deste Python (" + pipVersion + ") é anterior a 22.2.");
            }
            Path reqs = work.resolve("requisitos.txt");
            Files.writeString(reqs, String.join("\n", dependencies) + "\n", StandardCharsets.UTF_8);
            Path report = work.resolve("relatorio.json");
            List<String> args = new ArrayList<>(List.of("-m", "pip", "install", "--isolated", "--no-input", "--disable-pip-version-check", "-vv",
                    "--progress-bar", "off", "--dry-run", "--ignore-installed", "--only-binary=:all:", "--report", report.toString(), "--index-url", index));
            args.addAll(cacheArgs());
            args.addAll(List.of("-r", reqs.toString()));
            EnvCommand.Ran r = EnvCommand.run(py, args, work, "pip", left(deadline), cancel, 64 * 1024);
            if (r.timedOut() || (expired(deadline) && !r.ok())) {
                return notResolved(pythonVersion, pipVersion, "TIMEOUT", "A resolução não terminou no prazo de " + resolutionTimeout.toMinutes() + " minutos.");
            }
            if (r.cancelled()) {
                return notResolved(pythonVersion, pipVersion, "OTHER", "A resolução foi interrompida neste computador.");
            }
            if (!r.ok()) {
                PipText.Classified c = PipText.resolution(r.outputFile(), r.output());
                String outcome = switch (c.phase()) {
                    case "NO_COMPATIBLE_WHEEL", "RESOLUTION_CONFLICT", "NOT_FOUND" -> "INCOMPATIBLE";
                    default -> "NOT_RESOLVED";
                };
                String why = switch (c.phase()) {
                    case "NO_COMPATIBLE_WHEEL" -> "Não há wheel compatível com este Python e este sistema para " + c.requirement() + ".";
                    case "NOT_FOUND" -> "O índice não tem " + c.requirement() + ".";
                    case "RESOLUTION_CONFLICT" -> "As dependências pedidas estão em conflito entre si.";
                    case "INDEX_UNREACHABLE" -> "O índice de pacotes não pôde ser alcançado deste computador.";
                    default -> "A resolução falhou.";
                };
                return new Resolution(outcome, pythonVersion, pipVersion, List.of(), new Failure(c.requirement(), c.phase(), PipText.summary(why, r.output())));
            }
            return lock(py, work, report, dependencies, pythonVersion, pipVersion, cancel);
        } catch (IOException | RuntimeException e) {
            return notResolved(pythonVersion, pipVersion, "OTHER", "A resolução falhou neste computador.");
        } finally {
            SafeTree.delete(work);
            active.remove(work);
        }
    }

    /** Le o lock do relatorio do pip (por um codigo FIXO, no proprio Python do ambiente temporario) e o confere contra o contrato. */
    private Resolution lock(Path py, Path work, Path report, List<String> dependencies, String pythonVersion, String pipVersion, BooleanSupplier cancel)
            throws IOException {
        EnvCommand.Ran r = EnvCommand.run(py, List.of("-B", "-c", LOCK_CODE, report.toString()), work, "lock", Duration.ofSeconds(60), cancel, 4 * 1024 * 1024);
        int at = r.output().lastIndexOf(LOCK_MARK);
        if (!r.ok() || at < 0) {
            return notResolved(pythonVersion, pipVersion, "OTHER", "O relatório do pip não pôde ser lido.");
        }
        Map<String, Object> o;
        try {
            o = JsonReader.object(r.output().substring(at + LOCK_MARK.length()).lines().findFirst().orElse(""));
        } catch (JsonReader.Invalid e) {
            return notResolved(pythonVersion, pipVersion, "OTHER", "O relatório do pip não pôde ser lido.");
        }
        List<?> items = JsonReader.list(o, "items");
        if (items == null || items.size() > MAX_LOCK) {
            return notResolved(pythonVersion, pipVersion, "OTHER", items == null ? "O relatório do pip não pôde ser lido." : "A lista efetiva passa de 400 itens.");
        }
        List<LockItem> lock = new ArrayList<>();
        Map<String, String> byName = new LinkedHashMap<>();
        for (Object x : items) {
            if (!(x instanceof Map<?, ?> m)) {
                return notResolved(pythonVersion, pipVersion, "OTHER", "O relatório do pip não pôde ser lido.");
            }
            LockItem item = new LockItem(str(m.get("name")), str(m.get("version")), str(m.get("sha256")), str(m.get("fileName")));
            if (!item.valid()) {
                return notResolved(pythonVersion, pipVersion, "OTHER", "Um item da lista efetiva não tem wheel com SHA-256 conhecido: " + PipText.redact(String.valueOf(item.name())) + ".");
            }
            if (byName.put(normalize(item.name()), item.version()) != null) {
                return notResolved(pythonVersion, pipVersion, "OTHER", "A lista efetiva repete " + item.name() + ".");
            }
            lock.add(item);
        }
        for (String d : dependencies) {
            String[] nv = nameVersion(d);
            if (!nv[1].equals(byName.get(normalize(nv[0])))) {
                return notResolved(pythonVersion, pipVersion, "OTHER", "A lista efetiva não traz " + d + " na versão pedida.");
            }
        }
        lock.sort(java.util.Comparator.comparing(i -> normalize(i.name())));
        return new Resolution("RESOLVED", pythonVersion, pipVersion, List.copyOf(lock), null);
    }

    static final String LOCK_MARK = "NEKKORA-CLIENT-LOCK ";
    /** Le so os campos do lock do relatorio do pip; nada do projeto e importado. */
    static final String LOCK_CODE = String.join("\n",
            "import json, sys",
            "from urllib.parse import urlsplit, unquote",
            "with open(sys.argv[1], encoding='utf-8') as f:",
            "    r = json.load(f)",
            "out = {'pip': r.get('pip_version'), 'items': []}",
            "for i in r.get('install') or []:",
            "    md = i.get('metadata') or {}",
            "    di = i.get('download_info') or {}",
            "    ai = di.get('archive_info') or {}",
            "    sha = (ai.get('hashes') or {}).get('sha256')",
            "    h = ai.get('hash') or ''",
            "    if not sha and h.startswith('sha256='):",
            "        sha = h[7:]",
            "    url = di.get('url') or ''",
            "    out['items'].append({'name': md.get('name'), 'version': md.get('version'), 'sha256': sha,",
            "                         'fileName': unquote(urlsplit(url).path.rsplit('/', 1)[-1])})",
            "sys.stdout.write('NEKKORA-CLIENT-LOCK ' + json.dumps(out) + '\\n')",
            "");

    private static Resolution notResolved(String pythonVersion, String pipVersion, String phase, String summary) {
        return new Resolution("NOT_RESOLVED", pythonVersion, pipVersion, List.of(), new Failure(null, phase, PipText.summary(summary, null)));
    }

    // ------------------------------------------------------------------------------------------------ preparo

    /**
     * Prepara o ambiente da tentativa em {@code attemptDir\ambiente}: cria o ambiente a partir de {@code basePython}, instala
     * EXATAMENTE {@code lock}, confere e faz as verificacoes {@code checks}. Nunca lanca. Fora de READY, o ambiente e apagado
     * antes de voltar. {@code dependencies}: a lista efetiva da versao (tem de continuar satisfeita no ambiente).
     */
    public Preparation prepare(Path attemptDir, Path basePython, List<LockItem> lock, List<String> dependencies, List<String> checks, Stages stages,
                               BooleanSupplier cancel) {
        Path env = environmentDir(attemptDir).toAbsolutePath().normalize();
        Path work = attemptDir.resolve("preparo").toAbsolutePath().normalize();
        StringBuilder log = new StringBuilder();
        long deadline = System.nanoTime() + preparationTimeout.toNanos();
        Prep p = new Prep(log);
        active.add(env);
        try {
            for (LockItem i : lock) {
                if (i == null || !i.valid()) {
                    return p.failed("DEPENDENCY_INSTALL_FAILED", null, "OTHER", "O lock recebido está fora do formato.");
                }
            }
            SafeTree.delete(env);
            SafeTree.delete(work);
            Files.createDirectories(work);
            stages.reached("ENV_CREATING");
            note(log, "Criando o ambiente temporário a partir do Python deste computador (" + safeVersion(basePython, work, cancel) + ").");
            long t0 = System.nanoTime();
            EnvCommand.Ran v = EnvCommand.run(basePython, List.of("-m", "venv", env.toString()), work, "venv", left(deadline), cancel, 32 * 1024);
            p.createMs = (System.nanoTime() - t0) / 1_000_000;
            log.append(v.output());
            if (v.cancelled()) {
                return p.cancelled();
            }
            if (v.timedOut() || expired(deadline)) {
                return p.failed("ENVIRONMENT_SETUP_FAILED", null, "TIMEOUT", "O ambiente não ficou pronto no prazo de " + preparationTimeout.toMinutes() + " minutos.");
            }
            if (!v.ok()) {
                return p.failed("ENVIRONMENT_SETUP_FAILED", null, PipText.noSpace(v.output()) ? "NO_SPACE" : "VENV",
                        PipText.summary("Não foi possível criar o ambiente temporário.", v.output()));
            }
            Path py = python(env);
            p.pythonVersion = pythonVersion(py, work, cancel);
            p.pipVersion = pipVersion(py, work, deadline, cancel);
            if (p.pipVersion == null) {
                return p.failed("ENVIRONMENT_SETUP_FAILED", null, "VENV", "O ambiente temporário foi criado sem o pip.");
            }
            note(log, "Ambiente criado em " + p.createMs + " ms (Python " + p.pythonVersion + ", pip " + p.pipVersion + ").");
            stages.reached("DEPS_INSTALLING");
            long t1 = System.nanoTime();
            if (lock.isEmpty()) {
                note(log, "O lock está vazio: nada a instalar.");
            } else {
                Path lockFile = work.resolve("lock.txt");
                StringBuilder lines = new StringBuilder();
                for (LockItem i : lock) {
                    lines.append(i.name()).append("==").append(i.version()).append(" --hash=sha256:").append(i.sha256()).append('\n');
                }
                Files.writeString(lockFile, lines.toString(), StandardCharsets.UTF_8);
                note(log, "Instalando " + lock.size() + (lock.size() == 1 ? " pacote" : " pacotes") + " do lock aceito (sem dependências extras, com conferência de SHA-256).");
                List<String> args = new ArrayList<>(List.of("-m", "pip", "install", "--isolated", "--no-input", "--disable-pip-version-check",
                        "--progress-bar", "off", "--no-deps", "--require-hashes", "--only-binary=:all:", "--index-url", index));
                args.addAll(cacheArgs());
                args.addAll(List.of("-r", lockFile.toString()));
                EnvCommand.Ran r = EnvCommand.run(py, args, work, "pip", left(deadline), cancel, 256 * 1024);
                p.installMs = (System.nanoTime() - t1) / 1_000_000;
                log.append(r.output());
                if (r.cancelled()) {
                    return p.cancelled();
                }
                if (r.timedOut() || (expired(deadline) && !r.ok())) {
                    return p.failed("DEPENDENCY_INSTALL_FAILED", null, "TIMEOUT", "A instalação não terminou no prazo de " + preparationTimeout.toMinutes() + " minutos.");
                }
                if (!r.ok()) {
                    PipText.Classified c = PipText.install(r.outputFile(), r.output());
                    String why = switch (c.phase()) {
                        case "HASH_MISMATCH" -> "O arquivo baixado não tem o SHA-256 do lock aceito.";
                        case "NO_SPACE" -> "Falta espaço em disco neste computador.";
                        case "DOWNLOAD" -> "Não foi possível baixar os pacotes do índice.";
                        case "NO_COMPATIBLE_WHEEL" -> "O índice não oferece o wheel do lock para este computador.";
                        default -> "A instalação das dependências falhou.";
                    };
                    return p.failed("DEPENDENCY_INSTALL_FAILED", c.requirement(), c.phase(), PipText.summary(why, r.output()));
                }
            }
            p.installMs = Math.max(p.installMs, (System.nanoTime() - t1) / 1_000_000);
            if (cancel.getAsBoolean()) {
                return p.cancelled();
            }
            Prep verified = verify(p, py, work, lock, dependencies, cancel);
            if (verified != null) {
                return verified.result;
            }
            String missing = checks(py, work, lock, checks, log, cancel);
            if (cancel.getAsBoolean()) {
                return p.cancelled();
            }
            if (missing != null) {
                String playwright = lock.stream().filter(i -> normalize(i.name()).equals("playwright")).map(i -> "playwright==" + i.version()).findFirst().orElse("playwright");
                return p.failed("EXTERNAL_PREREQUISITE_MISSING", playwright, "PREREQUISITE", missing);
            }
            note(log, "Ambiente pronto.");
            p.installed = List.copyOf(lock);
            p.python = py;
            return p.ready();
        } catch (IOException | RuntimeException e) {
            return p.failed(p.pythonVersion == null ? "ENVIRONMENT_SETUP_FAILED" : "DEPENDENCY_INSTALL_FAILED", null,
                    e instanceof IOException io && PipText.noSpace(String.valueOf(io.getMessage())) ? "NO_SPACE" : "OTHER", "O preparo falhou neste computador.");
        } finally {
            SafeTree.delete(work);
            if (p.result == null || !"READY".equals(p.result.outcome())) {
                SafeTree.delete(env);
            }
            active.remove(env);
        }
    }

    /** O preparo em andamento: o que ja se sabe e o resultado, quando houver. */
    private final class Prep {
        final StringBuilder log;
        String pythonVersion;
        String pipVersion;
        long createMs;
        long installMs;
        List<LockItem> installed = List.of();
        Path python;
        Preparation result;

        Prep(StringBuilder log) {
            this.log = log;
        }

        Preparation failed(String refusal, String requirement, String phase, String summary) {
            note(log, "Falha (" + phase + "): " + summary);
            result = new Preparation("FAILED", refusal, pythonVersion, pipVersion, List.of(), new Failure(requirement == null ? null : PipText.redact(requirement), phase,
                    PipText.summary(summary, null)), createMs, installMs, PipText.log(log.toString()), null);
            return result;
        }

        Preparation cancelled() {
            note(log, "Preparo interrompido: o processo do pip e os que ele abriu foram encerrados.");
            result = new Preparation("CANCELLED", null, pythonVersion, pipVersion, List.of(), new Failure(null, "OTHER", "Preparo interrompido por cancelamento."),
                    createMs, installMs, PipText.log(log.toString()), null);
            return result;
        }

        Preparation ready() {
            result = new Preparation("READY", null, pythonVersion, pipVersion, installed, null, createMs, installMs, PipText.log(log.toString()), python);
            return result;
        }
    }

    /** A conferencia: o que esta instalado e exatamente o lock, e a lista efetiva continua satisfeita. Nulo: conferido. */
    private Prep verify(Prep p, Path py, Path work, List<LockItem> lock, List<String> dependencies, BooleanSupplier cancel) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (LockItem i : lock) {
            wanted.put(normalize(i.name()), i.version());
        }
        Map<String, String> effective = new LinkedHashMap<>();
        for (String d : dependencies) {
            if (!ExecutionPackage.validDependency(d)) {
                p.failed("DEPENDENCY_INSTALL_FAILED", d, "VERIFY", "Dependência fora do formato nome==versão.");
                return p;
            }
            String[] nv = nameVersion(d);
            effective.put(normalize(nv[0]), nv[1]);
        }
        Set<String> names = new LinkedHashSet<>(wanted.keySet());
        names.addAll(effective.keySet());
        if (names.isEmpty()) {
            return null;
        }
        PythonProbe.Info info;
        try {
            info = PythonProbe.run(py, List.copyOf(names), work, Duration.ofSeconds(60), cancel::getAsBoolean);
        } catch (PythonProbe.Failed e) {
            if (e.failure() == PythonProbe.Failure.CANCELLED) {
                p.cancelled();
            } else {
                p.failed("DEPENDENCY_INSTALL_FAILED", null, "VERIFY", "Não foi possível conferir o ambiente preparado.");
            }
            return p;
        }
        if (info.installed() == null) {
            p.failed("DEPENDENCY_INSTALL_FAILED", null, "VERIFY", "Não foi possível conferir o ambiente preparado.");
            return p;
        }
        for (Map.Entry<String, String> w : wanted.entrySet()) {
            if (!w.getValue().equals(info.installed().get(w.getKey()))) {
                p.failed("DEPENDENCY_INSTALL_FAILED", w.getKey() + "==" + w.getValue(), "VERIFY", "O ambiente preparado não tem " + w.getKey() + "==" + w.getValue() + ".");
                return p;
            }
        }
        for (Map.Entry<String, String> w : effective.entrySet()) {
            if (!w.getValue().equals(info.installed().get(w.getKey()))) {
                p.failed("DEPENDENCY_INSTALL_FAILED", w.getKey() + "==" + w.getValue(), "VERIFY",
                        "A lista efetiva da versão não está satisfeita no ambiente preparado: falta " + w.getKey() + "==" + w.getValue() + ".");
                return p;
            }
        }
        note(p.log, "Conferido: as versões instaladas são as do lock e a lista efetiva está satisfeita.");
        return null;
    }

    static final String CHECK_MARK = "NEKKORA-CLIENT-CHECK ";
    /**
     * A verificacao do Playwright, por um codigo FIXO: pergunta ao Playwright DO AMBIENTE onde fica o executavel de cada navegador e
     * confere que o arquivo existe. Nao instala nem baixa nada.
     */
    static final String CHECK_CODE = String.join("\n",
            "import os, sys",
            "try:",
            "    from playwright.sync_api import sync_playwright",
            "except Exception as e:",
            "    sys.stdout.write('NEKKORA-CLIENT-CHECK - 0 ' + type(e).__name__ + '\\n')",
            "    sys.exit(0)",
            "with sync_playwright() as p:",
            "    for n in sys.argv[1:]:",
            "        try:",
            "            path = getattr(p, n).executable_path",
            "            ok = bool(path) and os.path.isfile(path)",
            "        except Exception:",
            "            ok = False",
            "        sys.stdout.write('NEKKORA-CLIENT-CHECK ' + n + ' ' + ('1' if ok else '0') + '\\n')",
            "");

    /** As verificacoes externas. Nulo: todas passaram; senao, o que falta (resumo). */
    private String checks(Path py, Path work, List<LockItem> lock, List<String> checks, StringBuilder log, BooleanSupplier cancel) throws IOException {
        if (checks == null || checks.isEmpty()) {
            return null;
        }
        List<String> browsers = new ArrayList<>();
        for (String c : checks) {
            if (!CHECKS.contains(c)) {
                return "Verificação externa desconhecida por este aplicativo: " + PipText.redact(String.valueOf(c)) + ".";
            }
            browsers.add(c.substring("PLAYWRIGHT_".length()).toLowerCase(Locale.ROOT));
        }
        if (lock.stream().noneMatch(i -> normalize(i.name()).equals("playwright"))) {
            return "A verificação do Playwright foi pedida, mas o lock não tem o playwright.";
        }
        note(log, "Verificando os navegadores do Playwright nesta máquina (nada é instalado): " + String.join(", ", browsers) + ".");
        List<String> args = new ArrayList<>(List.of("-B", "-c", CHECK_CODE));
        args.addAll(browsers);
        EnvCommand.Ran r = EnvCommand.run(py, args, work, "verificacao", Duration.ofSeconds(90), cancel, 64 * 1024);
        Map<String, Boolean> seen = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^" + CHECK_MARK + "(\\S+) ([01])").matcher(r.output());
        while (m.find()) {
            seen.put(m.group(1), "1".equals(m.group(2)));
        }
        List<String> missing = new ArrayList<>();
        for (String b : browsers) {
            if (!Boolean.TRUE.equals(seen.get(b))) {
                missing.add(b);
            }
        }
        if (missing.isEmpty() && r.ok()) {
            note(log, "Navegadores do Playwright encontrados.");
            return null;
        }
        return missing.isEmpty() ? "Não foi possível verificar os navegadores do Playwright."
                : "Navegador do Playwright não instalado nesta máquina: " + String.join(", ", missing)
                        + ". Instale os navegadores com a mesma versão do Playwright do lock; a Nekkora só confere.";
    }

    // ------------------------------------------------------------------------------------------------ cache e limpeza

    /** O limite vigente do cache, em MiB (0 desliga o cache). */
    public int cacheLimitMiB() {
        int v;
        try {
            v = cacheMiB.getAsInt();
        } catch (RuntimeException e) {
            v = DEFAULT_CACHE_MIB;
        }
        return v < 0 || v > MAX_CACHE_MIB ? DEFAULT_CACHE_MIB : v;
    }

    List<String> cacheArgs() {
        return cacheLimitMiB() == 0 ? List.of("--no-cache-dir") : List.of("--cache-dir", cacheDir().toString());
    }

    /** Quanto o cache ocupa agora, em bytes. */
    public long cacheBytes() {
        long[] n = {0};
        files(cacheDir(), (p, a) -> n[0] += a.size());
        return n[0];
    }

    /**
     * A poda: com o limite 0, o cache inteiro sai; senao, os arquivos MAIS ANTIGOS saem ate o total ficar abaixo de 80% do limite.
     * Quem chama garante que nao ha resolucao nem preparo em andamento. Devolve quantos arquivos sairam.
     */
    public int pruneCache() {
        if (busy()) {
            return 0;
        }
        int limit = cacheLimitMiB();
        if (limit == 0) {
            return clearCache() ? 1 : 0;
        }
        List<Object[]> all = new ArrayList<>();
        long[] total = {0};
        files(cacheDir(), (p, a) -> {
            all.add(new Object[] {p, a.lastModifiedTime().toMillis(), a.size()});
            total[0] += a.size();
        });
        long target = (long) (limit * 1024L * 1024L * 0.8);
        if (total[0] < target) {
            return 0;
        }
        all.sort(java.util.Comparator.comparingLong(o -> (Long) o[1]));
        int removed = 0;
        for (Object[] f : all) {
            if (total[0] < target) {
                break;
            }
            try {
                if (Files.deleteIfExists((Path) f[0])) {
                    total[0] -= (Long) f[2];
                    removed++;
                }
            } catch (IOException e) {
                // em uso: fica para a proxima poda
            }
        }
        return removed;
    }

    /** Apaga o cache inteiro. Recusa ({@code false}) com resolucao ou preparo em andamento. */
    public boolean clearCache() {
        if (busy()) {
            return false;
        }
        return SafeTree.delete(cacheDir());
    }

    /**
     * Varredura (ao abrir e de hora em hora): as pastas de resolucao que sobraram de uma queda saem. Nunca a de uma resolucao em
     * andamento. Devolve quantas sairam.
     */
    public int sweep() {
        Path dir = resolutionsDir();
        int n = 0;
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!active(p) && stopped(p) && SafeTree.delete(p)) {
                    n++;
                }
            }
        } catch (IOException | RuntimeException e) {
            // fica para a proxima
        }
        if (cacheLimitMiB() == 0 && !busy()) {
            SafeTree.delete(cacheDir());
        }
        return n;
    }

    /**
     * Apaga o ambiente da tentativa, se nao estiver em preparo agora. Quem chama garante que nenhum processo DA AUTOMACAO vive;
     * um {@code venv}/{@code pip} que tenha sobrado de uma queda do aplicativo e encerrado antes ({@link EnvCommand#stopLeftovers}).
     */
    public boolean discard(Path attemptDir) {
        Path env = environmentDir(attemptDir).toAbsolutePath().normalize();
        if (active(env)) {
            return false;
        }
        stopped(attemptDir);
        SafeTree.delete(attemptDir.resolve("preparo"));
        return SafeTree.delete(env);
    }

    /** Encerra o que sobrou de uma queda debaixo da pasta (sempre verdadeiro: a remocao em seguida diz se deu certo). */
    private static boolean stopped(Path dir) {
        EnvCommand.stopLeftovers(dir);
        return true;
    }

    // ------------------------------------------------------------------------------------------------ auxiliares

    private static void files(Path dir, java.util.function.BiConsumer<Path, BasicFileAttributes> each) {
        walk(dir, each, 0);
    }

    private static void walk(Path dir, java.util.function.BiConsumer<Path, BasicFileAttributes> each, int depth) {
        if (depth > 32 || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (SafeTree.link(a)) {
                    continue;
                }
                if (a.isDirectory()) {
                    walk(p, each, depth + 1);
                } else if (a.isRegularFile()) {
                    each.accept(p, a);
                }
            }
        } catch (IOException | RuntimeException e) {
            // o que ja foi visto vale
        }
    }

    /** O interpretador de um ambiente virtual. */
    static Path python(Path venv) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? venv.resolve("Scripts").resolve("python.exe") : venv.resolve("bin").resolve("python");
    }

    private static String pythonVersion(Path py, Path work, BooleanSupplier cancel) {
        try {
            return PythonProbe.run(py, List.of(), work, PythonProbe.DEFAULT_TIMEOUT, cancel::getAsBoolean).version();
        } catch (PythonProbe.Failed | RuntimeException e) {
            return null;
        }
    }

    private static String safeVersion(Path py, Path work, BooleanSupplier cancel) {
        String v = pythonVersion(py, work, cancel);
        return v == null ? "versão não informada" : "Python " + v;
    }

    private static String pipVersion(Path py, Path work, long deadline, BooleanSupplier cancel) throws IOException {
        EnvCommand.Ran r = EnvCommand.run(py, List.of("-m", "pip", "--version", "--isolated", "--disable-pip-version-check"), work, "pip-versao",
                min(left(deadline), Duration.ofSeconds(60)), cancel, 4096);
        Matcher m = Pattern.compile("(?m)^pip ([0-9][0-9A-Za-z.+!-]{0,40}) ").matcher(r.output());
        return r.ok() && m.find() ? m.group(1) : null;
    }

    /** {@code version} (como o pip a escreve) e pelo menos {@code min} (maior, menor)? */
    static boolean atLeast(String version, int[] min) {
        String[] parts = version.split("\\.");
        for (int k = 0; k < min.length; k++) {
            int v = k < parts.length ? leading(parts[k]) : 0;
            if (v != min[k]) {
                return v > min[k];
            }
        }
        return true;
    }

    private static int leading(String s) {
        Matcher m = Pattern.compile("^[0-9]{1,6}").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    /** O nome como a PEP 503 o compara. */
    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    /** {@code nome[extras]==versao} -> {nome, versao}. */
    private static String[] nameVersion(String dependency) {
        int at = dependency.indexOf("==");
        String name = dependency.substring(0, at);
        int extras = name.indexOf('[');
        return new String[] {extras < 0 ? name : name.substring(0, extras), dependency.substring(at + 2).strip()};
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static Duration left(long deadline) {
        return Duration.ofNanos(Math.max(1_000_000L, deadline - System.nanoTime()));
    }

    private static boolean expired(long deadline) {
        return System.nanoTime() >= deadline;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static void note(StringBuilder log, String text) {
        log.append("[Nekkora Agent] ").append(text).append('\n');
    }
}
