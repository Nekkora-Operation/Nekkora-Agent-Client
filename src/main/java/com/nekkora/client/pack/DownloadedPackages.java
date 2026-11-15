package com.nekkora.client.pack;

import com.nekkora.client.pack.ImportProblem.Code;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AGENT-CLIENT-PACOTES-001 T02: os pacotes BAIXADOS da Platform, guardados nesta maquina. Esta classe nao baixa nada (a
 * rede fica em outra camada): ela entrega o lugar do arquivo parcial, confere o arquivo recebido e so entao o instala.
 *
 * <pre>
 * &lt;dados&gt;/baixados/.parcial/                                               arquivos em download e pastas de conferencia
 * &lt;dados&gt;/baixados/&lt;origem&gt;/&lt;organizationId&gt;/&lt;automationId&gt;/&lt;versionId&gt;/
 *     pacote.zip                                                          o ZIP conferido, como a Platform o entregou
 *     pacote.json                                                         metadados (nekkora-agent-client/downloaded-package 1.0.0)
 * </pre>
 *
 * {@code <origem>} sao os 16 primeiros hexadecimais do SHA-256 do endereco da Platform (em minusculas, sem a barra
 * final): dois enderecos nunca dividem pasta. Cada versao tem a sua pasta: instalar outra versao da mesma automacao nunca
 * substitui nem apaga os arquivos de uma versao ja instalada.
 *
 * <p>Ordem da instalacao ({@link #install}): (1) tamanho e SHA-256 do arquivo contra o que a Platform informou, ANTES de
 * abri-lo como ZIP; (2) leitura segura ({@link SafeZip}) e validador real ({@link ExecutionPackage#inspect}); (3) impressao
 * digital calculada igual a informada; (4) so entao o ZIP conferido e movido (troca atomica) para a pasta da versao e o
 * pacote e guardado no contexto e associado a automacao. Qualquer divergencia: o arquivo parcial e apagado e nada e
 * instalado. Bytes parciais so existem em {@code .parcial}, que o aplicativo nunca trata como pronto.
 *
 * <p>Nada aqui executa script, instala dependencia ou fala com a rede. Os metodos bloqueiam (disco).
 */
public final class DownloadedPackages {
    public static final String SCHEMA = "nekkora-agent-client/downloaded-package";
    public static final String VERSION = "1.0.0";
    /** Parciais mais velhos que isto, deixados por um encerramento abrupto, sao apagados no download seguinte. */
    static final Duration STALE = Duration.ofHours(12);
    static final String PARTIAL = ".parcial";
    static final String PARTIAL_SUFFIX = ".zip.parcial";
    static final String ZIP = "pacote.zip";
    static final String META = "pacote.json";
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    private final Path root;
    private final AtomicWriter writer;
    private final Clock clock;

    /** {@code root}: a pasta dos pacotes baixados (ex.: {@code <dados>/baixados}). */
    public DownloadedPackages(Path root, AtomicWriter writer, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.writer = Objects.requireNonNull(writer);
        this.clock = Objects.requireNonNull(clock);
    }

    public static Path defaultRoot(Path dataRoot) {
        return dataRoot.resolve("baixados");
    }

    /**
     * O ZIP conferido de uma versao instalada.
     *
     * @param zip o arquivo guardado
     * @param sha256 SHA-256 dos bytes do ZIP (hexadecimal)
     * @param sizeBytes tamanho do ZIP
     * @param fingerprint impressao digital do pacote ({@code sha256:...})
     * @param versionNumber numero da versao na Platform
     * @param installedAt quando foi instalado
     */
    public record Kept(Path zip, String sha256, long sizeBytes, String fingerprint, int versionNumber, Instant installedAt) {}

    // ------------------------------------------------------------------ parcial

    /**
     * Reserva o arquivo PARCIAL de um download (vazio, com nome proprio) em {@code .parcial}. Quem baixa escreve nele
     * ({@link #open}) e depois chama {@link #install} ou {@link #discard}.
     */
    public synchronized Path target(String platformOrigin, UUID organization, UUID automationId, UUID versionId) throws ImportProblem {
        String key = originKey(platformOrigin);
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(automationId, "automationId");
        Objects.requireNonNull(versionId, "versionId");
        Path dir = root.resolve(PARTIAL);
        try {
            Files.createDirectories(dir);
            cleanStale(dir);
            return Files.createTempFile(dir, key + "-" + versionId + "-", PARTIAL_SUFFIX);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    /** Abre o arquivo parcial para escrita, do inicio. So um arquivo reservado por {@link #target}. */
    public OutputStream open(Path partial) throws ImportProblem {
        Path f = managed(partial);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new ImportProblem(Code.DOWNLOAD_NOT_MANAGED);
            return Files.newOutputStream(f, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    /** Apaga um arquivo parcial (cancelamento ou falha do download). Qualquer caminho fora de {@code .parcial} e recusado. */
    public synchronized void discard(Path partial) throws ImportProblem {
        Path f = managed(partial);
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    /** O caminho e de um arquivo parcial DESTA pasta: filho direto de {@code .parcial}, com o sufixo, nunca ligacao nem pasta. */
    private Path managed(Path partial) throws ImportProblem {
        if (partial == null) throw new ImportProblem(Code.DOWNLOAD_NOT_MANAGED);
        Path dir = root.resolve(PARTIAL);
        Path f;
        try {
            f = partial.toAbsolutePath().normalize();
        } catch (InvalidPathException | java.io.IOError e) {
            throw new ImportProblem(Code.DOWNLOAD_NOT_MANAGED);
        }
        if (!dir.equals(f.getParent()) || !String.valueOf(f.getFileName()).endsWith(PARTIAL_SUFFIX)
                || Files.isSymbolicLink(f) || Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS)) {
            throw new ImportProblem(Code.DOWNLOAD_NOT_MANAGED);
        }
        try {
            // a propria pasta .parcial nao pode ser uma ligacao para outro lugar
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !dir.toRealPath().equals(root.toRealPath().resolve(PARTIAL))) {
                throw new ImportProblem(Code.DOWNLOAD_NOT_MANAGED);
            }
        } catch (IOException e) {
            throw new ImportProblem(Code.DOWNLOAD_NOT_MANAGED);
        }
        return f;
    }

    private void cleanStale(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            Instant limit = clock.instant().minus(STALE);
            for (Path p : ds) {
                if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(limit)) Io.deleteTree(p);
            }
        } catch (IOException | RuntimeException e) {
            // melhor esforco
        }
    }

    // ------------------------------------------------------------------ instalar

    /**
     * Confere o arquivo baixado e instala o pacote: guarda o ZIP na pasta da versao, guarda o pacote no contexto (origem
     * {@code PLATFORM_DOWNLOAD}, com a automacao e a versao) e o associa a automacao. A mesma versao instalada de novo nao
     * duplica nada. Em qualquer desfecho o arquivo parcial deixa de existir.
     *
     * @param partial o arquivo entregue por {@link #target}, ja escrito
     * @param expectedSha256 SHA-256 do ZIP informado pela Platform (64 hexadecimais; aceita o prefixo {@code sha256:})
     * @param expectedSizeBytes tamanho do ZIP informado pela Platform
     * @param expectedFingerprint impressao digital informada pela Platform
     */
    public synchronized LocalPackages.Item install(LocalPackages packages, String platformOrigin, PackageContext context, UUID automationId, UUID versionId,
                                                   int versionNumber, Path partial, String expectedSha256, long expectedSizeBytes,
                                                   String expectedFingerprint, SafeZip.Cancel cancel) throws ImportProblem, ExecRefused {
        Path file = managed(partial);
        Path work = null;
        try {
            String key = originKey(platformOrigin);
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(automationId, "automationId");
            Objects.requireNonNull(versionId, "versionId");
            String sha = hex(expectedSha256);
            String fingerprint = hex(expectedFingerprint);
            // sem o que conferir nao ha instalacao: valor ausente ou fora do formato e divergencia
            if (sha == null || fingerprint == null || expectedSizeBytes <= 0) throw new ImportProblem(Code.DOWNLOAD_MISMATCH);
            fingerprint = "sha256:" + fingerprint;
            if (cancel.requested()) throw new ImportProblem(Code.DOWNLOAD_CANCELLED);
            // (1) tamanho e SHA-256, antes de abrir como ZIP
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != expectedSizeBytes) throw new ImportProblem(Code.DOWNLOAD_MISMATCH);
            if (!sha.equals(sha256(file, cancel))) throw new ImportProblem(Code.DOWNLOAD_MISMATCH);
            // (2) leitura segura e validador real; (3) impressao digital
            work = Files.createTempDirectory(file.getParent(), "conferencia-");
            ExecutionPackage pkg = inspect(file, Files.createDirectories(work.resolve("x")), cancel);
            if (!pkg.fingerprint().equals(fingerprint)) throw new ImportProblem(Code.DOWNLOAD_MISMATCH);
            if (cancel.requested()) throw new ImportProblem(Code.DOWNLOAD_CANCELLED);
            // (4) o ZIP conferido vai para a pasta da versao; o pacote, para o contexto
            keep(file, key, platformOrigin, context.organizationId(), automationId, versionId, versionNumber, sha, expectedSizeBytes, fingerprint);
            LocalPackages.Provenance provenance = new LocalPackages.Provenance(LocalPackages.Origin.PLATFORM_DOWNLOAD, null, automationId, versionId, versionNumber);
            LocalPackages.Item item = packages.addInspected(context, pkg, provenance).item();
            // o mesmo conteudo ja estava aqui por outro caminho (um .zip local, por exemplo): agora e a versao conferida
            if (!provenance.equals(item.provenance())) item = packages.provenance(context, fingerprint, provenance);
            if (!automationId.equals(item.linkedAutomationId())) item = packages.link(context, fingerprint, automationId);
            return item;
        } catch (IOException e) {
            throw failed(e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // sobra em .parcial: nunca tratada como pronta, limpa depois
            }
            Io.deleteTree(work);
        }
    }

    /**
     * Confere um ZIP recebido para uma execucao GERENCIADA (a que nao nasce de uma aprovacao da pessoa aqui) e o extrai em
     * {@code into}, uma pasta nova de quem chama. As mesmas conferencias de {@link #install}, na mesma ordem: (1) SHA-256
     * dos bytes, antes de abrir como ZIP; (2) leitura segura e validador real; (3) impressao digital. NADA e guardado na
     * area de pacotes nem associado a contexto algum: o pacote so existe na pasta indicada. O ZIP nao e apagado aqui.
     *
     * @param expectedSha256 SHA-256 do ZIP (64 hexadecimais; aceita o prefixo {@code sha256:})
     * @param expectedFingerprint impressao digital esperada (idem)
     */
    public static ExecutionPackage unpackVerified(Path zip, String expectedSha256, String expectedFingerprint, Path into, SafeZip.Cancel cancel)
            throws ImportProblem {
        String sha = hex(expectedSha256);
        String fingerprint = hex(expectedFingerprint);
        if (sha == null || fingerprint == null) throw new ImportProblem(Code.DOWNLOAD_MISMATCH);
        try {
            if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) throw new ImportProblem(Code.DOWNLOAD_MISMATCH);
            if (!sha.equals(sha256(zip, cancel))) throw new ImportProblem(Code.DOWNLOAD_MISMATCH);
            if (Files.exists(into, LinkOption.NOFOLLOW_LINKS)) throw new ImportProblem(Code.DOWNLOAD_STORE_FAILED);
            ExecutionPackage pkg = inspect(zip, Files.createDirectories(into), cancel);
            if (!pkg.fingerprint().equals("sha256:" + fingerprint)) throw new ImportProblem(Code.DOWNLOAD_MISMATCH);
            return pkg;
        } catch (IOException e) {
            throw new ImportProblem(Code.DOWNLOAD_STORE_FAILED);
        }
    }

    private static ExecutionPackage inspect(Path zip, Path into, SafeZip.Cancel cancel) throws ImportProblem {
        try {
            SafeZip.Content c = SafeZip.extract(zip, into, ZipLimits.DEFAULT, cancel);
            String prefix = ProjectImporter.rootPrefix(c);
            return ExecutionPackage.inspect(prefix.isEmpty() ? into : into.resolve(prefix));
        } catch (ExecRefused e) {
            throw new ImportProblem(Code.DOWNLOAD_INVALID, e.code().code());
        } catch (ImportProblem e) {
            if (e.code() == Code.CANCELLED) throw new ImportProblem(Code.DOWNLOAD_CANCELLED);
            if (e.code() == Code.NO_SPACE || e.code() == Code.WORK_FAILED) throw e;
            throw new ImportProblem(Code.DOWNLOAD_INVALID, e.code().code());
        }
    }

    /** Move o ZIP conferido para a pasta da versao (troca atomica) e grava os metadados. O mesmo ZIP ja guardado fica como esta. */
    private void keep(Path file, String key, String origin, UUID organization, UUID automationId, UUID versionId, int versionNumber, String sha, long size,
                      String fingerprint) throws IOException, ImportProblem {
        Path dir = versionDir(key, organization, automationId, versionId);
        Path zip = dir.resolve(ZIP);
        Files.createDirectories(dir);
        boolean same = Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS) && Files.size(zip) == size && sha.equals(sha256(zip, SafeZip.Cancel.NEVER));
        if (!same) {
            try {
                Files.move(file, zip, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(file, zip, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (same && find(origin, organization, automationId, versionId).isPresent()) return;
        StringBuilder b = new StringBuilder("{\n");
        field(b, "schema", SCHEMA);
        field(b, "version", VERSION);
        field(b, "origin", normalizedOrigin(origin));
        field(b, "organizationId", organization.toString());
        field(b, "automationId", automationId.toString());
        field(b, "automationVersionId", versionId.toString());
        b.append("  \"versionNumber\": ").append(versionNumber).append(",\n");
        field(b, "sha256", sha);
        b.append("  \"sizeBytes\": ").append(size).append(",\n");
        field(b, "fingerprint", fingerprint);
        b.append("  \"installedAt\": ").append(StrictJson.quote(clock.instant().truncatedTo(ChronoUnit.MILLIS).toString())).append("\n}\n");
        writer.write(dir.resolve(META), b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void field(StringBuilder b, String k, String v) {
        b.append("  ").append(StrictJson.quote(k)).append(": ").append(StrictJson.quote(v)).append(",\n");
    }

    // ------------------------------------------------------------------ consultar

    /**
     * O ZIP guardado desta versao, so se os metadados forem DESTE lugar (endereco, organizacao, automacao e versao gravados
     * = os do caminho) e o arquivo existir com o tamanho registrado. Nao rele o conteudo.
     */
    public synchronized Optional<Kept> find(String platformOrigin, UUID organization, UUID automationId, UUID versionId) {
        try {
            Path dir = versionDir(originKey(platformOrigin), organization, automationId, versionId);
            Path meta = dir.resolve(META);
            Path zip = dir.resolve(ZIP);
            if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) || Files.size(meta) > 64 * 1024 || !Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Map<String, Object> m = JsonReader.object(Files.readString(meta, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version")) || !normalizedOrigin(platformOrigin).equals(m.get("origin"))
                    || !organization.toString().equals(m.get("organizationId")) || !automationId.toString().equals(m.get("automationId"))
                    || !versionId.toString().equals(m.get("automationVersionId")) || !(m.get("sizeBytes") instanceof Long size) || size != Files.size(zip)
                    || !(m.get("versionNumber") instanceof Long number)) {
                return Optional.empty();
            }
            String sha = Objects.requireNonNull(JsonReader.text(m, "sha256"));
            String fingerprint = Objects.requireNonNull(JsonReader.text(m, "fingerprint"));
            if (!SHA.matcher(sha).matches() || !ExecutionPackage.validFingerprint(fingerprint)) return Optional.empty();
            return Optional.of(new Kept(zip, sha, size, fingerprint, Math.toIntExact(number), Instant.parse(JsonReader.text(m, "installedAt"))));
        } catch (IOException | JsonReader.Invalid | ImportProblem | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ interno

    private Path versionDir(String key, UUID organization, UUID automationId, UUID versionId) {
        // UUID.toString() e sempre a forma canonica: so hexadecimais e hifens
        Path dir = root.resolve(key).resolve(organization.toString()).resolve(automationId.toString()).resolve(versionId.toString()).normalize();
        if (!dir.startsWith(root) || dir.getNameCount() != root.getNameCount() + 4) throw new IllegalArgumentException("versao fora da pasta de pacotes baixados");
        return dir;
    }

    private static String normalizedOrigin(String origin) {
        String s = origin.strip().toLowerCase(Locale.ROOT);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Nome de pasta do endereco da Platform: 16 hexadecimais do SHA-256 do endereco normalizado. */
    static String originKey(String origin) throws ImportProblem {
        if (origin == null || origin.isBlank() || origin.length() > 2048) throw new ImportProblem(Code.DOWNLOAD_STORE_FAILED);
        return HexFormat.of().formatHex(ProjectImporter.sha256().digest(normalizedOrigin(origin).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    /** 64 hexadecimais em minusculas (com ou sem {@code sha256:}); {@code null} se nao for isso. */
    private static String hex(String text) {
        if (text == null) return null;
        String s = text.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("sha256:")) s = s.substring(7);
        return SHA.matcher(s).matches() ? s : null;
    }

    private static String sha256(Path f, SafeZip.Cancel cancel) throws IOException, ImportProblem {
        MessageDigest md = ProjectImporter.sha256();
        try (InputStream in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.requested()) throw new ImportProblem(Code.DOWNLOAD_CANCELLED);
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private ImportProblem failed(IOException e) {
        return Io.noSpace(e, root) ? new ImportProblem(Code.NO_SPACE) : new ImportProblem(Code.DOWNLOAD_STORE_FAILED);
    }
}
