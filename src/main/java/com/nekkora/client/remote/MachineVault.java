package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.nekkora.client.auth.Json;

/**
 * O vinculo DESTA maquina com a Platform para execucao remota, no disco: a identidade (sem segredo) e o segredo da
 * credencial, este sempre protegido pelo {@link Keyring}. Um arquivo so, {@code <dados>/remota/maquina.json}, gravado de
 * forma atomica. Nada aqui e a sessao de uma pessoa: o token de quem entra no aplicativo nunca passa por esta classe.
 *
 * <p>Arquivo ausente, ilegivel, de outro usuario do Windows ou fora do formato: nao ha vinculo utilizavel
 * ({@link #load()} vazio), nunca um vinculo "meio lido".
 */
public final class MachineVault {

    static final String FILE = "maquina.json";
    /** Renovacao em curso: o segredo NOVO, protegido, e a credencial que ele vai substituir. */
    static final String PENDING = "renovacao.json";
    static final String SALT = "inventario.sal";
    private static final int MAX_FILE = 16 * 1024;
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern INSTANT = Pattern.compile("^[0-9T:.Z+-]{20,40}$");
    private static final Pattern ORIGIN = Pattern.compile("^https://[A-Za-z0-9.-]{1,253}(:[1-9][0-9]{1,4})?$");

    /**
     * O vinculo guardado. {@code origin}: o endereco da Platform em que a maquina foi habilitada; a credencial so e usada
     * nele. {@code paused}: o ultimo recebimento conhecido (a referencia e o servidor). {@link #toString()} nunca mostra o
     * segredo.
     */
    public record Link(String origin, String organizationId, String agentId, String workerId, String credentialId, String credentialExpiresAt,
                       String secret, boolean paused) {

        public Link {
            if (!ORIGIN.matcher(origin).matches() || !UUID.matcher(organizationId).matches() || !UUID.matcher(agentId).matches()
                    || !UUID.matcher(workerId).matches() || !UUID.matcher(credentialId).matches() || !INSTANT.matcher(credentialExpiresAt).matches()
                    || !SECRET.matcher(secret).matches()) {
                throw new IllegalArgumentException("vinculo");
            }
        }

        /** A credencial da maquina, montada so em memoria, para o cabecalho de autorizacao. */
        String credential() {
            return "nka1." + organizationId + "." + credentialId + "." + secret;
        }

        Link withPaused(boolean value) {
            return new Link(origin, organizationId, agentId, workerId, credentialId, credentialExpiresAt, secret, value);
        }

        /** A mesma maquina com a credencial NOVA de uma renovacao. */
        Link renewed(String newCredentialId, String newExpiresAt, String newSecret) {
            return new Link(origin, organizationId, agentId, workerId, newCredentialId, newExpiresAt, newSecret, paused);
        }

        Link withExpiry(String expiresAt) {
            return new Link(origin, organizationId, agentId, workerId, credentialId, expiresAt, secret, paused);
        }

        @Override
        public String toString() {
            return "Link[agent=" + agentId + "]";
        }
    }

    private final Path dir;
    private final Keyring keyring;

    public MachineVault(Path dir, Keyring keyring) {
        this.dir = dir;
        this.keyring = keyring;
    }

    /** {@code true} se este computador consegue proteger o segredo; sem isso, nada e gravado. */
    public boolean available() {
        return keyring.available();
    }

