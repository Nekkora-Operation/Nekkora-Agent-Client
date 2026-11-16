package com.nekkora.client.run;

import com.nekkora.client.pack.ExecutionPackage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Os ambientes Python que a pessoa pode escolher para executar um pacote, e a situacao de cada um CONTRA aquele pacote.
 * Nada e instalado, criado, baixado ou alterado: so descoberta limitada ({@link PythonSources}) e sondagem com codigo
 * fixo ({@link PythonProbe}).
 *
 * <h2>Instalacoes deste computador</h2>
 * Os candidatos das fontes sao reunidos, os da pasta de aliases da Microsoft Store sao descartados sem serem executados,
 * e cada caminho distinto e sondado uma vez. Candidato que nao existe, nao e arquivo comum ou nao responde como Python NAO
 * vira opcao. O que a sondagem responde decide o tipo: se o interpretador diz que e um ambiente virtual (prefixo diferente
 * do da instalacao-base), ele e apresentado como AMBIENTE VIRTUAL, nunca como instalacao do computador, mesmo tendo
 * chegado pelo PATH.
 *
 * <p>Duplicatas: dois candidatos sao o mesmo ambiente quando o interpretador responde o MESMO prefixo. Um ambiente
 * virtual e a sua instalacao-base tem prefixos diferentes e por isso nunca sao fundidos: as dependencias instaladas em um
 * nao sao as do outro.
 *
 * <h2>Pasta da automacao</h2>
 * So os dois ambientes convencionais DIRETAMENTE dentro da pasta indicada pela pessoa: {@code .venv} e {@code venv}. Nada
 * de busca recursiva, em subprojetos ou em outras pastas. Para ser um ambiente, a pasta precisa ter {@code pyvenv.cfg} e
 * o interpretador ({@code Scripts\python.exe} no Windows; {@code bin/python} nos outros sistemas), e o interpretador
 * precisa responder que e um ambiente virtual DAQUELA pasta. Se {@code .venv} ou {@code venv} for um link ou uma juncao
 * que leva para fora da pasta escolhida, ele NAO e seguido e isso e informado. Um ambiente legitimo aponta, no
 * {@code pyvenv.cfg}, para uma instalacao-base fora da pasta: isso nao e busca em outro projeto, e resolvido pelo proprio
 * interpretador, e se a base nao existir mais a sondagem falha e o ambiente aparece como "nao foi possivel verificar".
 *
 * <h2>Situacao</h2>
 * As mesmas regras do executor ({@link ExecutionPackage#pythonSatisfies} e a versao EXATA de cada dependencia declarada,
 * lida dos metadados de distribuicao). Versao compativel sozinha nao e compatibilidade completa. O que o pacote nao
 * declara (programas externos, arquivos, acessos) nao e verificavel aqui: "compativel" nao e promessa de que a execucao
 * vai dar certo.
 */
public final class PythonEnvironments {

    public enum Kind {
        /** Instalacao de Python deste computador. */
        INSTALLED,
        /** Ambiente virtual que chegou por uma fonte da maquina (PATH): nao e uma instalacao do computador. */
        VIRTUAL,
        /** Ambiente virtual da pasta que a pessoa indicou. */
        PROJECT
    }

    public enum State {
        COMPATIBLE,
        VERSION_INCOMPATIBLE,
        DEPENDENCIES_PENDING,
        /** A sondagem falhou, expirou ou o ambiente nao tem como informar as dependencias. */
        UNVERIFIED
    }

    /** Um requisito declarado que o ambiente nao atende. {@code installed}: a versao encontrada, ou {@code null} se ausente. */
    public record Pending(String requirement, String installed) {}

    /**
     * @param id identidade do ambiente (tipo + caminho real do interpretador): e o que preserva a escolha entre buscas
     * @param folderName {@code .venv} ou {@code venv} para {@link Kind#PROJECT}; nome da pasta do ambiente para
     *        {@link Kind#VIRTUAL}; {@code null} para instalacao
     * @param version {@code null} quando a sondagem nao respondeu
     */
    public record Environment(String id, Kind kind, String folderName, Path python, String version, State state, List<Pending> pending) {
        public boolean usable() {
            return state == State.COMPATIBLE;
        }
    }

    /**
     * @param failedSources fontes que existem e nao responderam: a busca foi parcial
     * @param unanswered candidatos encontrados que nao responderam como Python (nao viraram opcao)
     */
    public record Discovery(List<Environment> environments, List<String> failedSources, int unanswered, boolean cancelled) {}

    public enum Issue {
        /** O caminho indicado nao e uma pasta. */
        NOT_A_FOLDER,
        /** A pasta nao e a raiz de um ambiente virtual: nao tem {@code pyvenv.cfg}. */
        NOT_AN_ENVIRONMENT,
        /** Tem {@code pyvenv.cfg}, mas falta o interpretador deste sistema ({@code Scripts\python.exe} ou {@code bin/python}). */
        INCOMPLETE,
        /** A pasta escolhida e um link ou uma juncao: o destino nao e inspecionado. */
        LINK_NOT_FOLLOWED,
        /** A estrutura existe, mas o interpretador nao respondeu como um ambiente virtual DESTA pasta. */
        NOT_VERIFIED
    }

    /** O que nao virou ambiente e por que. {@code name}: o nome da pasta escolhida (ou {@code null} se ela nao existe). */
    public record Problem(String name, Issue issue) {}

    /** Os ambientes convencionais da pasta indicada. Lista vazia e sem problemas: nao ha ambiente virtual ali. */
    public record Project(Path folder, List<Environment> environments, List<Problem> problems, boolean cancelled) {}

    /** Os unicos nomes procurados, nesta ordem, diretamente dentro da pasta indicada. */

    private final PythonSources sources;
    private final Path workDir;
    private final Duration probeTimeout;
    private final boolean windows;

    public PythonEnvironments(PythonSources sources, Path workDir) {
        this(sources, workDir, PythonProbe.DEFAULT_TIMEOUT, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    PythonEnvironments(PythonSources sources, Path workDir, Duration probeTimeout, boolean windows) {
        this.sources = sources;
        this.workDir = workDir;
        this.probeTimeout = probeTimeout;
        this.windows = windows;
    }

    // ------------------------------------------------------------------ instalacoes do computador

    /** Descobre e confere contra {@code pkg}. Bloqueia (processos, com prazo): fora da linha de eventos. */
    public Discovery discover(ExecutionPackage pkg, PythonProbe.Cancel cancel) {
        List<String> failed = new ArrayList<>();
        Map<String, Path> candidates = new LinkedHashMap<>();
        for (PythonSources.Found found : safeCandidates(cancel, failed)) {
            if (found.failed()) failed.add(found.source());
            for (Path p : found.paths()) {
                if (p == null || !p.isAbsolute() || PythonSources.storeAlias(p)) continue; // alias da Store: nunca executado
                Path real = real(p);
                if (real == null || PythonSources.storeAlias(real)) continue; // nao existe (ou nao e legivel): nao e opcao
                candidates.putIfAbsent(key(real), real);
            }
        }
        Map<String, Environment> byPrefix = new LinkedHashMap<>();
        int unanswered = 0;
        boolean cancelled = false;
        for (Path python : candidates.values()) {
            if (cancel.cancelled()) {
                cancelled = true;
                break;
            }
            PythonProbe.Info info;
            try {
                info = PythonProbe.run(python, names(pkg), workDir, probeTimeout, cancel);
            } catch (PythonProbe.Failed e) {
                if (e.failure() == PythonProbe.Failure.CANCELLED) {
                    cancelled = true;
                    break;
                }
                unanswered++;
                continue;
            }
            Kind kind = info.virtual() ? Kind.VIRTUAL : Kind.INSTALLED;
            String folder = info.virtual() ? fileName(Path.of(info.prefix())) : null;
            // o prefixo identifica o ambiente; o tipo entra na chave para um venv jamais se fundir com a base
            byPrefix.putIfAbsent(kind + "|" + info.prefix().toLowerCase(Locale.ROOT), environment(kind, folder, info, pkg));
        }
        List<Environment> out = new ArrayList<>(byPrefix.values());
        out.sort(Comparator.comparing((Environment e) -> e.kind() == Kind.INSTALLED ? 0 : 1).thenComparing(e -> versionKey(e.version()), Comparator.reverseOrder())
                .thenComparing(e -> e.python().toString()));
        return new Discovery(List.copyOf(out), List.copyOf(failed), unanswered, cancelled);
    }

    /**
     * Uma instalacao (ou ambiente virtual) que a descoberta limitada achou e que respondeu a sondagem. {@code id} e a mesma
     * identidade usada na escolha local (tipo + caminho real): fica neste computador.
     */
    public record Installed(String id, boolean virtual, String version) {}

    /**
     * As instalacoes de Python deste computador, SEM conferir pacote nenhum: a mesma descoberta limitada e a mesma sondagem
     * de {@link #discover}, so com a versao. Quem nao respondeu fica de fora. Bloqueia (processos, com prazo).
     */
    public List<Installed> installed(PythonProbe.Cancel cancel) {
        Map<String, Path> candidates = new LinkedHashMap<>();
        for (PythonSources.Found found : safeCandidates(cancel, new ArrayList<>())) {
            for (Path p : found.paths()) {
                if (p == null || !p.isAbsolute() || PythonSources.storeAlias(p)) continue;
                Path real = real(p);
                if (real == null || PythonSources.storeAlias(real)) continue;
                candidates.putIfAbsent(key(real), real);
            }
        }
        Map<String, Installed> byPrefix = new LinkedHashMap<>();
        for (Path python : candidates.values()) {
            if (cancel.cancelled()) break;
            try {
                PythonProbe.Info info = PythonProbe.run(python, List.of(), workDir, probeTimeout, cancel);
                Kind kind = info.virtual() ? Kind.VIRTUAL : Kind.INSTALLED;
                byPrefix.putIfAbsent(kind + "|" + info.prefix().toLowerCase(Locale.ROOT), new Installed(id(kind, info.python()), info.virtual(), info.version()));
            } catch (PythonProbe.Failed e) {
                if (e.failure() == PythonProbe.Failure.CANCELLED) break;
            } catch (RuntimeException e) {
                // candidato que nao e um Python utilizavel: fica de fora
            }
        }
        return List.copyOf(byPrefix.values());
    }

    private List<PythonSources.Found> safeCandidates(PythonProbe.Cancel cancel, List<String> failed) {
        try {
            return sources.candidates(cancel);
        } catch (RuntimeException e) {
            failed.add("fontes de descoberta");
            return List.of();
        }
    }

    // ------------------------------------------------------------------ pasta da automacao

    /**
     * A pasta que a pessoa escolheu e, ELA MESMA, a raiz de um ambiente virtual? (AGENT-CLIENT-PREPARACAO-002: a pessoa
     * aponta o ambiente, com o nome que ele tiver; o aplicativo nao procura ambientes dentro de uma pasta de projeto.)
     * Confere a estrutura - {@code pyvenv.cfg} e o interpretador deste sistema, arquivos comuns - e pergunta ao proprio
     * interpretador se ele e um ambiente virtual DESTA pasta. Nada e procurado em subpastas, em pastas vizinhas ou acima; a
     * instalacao-base que o ambiente referencia NAO vira outro ambiente. Um link ou uma juncao nao e seguido. Uma tentativa
     * que nao serve volta sem ambiente e com o motivo: quem chamou mantem a escolha anterior. Bloqueia: fora da linha de
     * eventos.
     */
    public Project virtual(Path folder, ExecutionPackage pkg, PythonProbe.Cancel cancel) {
        Path given = folder == null || !folder.isAbsolute() ? null : folder.normalize();
        Path dir = given == null ? null : real(given);
        if (dir == null || !Files.isDirectory(dir) || given.getParent() == null || given.getFileName() == null) {
            return new Project(folder, List.of(), List.of(new Problem(null, Issue.NOT_A_FOLDER)), false);
        }
        String name = fileName(given);
        // a pasta escolhida, vista a partir da pasta-mae REAL: se o ultimo nome leva a outro lugar, e link ou juncao
        Path parent = real(given.getParent());
        if (Files.isSymbolicLink(given) || parent == null || !key(dir).equals(key(parent.resolve(given.getFileName())))) {
            return new Project(given, List.of(), List.of(new Problem(name, Issue.LINK_NOT_FOLLOWED)), false); // o destino nao e inspecionado
        }
        if (!Files.isRegularFile(dir.resolve("pyvenv.cfg"), LinkOption.NOFOLLOW_LINKS)) {
            return new Project(dir, List.of(), List.of(new Problem(name, Issue.NOT_AN_ENVIRONMENT)), false);
        }
        Path python = windows ? dir.resolve("Scripts").resolve("python.exe") : dir.resolve("bin").resolve("python");
        if (!Files.isRegularFile(python, LinkOption.NOFOLLOW_LINKS)) {
            return new Project(dir, List.of(), List.of(new Problem(name, Issue.INCOMPLETE)), false);
        }
        if (cancel.cancelled()) return new Project(dir, List.of(), List.of(), true);
        Environment e = check(Kind.PROJECT, name, python, dir, pkg, cancel);
        if (e == null) return new Project(dir, List.of(), List.of(), true);
        if (e.state() == State.UNVERIFIED) {
            return new Project(dir, List.of(), List.of(new Problem(name, Issue.NOT_VERIFIED)), false);
        }
        return new Project(dir, List.of(e), List.of(), false);
    }

    // ------------------------------------------------------------------ um ambiente

    /**
     * Confere de novo UM ambiente ja listado (antes de iniciar, ou quando o pacote muda). Se o interpretador sumiu ou nao
     * responde, a situacao e {@link State#UNVERIFIED}: nada e trocado por outro ambiente. {@code null}: cancelado.
     */
    public Environment recheck(Environment known, ExecutionPackage pkg, PythonProbe.Cancel cancel) {
        Path environmentDir = known.kind() == Kind.PROJECT ? known.python().getParent().getParent() : null;
        Environment now = check(known.kind(), known.folderName(), known.python(), environmentDir, pkg, cancel);
        if (now == null) return null;
        // a identidade nao muda: e o mesmo item da lista, com a situacao de agora
        return new Environment(known.id(), now.kind(), now.folderName(), now.python(), now.version(), now.state(), now.pending());
    }

    /** {@code environmentDir}: para um ambiente de projeto, a pasta que o interpretador tem de dizer que e o seu prefixo. */
    private Environment check(Kind kind, String folderName, Path python, Path environmentDir, ExecutionPackage pkg, PythonProbe.Cancel cancel) {
        String id = id(kind, python);
        try {
            PythonProbe.Info info = PythonProbe.run(python, names(pkg), workDir, probeTimeout, cancel);
            if (environmentDir != null) {
                Path prefix = real(Path.of(info.prefix()));
                // tem pyvenv.cfg e interpretador, mas nao e um ambiente virtual desta pasta (copia solta, configuracao quebrada)
                if (!info.virtual() || prefix == null || !key(prefix).equals(key(environmentDir))) {
                    return new Environment(id, kind, folderName, python, info.version(), State.UNVERIFIED, List.of());
                }
            } else if (kind == Kind.INSTALLED && info.virtual()) {
                return new Environment(id, Kind.VIRTUAL, fileName(Path.of(info.prefix())), python, info.version(), State.UNVERIFIED, List.of());
            }
            Environment e = environment(kind, folderName, info, pkg);
            return new Environment(id, e.kind(), e.folderName(), python, e.version(), e.state(), e.pending());
        } catch (PythonProbe.Failed e) {
            if (e.failure() == PythonProbe.Failure.CANCELLED) return null;
            return new Environment(id, kind, folderName, python, null, State.UNVERIFIED, List.of());
        } catch (RuntimeException e) {
            return new Environment(id, kind, folderName, python, null, State.UNVERIFIED, List.of());
        }
    }

    private Environment environment(Kind kind, String folderName, PythonProbe.Info info, ExecutionPackage pkg) {
        String id = id(kind, info.python());
        if (!ExecutionPackage.pythonSatisfies(pkg.pythonRequirement(), info.version())) {
            return new Environment(id, kind, folderName, info.python(), info.version(), State.VERSION_INCOMPATIBLE, List.of());
        }
        if (pkg.dependencies().isEmpty()) {
            return new Environment(id, kind, folderName, info.python(), info.version(), State.COMPATIBLE, List.of());
        }
        if (info.installed() == null) {
            return new Environment(id, kind, folderName, info.python(), info.version(), State.UNVERIFIED, List.of());
        }
        List<Pending> pending = new ArrayList<>();
        for (String declared : pkg.dependencies()) {
            String name = distribution(declared);
            String wanted = declared.split("==", 2)[1];
            String installed = info.installed().get(name);
            if (!wanted.equals(installed)) pending.add(new Pending(declared, installed));
        }
        return new Environment(id, kind, folderName, info.python(), info.version(), pending.isEmpty() ? State.COMPATIBLE : State.DEPENDENCIES_PENDING,
                List.copyOf(pending));
    }

    // ------------------------------------------------------------------ apoio

    /** Os nomes de distribuicao declarados (sem extras nem versao), como o executor os confere. */
    static List<String> names(ExecutionPackage pkg) {
        List<String> out = new ArrayList<>();
        for (String d : pkg.dependencies()) out.add(distribution(d));
        return out;
    }

    private static String distribution(String declared) {
        return declared.replaceAll("\\[.*]", "").split("==", 2)[0];
    }

    static String id(Kind kind, Path python) {
        Path real = real(python);
        return (kind == Kind.PROJECT ? "projeto" : "computador") + ":" + key(real == null ? python.toAbsolutePath().normalize() : real);
    }

    /** O caminho real (links e juncoes resolvidos), ou {@code null} se nao existe ou nao pode ser lido. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String key(Path p) {
        return p.toString().toLowerCase(Locale.ROOT);
    }

    private static String fileName(Path p) {
        return p.getFileName() == null ? p.toString() : p.getFileName().toString();
    }

    private static String versionKey(String version) {
        if (version == null) return "";
        StringBuilder b = new StringBuilder();
        for (String part : version.split("\\.")) b.append(String.format("%06d", Integer.parseInt(part)));
        return b.toString();
    }
}
