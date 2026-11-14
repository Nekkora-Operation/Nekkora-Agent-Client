package com.nekkora.client.pack;

import com.nekkora.client.pack.ImportProblem.Code;
import com.nekkora.client.pack.ImportedProject.Kind;
import com.nekkora.client.pack.ImportedProject.ProjectFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Abre um ZIP de projeto escolhido pela pessoa (portado do Nekkora Agent 9b1e7e1; nao fala com a rede nem com a Platform).
 * <ol>
 *   <li>copia o ZIP para a pasta PRIVADA da operacao ({@code <dados>/importacao/op-...}), calculando o SHA-256 dos bytes
 *       copiados, e confere que a origem nao mudou durante a copia - dai em diante so a copia e lida (os mesmos bytes
 *       conferidos sao os empacotados);</li>
 *   <li>le e extrai a copia ({@link SafeZip}: limites, nomes, tipos, colisoes);</li>
 *   <li>reconhece a raiz (uma pasta externa unica vira a raiz, mostrada a pessoa) e o formato, SO pelos descritores da raiz
 *       (nenhuma busca recursiva escolhe manifesto); pacote existente e conferido pelo validador do Agent
 *       ({@link ExecutionPackage#inspect}).</li>
 * </ol>
 * Nada e executado: nenhum Python, {@code setup.py}, gancho ou import. Cancelar ou falhar apaga so a pasta da operacao.
 * <p>
 * AGENT-CLIENT-PACOTES-001: {@link #openFolder} faz o mesmo a partir de uma PASTA de projeto. A pasta e lida UMA vez, sem
 * seguir ligacao simbolica nem juncao, para uma copia privada (o retrato); dai em diante so o retrato e conferido e
 * empacotado, e a pasta da pessoa so e lida de novo para saber se mudou ({@link #folderUnchanged}). A pasta da pessoa
 * nunca e alterada.
 */
public final class ProjectImporter {
    /** Pastas de operacao mais velhas que isto, deixadas por um encerramento abrupto, sao apagadas na abertura seguinte. */
    static final Duration STALE = Duration.ofHours(12);

    private final Path workRoot;
    private final ZipLimits limits;

    /** {@code workRoot}: pasta do aplicativo para as operacoes de importacao (ex.: {@code <dados>/importacao}). */
    public ProjectImporter(Path workRoot, ZipLimits limits) {
        this.workRoot = workRoot;
        this.limits = limits;
    }

    public static Path defaultRoot(Path dataRoot) {
        return dataRoot.resolve("importacao");
    }

    /** Ponto de teste: chamado entre a copia e a conferencia final da origem (simula a origem mudando). */
    Runnable afterCopy = () -> {};

    public ImportedProject open(Path zip, SafeZip.Cancel cancel) throws ImportProblem {
        Path op = newOperation();
        try {
            ImportedProject p = openIn(zip, op, cancel);
            if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
            return p;
        } catch (ImportProblem | RuntimeException e) {
            Io.deleteTree(op);
            throw e;
        }
    }

    /** Fecha a operacao: apaga a pasta privada (o ZIP original e as saidas nao sao tocados). */
    public void close(ImportedProject p) {
        if (p != null && p.workDir().startsWith(workRoot)) Io.deleteTree(p.workDir());
    }

    /** A origem continua como quando foi copiada (tamanho e data)? Senao a importacao precisa ser refeita. */
    public static boolean sourceUnchanged(ImportedProject p) {
        if (p.fromFolder()) return folderUnchanged(p, p.folder().stamps().keySet(), false);
        try {
            BasicFileAttributes a = Files.readAttributes(p.source(), BasicFileAttributes.class);
            return a.size() == p.sourceSize() && a.lastModifiedTime().toInstant().equals(p.sourceModified());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Os arquivos {@code paths} da pasta de origem continuam como no retrato? Cada um precisa ainda ser um arquivo comum
     * (nunca ligacao), com o mesmo tamanho e a mesma data; com {@code content}, o conteudo e lido de novo e o hash precisa
     * ser o do retrato. So faz sentido para um projeto aberto de uma pasta.
     */
    public static boolean folderUnchanged(ImportedProject p, Collection<String> paths, boolean content) {
        if (!p.fromFolder()) return false;
        Map<String, String> hashes = content ? hashes(p.files()) : Map.of();
        try {
            for (String rel : paths) {
                ImportedProject.Stamp stamp = p.folder().stamps().get(rel);
                Path f = p.folder().root().resolve(rel).normalize();
                if (stamp == null || !f.startsWith(p.folder().root())) return false;
                BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther() || a.size() != stamp.size()
                        || !a.lastModifiedTime().toInstant().equals(stamp.modified())) {
                    return false;
                }
                if (content && !ExecutionPackage.fileHash(readNoFollow(f)).equals(hashes.get(rel))) return false;
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Pasta de trabalho nova desta raiz, para quem confere um pacote escolhido (apagada por quem pediu). */
    Path newWork() throws ImportProblem {
        return newOperation();
    }

    private Path newOperation() throws ImportProblem {
        try {
            Files.createDirectories(workRoot);
            cleanStale();
            return Files.createTempDirectory(workRoot, "op-");
        } catch (IOException e) {
            throw Io.work(e, workRoot);
        }
    }

    /** Sobras de operacoes interrompidas (so as pastas {@code op-*} desta raiz, antigas). */
    void cleanStale() {
        try (var s = Files.list(workRoot)) {
            Instant limit = Instant.now().minus(STALE);
            for (Path p : s.toList()) {
                String n = String.valueOf(p.getFileName());
                if (n.startsWith("op-") && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(limit)) {
                    Io.deleteTree(p);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // melhor esforco
        }
    }

    private ImportedProject openIn(Path zip, Path op, SafeZip.Cancel cancel) throws ImportProblem {
        Path copy = op.resolve("origem.zip");
        BasicFileAttributes before;
        String sha;
        try {
            if (!Files.isRegularFile(zip)) throw new ImportProblem(Code.SOURCE_NOT_FOUND);
            before = Files.readAttributes(zip, BasicFileAttributes.class);
            if (before.size() == 0) throw new ImportProblem(Code.EMPTY_FILE);
            if (before.size() > limits.zipBytes()) throw new ImportProblem(Code.ZIP_TOO_LARGE);
            sha = copy(zip, copy, cancel);
            afterCopy.run();
            BasicFileAttributes after = Files.readAttributes(zip, BasicFileAttributes.class);
            if (after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime()) || Files.size(copy) != before.size()) {
                throw new ImportProblem(Code.SOURCE_CHANGED);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            throw new ImportProblem(Code.SOURCE_NOT_FOUND);
        } catch (FileSystemException e) {
            throw locked(e) ? new ImportProblem(Code.SOURCE_LOCKED) : new ImportProblem(Code.SOURCE_NOT_FOUND);
        } catch (IOException e) {
            // trava de trecho do Windows (outro programa com o arquivo travado): IOException comum, com o motivo no texto
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            throw m.contains("lock") || m.contains("another process") || m.contains("outro processo") ? new ImportProblem(Code.SOURCE_LOCKED)
                    : new ImportProblem(Code.SOURCE_NOT_FOUND);
        }
        Path extracted = op.resolve("fonte");
        try {
            Files.createDirectories(extracted);
        } catch (IOException e) {
            throw Io.work(e, op);
        }
        SafeZip.Content content = SafeZip.extract(copy, extracted, limits, cancel);
        return scan(zip, before, sha, op, extracted, content, cancel);
    }

    private static boolean locked(FileSystemException e) {
        String r = String.valueOf(e.getReason()).toLowerCase(Locale.ROOT);
        return e instanceof java.nio.file.AccessDeniedException || r.contains("another process") || r.contains("outro processo") || r.contains("locked");
    }

    private String copy(Path from, Path to, SafeZip.Cancel cancel) throws IOException, ImportProblem {
        MessageDigest md = sha256();
        long total = 0;
        try (InputStream in = Files.newInputStream(from); OutputStream out = Files.newOutputStream(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
                total += n;
                if (total > limits.zipBytes()) throw new ImportProblem(Code.ZIP_TOO_LARGE);
                md.update(buf, 0, n);
                try {
                    out.write(buf, 0, n);
                } catch (IOException e) {
                    throw Io.work(e, to.getParent());
                }
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // ------------------------------------------------------------------ pasta de projeto

    /** Arquivos fora do pacote listados um a um; alem disso so a contagem e guardada (pastas de dados muito grandes). */
    static final int MAX_LISTED_OUTSIDE = 2000;

    /** Ponto de teste: chamado depois de cada arquivo copiado da pasta (simula a origem mudando durante a leitura). */
    Runnable afterFolderFile = () -> {};

    /**
     * Abre uma PASTA de projeto: tira o retrato (copia privada) e reconhece o formato, como em {@link #open}.
     * <ul>
     *   <li>nada e seguido: ligacao simbolica, juncao do Windows ou outro item especial recusa a pasta (a nao ser dentro
     *       do que ja fica fora - ambiente virtual, cache, controle de versao -, que nem e lido);</li>
     *   <li>pastas de ambiente virtual, cache e controle de versao nao sao lidas nem contam nos limites; arquivos que nao
     *       podem entrar no pacote (credenciais, dados, configuracao iniciada por ponto) sao listados com o motivo, mas
     *       NAO sao copiados - so um arquivo de dados que um {@code nekkora-external.json} da raiz lista e copiado;</li>
     *   <li>limites de quantidade, tamanho e profundidade iguais aos do ZIP, e os nomes passam pelas regras do Windows
     *       (reservados, colisao so por maiusculas).</li>
     * </ul>
     * A pasta da pessoa nunca e alterada. Recusa: {@link ImportProblem} ({@code AGT-PAC-070..084}).
     */
    public ImportedProject openFolder(Path folder, SafeZip.Cancel cancel) throws ImportProblem {
        Path root;
        try {
            root = folder.toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new ImportProblem(Code.FOLDER_NOT_FOUND);
        }
        if (!Files.isDirectory(root)) throw new ImportProblem(Code.FOLDER_NOT_FOUND);
        Path op = newOperation();
        try {
            Path work = workRoot.toRealPath();
            if (root.startsWith(work)) throw new ImportProblem(Code.FOLDER_IS_APP);
            ImportedProject p = snapshot(root, work, op, cancel);
            if (cancel.requested()) throw new ImportProblem(Code.FOLDER_CANCELLED);
            return p;
        } catch (IOException e) {
            Io.deleteTree(op);
            throw Io.work(e, workRoot);
        } catch (ImportProblem | RuntimeException e) {
            Io.deleteTree(op);
            throw e;
        }
    }

    /** Leva uma recusa para fora do percurso da pasta. */
    private static final class Carry extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient ImportProblem problem;

        Carry(ImportProblem problem) {
            super(problem.code().code());
            this.problem = problem;
        }
    }

    private ImportedProject snapshot(Path root, Path work, Path op, SafeZip.Cancel cancel) throws ImportProblem {
        Path copy = op.resolve("fonte");
        try {
            Files.createDirectories(copy);
        } catch (IOException e) {
            throw Io.work(e, op);
        }
        Set<String> listed = listedInDescriptor(root);
        List<ProjectFile> files = new ArrayList<>();
        Map<String, byte[]> rootSmall = new HashMap<>();
        List<String> paths = new ArrayList<>();
        Map<String, ImportedProject.Stamp> stamps = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        long[] total = {0};
        int[] unlisted = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private String rel(Path p) {
                    return root.relativize(p).toString().replace('\\', '/');
                }

                private Carry refuse(Code code, String rel) {
                    return new Carry(new ImportProblem(code, ZipNames.display(rel)));
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                    if (cancel.requested()) throw new Carry(new ImportProblem(Code.FOLDER_CANCELLED));
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    if (dir.equals(work)) return FileVisitResult.SKIP_SUBTREE; // a area de trabalho do aplicativo nunca e projeto
                    String rel = rel(dir);
                    if (ExecutionPackage.forbidden(rel)) {
                        // ambiente virtual, cache, controle de versao: nao e lido, nao e copiado, nao conta nos limites
                        skipped.add(rel + "/");
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // juncao do Windows: pasta E ponto de nova analise (isOther); ligacao: isSymbolicLink
                    if (a.isSymbolicLink() || a.isOther() || !dir.toRealPath().startsWith(root)) throw refuse(Code.FOLDER_LINK, rel);
                    try {
                        checkFolderName(rel, true, seen);
                    } catch (ImportProblem e) {
                        throw new Carry(e);
                    }
                    if (ZipNames.depth(rel) >= limits.depth()) throw refuse(Code.FOLDER_TOO_DEEP, rel);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                    if (cancel.requested()) throw new Carry(new ImportProblem(Code.FOLDER_CANCELLED));
                    String rel = rel(file);
                    boolean forbidden = ExecutionPackage.forbidden(rel);
                    InclusionRules.Rule rule = InclusionRules.of(rel);
                    if (a.isSymbolicLink() || a.isOther() || !a.isRegularFile()) {
                        // ligacao (para arquivo ou pasta) ou item especial: nunca lido; so passa se ja ficaria fora
                        if (!forbidden) throw refuse(Code.FOLDER_LINK, rel);
                        outside(rel, 0, rule);
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        checkFolderName(rel, false, seen);
                    } catch (ImportProblem e) {
                        throw new Carry(e);
                    }
                    boolean take = !forbidden && (rule.included() || listed.contains(rel));
                    boolean declares = !forbidden && !rel.contains("/") && a.size() <= 1024 * 1024;
                    if (!take) {
                        if (declares) rootSmall.put(rel, read(file, rel)); // .python-version: lido para as declaracoes, nunca copiado
                        outside(rel, a.size(), rule);
                        return FileVisitResult.CONTINUE;
                    }
                    if (stamps.size() + 1 > limits.entries()) throw new Carry(new ImportProblem(Code.FOLDER_TOO_MANY));
                    if (a.size() > limits.fileBytes()) throw refuse(Code.FOLDER_FILE_TOO_LARGE, rel);
                    total[0] += a.size();
                    if (total[0] > limits.totalBytes()) throw new Carry(new ImportProblem(Code.FOLDER_CONTENT_TOO_LARGE));
                    byte[] data = read(file, rel);
                    afterFolderFile.run();
                    BasicFileAttributes after;
                    try {
                        after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException e) {
                        throw new Carry(new ImportProblem(Code.FOLDER_CHANGED));
                    }
                    // os bytes copiados sao os de um arquivo que nao mudou enquanto era lido
                    if (!after.isRegularFile() || after.size() != data.length || a.size() != data.length
                            || !after.lastModifiedTime().equals(a.lastModifiedTime())) {
                        throw new Carry(new ImportProblem(Code.FOLDER_CHANGED));
                    }
                    Path target = copy.resolve(rel).normalize();
                    if (!target.startsWith(copy)) throw refuse(Code.FOLDER_LINK, rel);
                    try {
                        Files.createDirectories(target.getParent());
                        Files.write(target, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    } catch (IOException e) {
                        throw new Carry(Io.work(e, op));
                    }
                    if (declares) rootSmall.put(rel, data);
                    paths.add(rel);
                    stamps.put(rel, new ImportedProject.Stamp(a.size(), a.lastModifiedTime().toInstant()));
                    files.add(new ProjectFile(rel, a.size(), ExecutionPackage.fileHash(data), rule, ZipNames.contractSafe(rel)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                    String rel = rel(file);
                    if (ExecutionPackage.forbidden(rel)) return FileVisitResult.CONTINUE;
                    throw refuse(Code.FOLDER_UNREADABLE, rel);
                }

                private byte[] read(Path file, String rel) throws Carry {
                    try {
                        return readNoFollow(file);
                    } catch (IOException e) {
                        throw refuse(Code.FOLDER_UNREADABLE, rel);
                    }
                }

                /** Arquivo que fica fora do pacote e nao e copiado: so o nome, o tamanho e o motivo. */
                private void outside(String rel, long size, InclusionRules.Rule rule) {
                    if (files.size() - stamps.size() >= MAX_LISTED_OUTSIDE) {
                        unlisted[0]++;
                        return;
                    }
                    paths.add(rel);
                    files.add(new ProjectFile(rel, size, "", rule, ZipNames.contractSafe(rel)));
                }
            });
        } catch (Carry c) {
            throw c.problem;
        } catch (IOException | RuntimeException e) {
            throw new ImportProblem(Code.FOLDER_UNREADABLE);
        }
        files.sort(java.util.Comparator.comparing(ProjectFile::path));
        skipped.sort(null);
        return recognize(root, total[0], Instant.now(), null, op, copy, "", files, rootSmall, paths,
                new ImportedProject.FolderSource(root, stamps, skipped, unlisted[0]));
    }

    /**
     * Nome de um item da pasta (relativo, com {@code /}): o Windows precisa aceitar e dois itens nao podem diferir so por
     * maiusculas ou forma Unicode. Numa pasta do Windows isso nunca acontece; numa pasta vinda de outro sistema, recusa.
     */
    static void checkFolderName(String rel, boolean dir, Map<String, Boolean> seen) throws ImportProblem {
        if (ZipNames.unsafe(rel) || ZipNames.windowsInvalid(rel) || seen.put(ZipNames.collisionKey(rel), dir) != null) {
            throw new ImportProblem(Code.FOLDER_NAME, ZipNames.display(rel));
        }
    }

    /** Os caminhos que um {@code nekkora-external.json} da raiz lista (leitura tolerante: so decide o que copiar). */
    private static Set<String> listedInDescriptor(Path root) {
        Set<String> out = new HashSet<>();
        Path d = root.resolve(ExecutionPackage.EXTERNAL_DESCRIPTOR);
        try {
            if (!Files.isRegularFile(d, LinkOption.NOFOLLOW_LINKS) || Files.size(d) > JsonReader.MAX_CHARS) return out;
            Map<String, Object> files = JsonReader.obj(JsonReader.object(new String(readNoFollow(d), StandardCharsets.UTF_8)), "files");
            if (files != null) out.addAll(files.keySet());
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            // descritor ilegivel: o validador explica depois
        }
        return out;
    }

    /** Le um arquivo comum sem seguir ligacao. */
    static byte[] readNoFollow(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ reconhecimento

    private ImportedProject scan(Path zip, BasicFileAttributes attrs, String sha, Path op, Path extracted, SafeZip.Content content, SafeZip.Cancel cancel)
            throws ImportProblem {
        String prefix = rootPrefix(content);
        Path root = prefix.isEmpty() ? extracted : extracted.resolve(prefix);
        List<ProjectFile> files = new ArrayList<>();
        Map<String, byte[]> rootSmall = new HashMap<>();
        List<String> paths = new ArrayList<>();
        for (SafeZip.Item it : content.files()) {
            if (cancel.requested()) throw new ImportProblem(Code.CANCELLED);
            String rel = prefix.isEmpty() ? it.name() : it.name().substring(prefix.length() + 1);
            byte[] data;
            try {
                data = Files.readAllBytes(it.file());
            } catch (IOException e) {
                throw Io.work(e, op);
            }
            paths.add(rel);
            if (!rel.contains("/") && data.length <= 1024 * 1024) rootSmall.put(rel, data);
            files.add(new ProjectFile(rel, it.size(), ExecutionPackage.fileHash(data), InclusionRules.of(rel), ZipNames.contractSafe(rel)));
        }
        files.sort(java.util.Comparator.comparing(ProjectFile::path));
        return recognize(zip, attrs.size(), attrs.lastModifiedTime().toInstant(), sha, op, root, prefix, files, rootSmall, paths, null);
    }

    /** Reconhece o formato SO pelos descritores da raiz. {@code folder} nao nulo: a origem e uma pasta (textos da pasta). */
    private static ImportedProject recognize(Path source, long size, Instant modified, String sha, Path op, Path root, String prefix, List<ProjectFile> files,
                                             Map<String, byte[]> rootSmall, List<String> paths, ImportedProject.FolderSource folder) {
        boolean dir = folder != null;
        List<String> notes = new ArrayList<>();
        List<ImportProblem> blockers = new ArrayList<>();
        boolean ext = rootSmall.containsKey(ExecutionPackage.EXTERNAL_DESCRIPTOR) || paths.contains(ExecutionPackage.EXTERNAL_DESCRIPTOR);
        boolean manifest = paths.contains(ExecutionPackage.STUDIO_MANIFEST);
        boolean lab = paths.contains(ExecutionPackage.LAB_DESCRIPTOR);
        for (String p : paths) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (p.contains("/") && List.of(ExecutionPackage.EXTERNAL_DESCRIPTOR, ExecutionPackage.STUDIO_MANIFEST, ExecutionPackage.LAB_DESCRIPTOR).contains(name)) {
                notes.add("Há um " + name + " em subpasta (" + ZipNames.display(p) + "). Ele não é usado para reconhecer o pacote: só a raiz conta.");
            }
        }
        Kind kind;
        ExecutionPackage existing = null;
        ExecRefused refusal = null;
        if (ext && (manifest || lab)) {
            kind = Kind.CONFLICT;
            blockers.add(new ImportProblem(dir ? Code.FOLDER_DESCRIPTOR_CONFLICT : Code.DESCRIPTOR_CONFLICT));
        } else if (ext) {
            try {
                existing = ExecutionPackage.inspect(root);
                kind = Kind.EXTERNAL_PACKAGE;
            } catch (ExecRefused e) {
                kind = Kind.EXTERNAL_INVALID;
                refusal = e;
                blockers.add(new ImportProblem(dir ? Code.FOLDER_EXTERNAL_INVALID : Code.EXTERNAL_INVALID, e.code().description() + " " + e.code().code()));
            }
        } else if (manifest && schema(rootSmall.get(ExecutionPackage.STUDIO_MANIFEST), ExecutionPackage.STUDIO_SCHEMA)
                || lab && schema(rootSmall.get(ExecutionPackage.LAB_DESCRIPTOR), ExecutionPackage.LAB_SCHEMA)) {
            // formato que existe e que este aplicativo nao aceita: reconhecido so para recusar com um motivo claro
            kind = Kind.UNSUPPORTED_PACKAGE;
            blockers.add(new ImportProblem(dir ? Code.FOLDER_FORMAT_UNSUPPORTED : Code.FORMAT_UNSUPPORTED));
        } else {
            kind = Kind.CODE;
            if (manifest || lab) {
                // um manifest.json qualquer (de outra ferramenta) na raiz: o formato externo reserva o nome
                notes.add(Code.RESERVED_ROOT_NAME.text());
            }
        }
        if (existing != null && kind.readyPackage() && !dir) {
            ExecutionPackage ex = existing;
            int ignored = (int) files.stream().filter(f -> !ex.files().containsKey(f.path()) && !f.path().equals(ExecutionPackage.EXTERNAL_DESCRIPTOR)).count();
            if (ignored > 0) notes.add(ignored + " arquivo(s) do ZIP não estão listados no descritor e ficam fora da execução.");
        }
        if (kind == Kind.EXTERNAL_PACKAGE || kind == Kind.EXTERNAL_INVALID) {
            files.replaceAll(f -> f.path().equals(ExecutionPackage.EXTERNAL_DESCRIPTOR)
                    ? new ProjectFile(f.path(), f.size(), f.hash(), InclusionRules.Rule.DESCRIPTOR, f.contractSafe()) : f);
        }
        List<String> py = new ArrayList<>();
        for (ProjectFile f : files) {
            // numa pasta, so o que vai entrar no pacote pode ser entrada (nunca um arquivo que ficou fora ou nao foi copiado)
            boolean candidate = dir ? f.rule().included() && f.contractSafe() : !f.rule().equals(InclusionRules.Rule.ENVIRONMENT);
            if (f.path().endsWith(".py") && candidate) py.add(f.path());
        }
        if (kind == Kind.CODE && py.isEmpty()) blockers.add(new ImportProblem(dir ? Code.FOLDER_NO_PYTHON : Code.NO_PYTHON_FILES));
        if (dir && folder.unlisted() > 0) notes.add("Mais " + folder.unlisted() + " arquivo(s) que ficam fora do pacote não foram listados um a um.");
        Optional<String> suggested = py.contains("main.py") ? Optional.of("main.py") : py.contains("__main__.py") ? Optional.of("__main__.py")
                : py.stream().filter(p -> !p.contains("/")).count() == 1 ? py.stream().filter(p -> !p.contains("/")).findFirst() : Optional.empty();
        Declarations decl = Declarations.read(rootSmall, paths);
        return new ImportedProject(source, size, modified, sha, op, root, prefix, kind, existing, refusal, files, py, suggested, decl, blockers, notes, folder);
    }

    /** Uma pasta externa unica (sem arquivo solto na raiz do ZIP) e a raiz do projeto; so um nivel, como na Platform. */
    static String rootPrefix(SafeZip.Content c) {
        String first = null;
        for (SafeZip.Item it : c.files()) {
            int slash = it.name().indexOf('/');
            if (slash <= 0) return "";
            String seg = it.name().substring(0, slash);
            if (first == null) first = seg;
            else if (!first.equals(seg)) return "";
        }
        for (String d : c.dirs()) {
            String seg = d.contains("/") ? d.substring(0, d.indexOf('/')) : d;
            if (!seg.equals(first)) return "";
        }
        return first == null ? "" : first;
    }

    private static boolean schema(byte[] json, String expected) {
        if (json == null) return false;
        try {
            return expected.equals(JsonReader.text(JsonReader.object(new String(json, StandardCharsets.UTF_8)), "schema"));
        } catch (JsonReader.Invalid | RuntimeException e) {
            return false;
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Uso em teste: o mapa ordenado dos hashes incluidos. */
    static Map<String, String> hashes(List<ProjectFile> files) {
        Map<String, String> m = new TreeMap<>();
        for (ProjectFile f : files) m.put(f.path(), f.hash());
        return m;
    }
}