    public Optional<Link> load() {
        Path f = dir.resolve(FILE);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > MAX_FILE) {
                return Optional.empty();
            }
            Map<String, Object> o = Json.object(Files.readString(f, StandardCharsets.UTF_8));
            if (!(o.get("versao") instanceof Double v) || v != 1.0 || !(o.get("pausado") instanceof Boolean paused)) {
                return Optional.empty();
            }
            byte[] plain = keyring.unprotect(Base64.getDecoder().decode(text(o, "segredo")));
            try {
                return Optional.of(new Link(text(o, "origem"), text(o, "organizacao"), text(o, "agente"), text(o, "posto"), text(o, "credencial"),
                        text(o, "validade"), new String(plain, StandardCharsets.US_ASCII), paused));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public void save(Link link) throws IOException {
        if (!keyring.available()) {
            throw new IOException("protecao indisponivel");
        }
        byte[] plain = link.secret().getBytes(StandardCharsets.US_ASCII);
        String blob;
        try {
            blob = Base64.getEncoder().encodeToString(keyring.protect(plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        String json = "{\"versao\":1,\"origem\":" + Json.quote(link.origin()) + ",\"organizacao\":" + Json.quote(link.organizationId()) + ",\"agente\":"
                + Json.quote(link.agentId()) + ",\"posto\":" + Json.quote(link.workerId()) + ",\"credencial\":" + Json.quote(link.credentialId())
                + ",\"validade\":" + Json.quote(link.credentialExpiresAt()) + ",\"pausado\":" + link.paused() + ",\"segredo\":" + Json.quote(blob) + "}";
        Files.createDirectories(dir);
        Path tmp = dir.resolve(FILE + ".novo");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * O segredo novo de uma renovacao em curso DA credencial indicada, se houver. E gravado antes de a troca ser pedida:
     * se a resposta se perder ou o aplicativo fechar, a troca e repetida com o MESMO segredo. Arquivo de outra credencial,
     * ilegivel ou fora do formato: nao ha renovacao em curso.
     */
    Optional<String> pending(String fromCredentialId) {
        Path f = dir.resolve(PENDING);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > MAX_FILE) {
                return Optional.empty();
            }
            Map<String, Object> o = Json.object(Files.readString(f, StandardCharsets.UTF_8));
            if (!fromCredentialId.equals(o.get("credencial"))) {
                return Optional.empty();
            }
            byte[] plain = keyring.unprotect(Base64.getDecoder().decode(text(o, "segredo")));
            try {
                String secret = new String(plain, StandardCharsets.US_ASCII);
                return SECRET.matcher(secret).matches() ? Optional.of(secret) : Optional.empty();
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    void savePending(String fromCredentialId, String secret) throws IOException {
        if (!keyring.available() || !SECRET.matcher(secret).matches() || !UUID.matcher(fromCredentialId).matches()) {
            throw new IOException("protecao indisponivel");
        }
        byte[] plain = secret.getBytes(StandardCharsets.US_ASCII);
        String blob;
        try {
            blob = Base64.getEncoder().encodeToString(keyring.protect(plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve(PENDING + ".novo");
        Files.writeString(tmp, "{\"versao\":1,\"credencial\":" + Json.quote(fromCredentialId) + ",\"segredo\":" + Json.quote(blob) + "}", StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dir.resolve(PENDING), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve(PENDING), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * O sal deste computador para os identificadores opacos do inventario (32 bytes aleatorios, criado na primeira vez). Com
     * ele, o mesmo ambiente tem sempre o mesmo identificador NESTA maquina, e ninguem de fora chega do identificador ao
     * caminho. Nao e uma credencial; sai junto com o vinculo.
     */
    byte[] inventorySalt() throws IOException {
        Path f = dir.resolve(SALT);
        if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && Files.size(f) == 32) {
            return Files.readAllBytes(f);
        }
        byte[] salt = new byte[32];
        new java.security.SecureRandom().nextBytes(salt);
        Files.createDirectories(dir);
        Files.write(f, salt);
        return salt;
    }

    /** O diario da entrega fica junto do vinculo, com a mesma protecao, e sai junto com ele. */
    AttemptJournal journal() {
        return new AttemptJournal(dir, keyring);
    }

    /** O prazo de guarda quando nada foi configurado, em dias. */
    public static final int DEFAULT_RETENTION_DAYS = 7;
    /** O arquivo, na pasta {@code remota/}, onde o prazo de guarda pode ser configurado: um numero inteiro de dias, de 1 a 90. */
    public static final String RETENTION_FILE = "prazo-de-guarda.txt";
    static final int MAX_RETENTION_DAYS = 90;

    /**
     * Por quantos dias fica neste computador o que a Platform nao confirmou ter recebido (a fila de registros e a pasta de
     * saida de uma tentativa ja terminada). Configuravel SEM mudar o programa: a primeira linha de {@link #RETENTION_FILE}, um
     * inteiro de 1 a 90. Arquivo ausente, ilegivel ou com outro conteudo: {@link #DEFAULT_RETENTION_DAYS} - um valor invalido
     * nunca encurta nem alonga a guarda. E lido a cada uso: mudar o arquivo vale a partir da limpeza seguinte.
     */
    public int retentionDays() {
        try {
            Path f = dir.resolve(RETENTION_FILE);
            if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 64) {
                return DEFAULT_RETENTION_DAYS;
            }
            String first = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8).strip().split("\\R", 2)[0].strip();
            if (!first.matches("[0-9]{1,2}")) {
                return DEFAULT_RETENTION_DAYS;
            }
            int days = Integer.parseInt(first);
            return days >= 1 && days <= MAX_RETENTION_DAYS ? days : DEFAULT_RETENTION_DAYS;
        } catch (IOException | RuntimeException e) {
            return DEFAULT_RETENTION_DAYS;
        }
    }

    java.time.Duration retention() {
        return java.time.Duration.ofDays(retentionDays());
    }

    /** O registro minimo do que ficou sem confirmacao e do que foi removido pelo prazo (ver {@link LossLedger}). */
    LossLedger losses() {
        return new LossLedger(dir.resolve("guarda"), keyring);
    }

    /** As tentativas cujo fim foi informado sem a afirmacao de que nada delas continua em andamento aqui (ver {@link AbsenceLedger}). */
    AbsenceLedger absences() {
        return new AbsenceLedger(dir.resolve("confirmar"), keyring);
    }

    /** Uma fila de registros parada no disco, sem confirmacao. {@code removeAt}: a partir de quando a limpeza a remove. */
    record QueueHeld(String attemptId, long sinceEpochSecond, long removeAtEpochSecond) {
    }

    /** As filas de registros guardadas (fora a da tentativa pendente). O prazo conta da ultima gravacao do arquivo. */
    java.util.List<QueueHeld> heldLogQueues(String keepAttemptId) {
        java.util.List<QueueHeld> out = new java.util.ArrayList<>();
        Path root = dir.resolve("fila");
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        long keep = retention().toSeconds();
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.fila")) {
            for (Path f : ds) {
                String name = String.valueOf(f.getFileName());
                String key = name.substring(0, name.length() - ".fila".length());
                if (key.equals(keepAttemptId) || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                long since = Files.getLastModifiedTime(f, java.nio.file.LinkOption.NOFOLLOW_LINKS).toInstant().getEpochSecond();
                out.add(new QueueHeld(key, since, since + keep));
            }
        } catch (IOException | RuntimeException e) {
            // o que ja foi lido vale
        }
        out.sort(java.util.Comparator.comparing(QueueHeld::sinceEpochSecond).thenComparing(QueueHeld::attemptId));
        return out;
    }

    /** A fila de registros de uma tentativa (um arquivo protegido em {@code fila/}). {@code secrets}: o que nunca entra nela. */
    LogQueue logQueue(String attemptId, java.util.List<String> secrets) {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("tentativa");
        }
        return new LogQueue(dir.resolve("fila").resolve(attemptId + ".fila"), keyring, secrets);
    }

    /** A data do arquivo da fila (de onde o prazo de guarda conta). Nulo: nao ha arquivo. */
    java.nio.file.attribute.FileTime logQueueTime(String attemptId) {
        try {
            Path f = dir.resolve("fila").resolve(attemptId + ".fila");
            return Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? Files.getLastModifiedTime(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Devolve ao arquivo da fila a data que ele tinha: tentar enviar de novo nao recomeca o prazo de guarda. */
    void logQueueTime(String attemptId, java.nio.file.attribute.FileTime time) {
        if (time == null) {
            return;
        }
        try {
            Path f = dir.resolve("fila").resolve(attemptId + ".fila");
            if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.setLastModifiedTime(f, time);
            }
        } catch (IOException | RuntimeException e) {
            // a fila fica um pouco mais, nunca menos
        }
    }

    /**
     * Apaga as filas de registros que nao sao a da tentativa pendente e estao paradas ha mais que o prazo de guarda
     * ({@link #retentionDays}). A fila da tentativa pendente (em andamento, ou ainda enviando) nunca entra na conta. So arquivos
     * {@code <uuid>.fila} dentro de {@code fila/}. Devolve as tentativas cujas filas apagou.
     */
    java.util.List<String> sweepLogQueues(String keepAttemptId, java.time.Instant now) {
        java.util.List<String> n = new java.util.ArrayList<>();
        Path root = dir.resolve("fila");
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return n;
        }
        java.time.Duration keep = retention();
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.fila")) {
            for (Path f : ds) {
                String name = String.valueOf(f.getFileName());
                String key = name.substring(0, name.length() - ".fila".length());
                if (key.equals(keepAttemptId) || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.getLastModifiedTime(f, java.nio.file.LinkOption.NOFOLLOW_LINKS).toInstant().plus(keep).isBefore(now)) {
                    Files.deleteIfExists(f);
                    n.add(key);
                }
            }
        } catch (IOException | RuntimeException e) {
            // fica para a proxima limpeza
        }
        return n;
    }

    void clearPending() throws IOException {
        Files.deleteIfExists(dir.resolve(PENDING + ".novo"));
        Files.deleteIfExists(dir.resolve(PENDING));
    }

    /** Remove o vinculo deste computador. Depois disso a maquina nao consegue mais se autenticar. */
    public void clear() throws IOException {
        // os tokens das atribuicoes nao servem mais: o registro de perda fica (sem token), o resto sai
        losses().unlink(java.time.Instant.now().getEpochSecond());
        absences().clear();
        clearPending();
        journal().clear();
        Files.deleteIfExists(dir.resolve(SALT));
        Files.deleteIfExists(dir.resolve(FILE + ".novo"));
        Files.deleteIfExists(dir.resolve(FILE));
    }

    /** Ha um arquivo de vinculo, legivel ou nao (para a tela distinguir "nunca habilitado" de "vinculo ilegivel"). */
    public boolean present() {
        return Files.exists(dir.resolve(FILE), LinkOption.NOFOLLOW_LINKS);
    }

    private static String text(Map<String, Object> o, String key) {
        if (o.get(key) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(key);
    }
}
