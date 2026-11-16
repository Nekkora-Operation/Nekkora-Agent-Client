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

    /** Remove o vinculo deste computador. Depois disso a maquina nao consegue mais se autenticar. */
    public void clear() throws IOException {
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
