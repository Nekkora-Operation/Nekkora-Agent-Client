package com.nekkora.client.pack;

import com.nekkora.client.pack.ImportProblem.Code;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pacotes locais guardados pelo aplicativo, SEPARADOS por organizacao e ambiente:
 *
 * <pre>
 * &lt;dados&gt;/pacotes/&lt;organizationId&gt;/&lt;workspaceId&gt;/&lt;impressao-hex&gt;/      copia conferida do pacote (descritor + arquivos listados)
 * &lt;dados&gt;/pacotes/&lt;organizationId&gt;/&lt;workspaceId&gt;/&lt;impressao-hex&gt;.json  metadados locais do item
 * &lt;dados&gt;/pacotes/.preparo/                                          pastas temporarias das operacoes
 * </pre>
 *
 * <p>O endereco e o conteudo: a pasta tem o nome da impressao digital, de modo que o mesmo conteudo cai sempre no mesmo
 * lugar e um conteudo diferente nunca se mistura com outro. O mesmo pacote importado em dois ambientes existe duas
 * vezes, uma em cada um; nada de um ambiente e visto por outro.
 *
 * <p>Guardar NAO executa nada, NAO instala nada e NAO aprova nada: o ZIP e lido pelo leitor seguro ({@link SafeZip}) e
 * conferido pelo validador ({@link ExecutionPackage#inspect}); a aprovacao continua sendo um ato da pessoa, pela
 * impressao digital, e o inicio de uma execucao confere a impressao de novo. Nada aqui fala com a rede.
 *
 * <p>Os metadados sao locais: renomear muda so o nome de exibicao ({@code localName}); o descritor, os arquivos e a
 * impressao digital nunca sao tocados, e o nome DECLARADO pelo pacote continua disponivel em {@link Item#declaredName}.
 *
 * <p>Os metodos bloqueiam (disco): chame fora da linha de eventos da interface. A classe e segura entre linhas.
 */
public final class LocalPackages {
    public static final String SCHEMA = "nekkora-agent-client/local-package";
    public static final String VERSION = "1.0.0";
    /** Tamanho maximo do nome local. */
    public static final int LOCAL_NAME_MAX = 80;
    /** Pastas temporarias mais velhas que isto, deixadas por um encerramento abrupto, sao apagadas na operacao seguinte. */
    static final Duration STALE = Duration.ofHours(12);
    private static final String WORK = ".preparo";
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    /**
     * Um pacote guardado.
     *
     * @param context organizacao e ambiente a que o item pertence
     * @param fingerprint impressao digital ({@code sha256:...}); identifica o item dentro do contexto
     * @param declaredName nome DECLARADO no descritor (nunca muda)
     * @param packageVersion versao DECLARADA no descritor
     * @param entry o {@code .py} de entrada
     * @param pythonRequirement versao de Python exigida, no formato do descritor
     * @param dependencies dependencias com versao exata
     * @param localName nome de exibicao dado pela pessoa, ou {@code null}
     * @param linkedAutomationId automacao da Platform a que a pessoa associou o item, ou {@code null}
     * @param addedAt quando o item foi guardado neste contexto
     * @param dir a pasta da copia conferida
     */
    public record Item(PackageContext context, String fingerprint, String declaredName, String packageVersion, String entry, String pythonRequirement,
                       List<String> dependencies, String localName, UUID linkedAutomationId, Instant addedAt, Path dir) {
        public Item {
            dependencies = List.copyOf(dependencies);
        }

        /** O nome para a tela: o local, se houver; senao o declarado. */
        public String displayName() {
            return localName != null ? localName : declaredName;
        }

        public String shortFingerprint() {
            return ExecutionPackage.shortFingerprint(fingerprint);
        }

        private Item with(String name, UUID automation) {
            return new Item(context, fingerprint, declaredName, packageVersion, entry, pythonRequirement, dependencies, name, automation, addedAt, dir);
        }
    }

    /**
     * Resultado de guardar.
     *
     * @param item o item no contexto
     * @param pkg o pacote conferido na pasta guardada
     * @param alreadyPresent o mesmo conteudo ja estava guardado neste contexto (nada foi regravado; os metadados ficaram)
     */
    public record Added(Item item, ExecutionPackage pkg, boolean alreadyPresent) {}

    private final Path root;
    private final AtomicWriter writer;
    private final Clock clock;

    /** {@code root}: a pasta dos pacotes (ex.: {@code <dados>/pacotes}). */
    public LocalPackages(Path root, AtomicWriter writer, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.writer = Objects.requireNonNull(writer);
        this.clock = Objects.requireNonNull(clock);
    }

    public static Path defaultRoot(Path dataRoot) {
        return dataRoot.resolve("pacotes");
    }

    // ------------------------------------------------------------------ guardar

    /**
     * Le, confere e guarda o pacote do ZIP no contexto. Idempotente: o mesmo conteudo de novo devolve o item que ja
     * existe. Recusa do ZIP: {@link ImportProblem}; recusa do pacote: {@link ExecRefused} do validador (um ZIP do Studio ou
     * de laboratorio e recusado com {@code AGT-EXE-030}; um ZIP so de codigo, com {@code AGT-EXE-002}).
     */
    public synchronized Added add(PackageContext context, Path zip, SafeZip.Cancel cancel) throws ImportProblem, ExecRefused {
        Objects.requireNonNull(context);
        Path tmp = newWork();
        try {
            Path into = Files.createDirectories(tmp.resolve("x"));
            SafeZip.Content c = SafeZip.extract(zip, into, ZipLimits.DEFAULT, cancel);
            String prefix = ProjectImporter.rootPrefix(c);
            ExecutionPackage read = ExecutionPackage.inspect(prefix.isEmpty() ? into : into.resolve(prefix));
            if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
            Path target = packageDir(context, read.fingerprint());
            ExecutionPackage kept = intact(target, read.fingerprint());
            boolean present = kept != null;
            if (!present) {
                // so o descritor e os arquivos listados entram na copia guardada; o resto do ZIP fica de fora
                Path staged = tmp.resolve("pacote");
                copyListed(read, staged);
                if (!ExecutionPackage.inspect(staged).fingerprint().equals(read.fingerprint())) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
                Io.deleteTree(target); // pasta alterada ou incompleta: refeita
                Files.createDirectories(target.getParent());
                try {
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(staged, target);
                }
                kept = ExecutionPackage.inspect(target);
                if (!kept.fingerprint().equals(read.fingerprint())) {
                    Io.deleteTree(target);
                    throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
                }
            }
            Optional<Item> known = load(context, read.fingerprint());
            Item item = known.orElse(null);
            if (item == null) {
                item = new Item(context, kept.fingerprint(), kept.name(), kept.packageVersion(), kept.entry(), kept.pythonRequirement(), kept.dependencies(), null,
                        null, clock.instant().truncatedTo(ChronoUnit.MILLIS), target);
                save(item);
            }
            return new Added(item, kept, present && known.isPresent());
        } catch (IOException e) {
            throw store(e);
        } finally {
            Io.deleteTree(tmp);
        }
    }

    /** Guarda um pacote que o {@link Packager} gravou: o ZIP e lido e conferido de novo, e a impressao precisa ser a do empacotamento. */
    public synchronized Added addBuilt(PackageContext context, Packager.Built built, SafeZip.Cancel cancel) throws ImportProblem, ExecRefused {
        Added a = add(context, built.zip(), cancel);
        if (!a.item().fingerprint().equals(built.fingerprint())) {
            // o arquivo mudou entre o empacotamento e a leitura: o que foi guardado agora nao e o que a pessoa preparou
            if (!a.alreadyPresent()) remove(context, a.item().fingerprint());
            throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
        }
        return a;
    }

    /**
     * "Nova automacao" sem escolher pasta: empacota o projeto importado numa pasta temporaria do aplicativo e guarda o
     * resultado no contexto. O ZIP intermediario e apagado; o ZIP de origem nao e tocado.
     */
    public synchronized Added addBuilt(PackageContext context, ImportedProject project, PackagePlan plan, SafeZip.Cancel cancel)
            throws ImportProblem, ExecRefused {
        Objects.requireNonNull(context);
        Path tmp = newWork();
        try {
            return addBuilt(context, Packager.build(project, plan, tmp, false, cancel), cancel);
        } finally {
            Io.deleteTree(tmp);
        }
    }

    // ------------------------------------------------------------------ consultar

    /**
     * Os itens do contexto, em ordem fixa: do mais antigo para o mais novo e, no empate, pela impressao digital. Item com
     * metadados ilegiveis ou sem a pasta fica de fora (guardar o mesmo ZIP de novo o refaz).
     */
    public synchronized List<Item> list(PackageContext context) {
        Path dir = contextDir(context);
        List<Item> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                String n = String.valueOf(f.getFileName());
                String hex = n.substring(0, n.length() - ".json".length());
                if (HEX.matcher(hex).matches()) load(context, "sha256:" + hex).ifPresent(out::add);
            }
        } catch (IOException | RuntimeException e) {
            // o que ja foi lido continua valendo
        }
        out.sort(Comparator.comparing(Item::addedAt).thenComparing(Item::fingerprint));
        return List.copyOf(out);
    }

    /** O item com esta impressao NESTE contexto (o mesmo conteudo guardado em outro contexto nao conta). */
    public synchronized Optional<Item> find(PackageContext context, String fingerprint) {
        return load(context, requireFingerprint(fingerprint));
    }

    /**
     * Confere de novo a pasta guardada (hashes e impressao) e devolve o pacote, para preparar uma execucao. Pasta alterada
     * por fora: {@code AGT-EXE-004}; item que nao existe neste contexto: {@code AGT-EXE-001}.
     */
    public synchronized ExecutionPackage inspect(PackageContext context, String fingerprint) throws ExecRefused {
        Item item = load(context, requireFingerprint(fingerprint)).orElseThrow(() -> new ExecRefused(ErrorCode.EXEC_PACKAGE_NOT_FOUND));
        ExecutionPackage p = ExecutionPackage.inspect(item.dir());
        if (!p.fingerprint().equals(fingerprint)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
        return p;
    }

    // ------------------------------------------------------------------ metadados

    /**
     * Muda SO o nome de exibicao local ({@code null} ou vazio volta ao nome declarado). O descritor, os arquivos e a
     * impressao digital nao sao tocados.
     */
    public synchronized Item rename(PackageContext context, String fingerprint, String localName) throws ImportProblem {
        String name = localName == null || localName.isBlank() ? null : localName.strip();
        if (name != null && (name.length() > LOCAL_NAME_MAX || !name.equals(ZipNames.display(name)) || name.codePoints().anyMatch(Character::isISOControl))) {
            throw new ImportProblem(Code.LOCAL_NAME_INVALID);
        }
        Item item = required(context, fingerprint);
        return saved(item.with(name, item.linkedAutomationId()));
    }

    /** Associa o item a uma automacao da Platform (so nos metadados locais; nada e enviado). */
    public synchronized Item link(PackageContext context, String fingerprint, UUID automationId) throws ImportProblem {
        Objects.requireNonNull(automationId, "automationId");
        Item item = required(context, fingerprint);
        return saved(item.with(item.localName(), automationId));
    }

    /** Desfaz a associacao (so nos metadados locais). */
    public synchronized Item unlink(PackageContext context, String fingerprint) throws ImportProblem {
        Item item = required(context, fingerprint);
        return saved(item.with(item.localName(), null));
    }

    /**
     * Remove o item deste contexto (metadados e copia). Verdadeiro se existia. O mesmo conteudo guardado em outro contexto
     * e as execucoes ja registradas nao sao tocados.
     */
    public synchronized boolean remove(PackageContext context, String fingerprint) throws ImportProblem {
        requireFingerprint(fingerprint);
        Path meta = metaFile(context, fingerprint);
        Path dir = packageDir(context, fingerprint);
        boolean existed = Files.exists(meta, LinkOption.NOFOLLOW_LINKS) || Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
        try {
            // os metadados saem primeiro: uma queda no meio deixa uma pasta sem item (invisivel), nunca um item sem pasta
            Files.deleteIfExists(meta);
        } catch (IOException e) {
            throw new ImportProblem(Code.STORE_FAILED);
        }
        Io.deleteTree(dir);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw new ImportProblem(Code.STORE_FAILED);
        return existed;
    }

    // ------------------------------------------------------------------ interno

    private Item required(PackageContext context, String fingerprint) throws ImportProblem {
        return load(context, requireFingerprint(fingerprint)).orElseThrow(() -> new ImportProblem(Code.LOCAL_NOT_FOUND));
    }

    private Item saved(Item item) throws ImportProblem {
        try {
            save(item);
        } catch (IOException e) {
            throw new ImportProblem(Code.STORE_FAILED);
        }
        return item;
    }

    private static String requireFingerprint(String fingerprint) {
        if (!ExecutionPackage.validFingerprint(fingerprint)) throw new IllegalArgumentException("impressao digital fora do formato");
        return fingerprint;
    }

    private Path contextDir(PackageContext context) {
        // UUID.toString() e sempre a forma canonica: so hexadecimais e hifens
        Path dir = root.resolve(context.organizationId().toString()).resolve(context.workspaceId().toString()).normalize();
        if (!dir.startsWith(root) || dir.getNameCount() != root.getNameCount() + 2) throw new IllegalArgumentException("contexto fora da pasta de pacotes");
        return dir;
    }

    private Path packageDir(PackageContext context, String fingerprint) {
        return contextDir(context).resolve(requireFingerprint(fingerprint).substring("sha256:".length()));
    }

    private Path metaFile(PackageContext context, String fingerprint) {
        return contextDir(context).resolve(requireFingerprint(fingerprint).substring("sha256:".length()) + ".json");
    }

    /** A pasta guardada, so se ainda for exatamente o pacote desta impressao; senao {@code null}. */
    private static ExecutionPackage intact(Path dir, String fingerprint) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            ExecutionPackage p = ExecutionPackage.inspect(dir);
            return p.fingerprint().equals(fingerprint) ? p : null;
        } catch (ExecRefused e) {
            return null;
        }
    }

    private static void copyListed(ExecutionPackage pkg, Path to) throws IOException, ExecRefused {
        List<String> all = new ArrayList<>(pkg.files().keySet());
        all.add(ExecutionPackage.EXTERNAL_DESCRIPTOR);
        Files.createDirectories(to);
        for (String rel : all) {
            Path from = pkg.dir().resolve(rel).normalize();
            Path target = to.resolve(rel).normalize();
            if (!from.startsWith(pkg.dir()) || !target.startsWith(to) || Files.isSymbolicLink(from)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
            Files.createDirectories(target.getParent());
            Files.copy(from, target, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private Path newWork() throws ImportProblem {
        Path work = root.resolve(WORK);
        try {
            Files.createDirectories(work);
            cleanStale(work);
            return Files.createTempDirectory(work, "preparo-");
        } catch (IOException e) {
            throw store(e);
        }
    }

    private ImportProblem store(IOException e) {
        return Io.noSpace(e, root) ? new ImportProblem(Code.NO_SPACE) : new ImportProblem(Code.STORE_FAILED);
    }

    private void cleanStale(Path work) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(work, "preparo-*")) {
            Instant limit = clock.instant().minus(STALE);
            for (Path p : ds) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(limit)) {
                    Io.deleteTree(p);
                }
            }
        } catch (IOException | RuntimeException e) {
            // melhor esforco
        }
    }

    private void save(Item it) throws IOException {
        StringBuilder b = new StringBuilder("{\n");
        field(b, "schema", SCHEMA);
        field(b, "version", VERSION);
        field(b, "organizationId", it.context().organizationId().toString());
        field(b, "workspaceId", it.context().workspaceId().toString());
        field(b, "fingerprint", it.fingerprint());
        field(b, "name", it.declaredName());
        field(b, "packageVersion", it.packageVersion());
        field(b, "entry", it.entry());
        field(b, "python", it.pythonRequirement());
        b.append("  \"dependencies\": [");
        for (int i = 0; i < it.dependencies().size(); i++) b.append(i == 0 ? "" : ", ").append(StrictJson.quote(it.dependencies().get(i)));
        b.append("],\n");
        field(b, "localName", it.localName());
        field(b, "linkedAutomationId", it.linkedAutomationId() == null ? null : it.linkedAutomationId().toString());
        b.append("  \"addedAt\": ").append(StrictJson.quote(it.addedAt().toString())).append("\n}\n");
        Path f = metaFile(it.context(), it.fingerprint());
        Files.createDirectories(f.getParent());
        writer.write(f, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void field(StringBuilder b, String k, String v) {
        b.append("  ").append(StrictJson.quote(k)).append(": ").append(v == null ? "null" : StrictJson.quote(v)).append(",\n");
    }

    /**
     * Os metadados do item, so se forem DESTE lugar: organizacao, ambiente e impressao gravados precisam ser os do
     * caminho (um arquivo copiado de outro contexto nao vira item) e a pasta da copia precisa existir.
     */
    private Optional<Item> load(PackageContext context, String fingerprint) {
        Path f = metaFile(context, fingerprint);
        Path dir = packageDir(context, fingerprint);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 256 * 1024 || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Map<String, Object> m = JsonReader.object(Files.readString(f, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version")) || !context.organizationId().toString().equals(m.get("organizationId"))
                    || !context.workspaceId().toString().equals(m.get("workspaceId")) || !fingerprint.equals(m.get("fingerprint"))) {
                return Optional.empty();
            }
            List<String> deps = new ArrayList<>();
            for (Object d : Objects.requireNonNull(JsonReader.list(m, "dependencies"))) deps.add((String) d);
            String linked = JsonReader.text(m, "linkedAutomationId");
            return Optional.of(new Item(context, fingerprint, Objects.requireNonNull(JsonReader.text(m, "name")),
                    Objects.requireNonNull(JsonReader.text(m, "packageVersion")), Objects.requireNonNull(JsonReader.text(m, "entry")),
                    Objects.requireNonNull(JsonReader.text(m, "python")), deps, JsonReader.text(m, "localName"),
                    linked == null ? null : PackageContext.uuid(linked), Instant.parse(JsonReader.text(m, "addedAt")), dir));
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            return Optional.empty();
        }
    }
}
