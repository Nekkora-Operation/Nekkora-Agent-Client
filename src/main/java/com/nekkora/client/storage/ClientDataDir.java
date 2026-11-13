package com.nekkora.client.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Pasta de dados PROPRIA do Client: {@code %LOCALAPPDATA%\Nekkora\AgentClient}. Nunca a do Nekkora Agent
 * ({@code ...\Nekkora\Agent}), que este aplicativo nao le nem altera: qualquer caminho que caia nela, ou dentro dela, e
 * recusado.
 *
 * <p>Ordem: propriedade {@code nekkora.client.dataDir} (launcher), variavel {@code NEKKORA_AGENT_CLIENT_DATA_DIR}
 * (desenvolvimento e testes), padrao.
 */
public final class ClientDataDir {

    public static final String PROPERTY = "nekkora.client.dataDir";
    public static final String ENVIRONMENT = "NEKKORA_AGENT_CLIENT_DATA_DIR";

    private final Path root;

    private ClientDataDir(Path root) {
        this.root = root;
    }

    public static ClientDataDir resolve() {
        return resolve(System.getProperties(), System.getenv());
    }

    static ClientDataDir resolve(Properties properties, Map<String, String> env) {
        String chosen = properties.getProperty(PROPERTY);
        if (chosen == null || chosen.isBlank()) {
            chosen = env.get(ENVIRONMENT);
        }
        Path base = localAppData(properties, env);
        Path root = chosen == null || chosen.isBlank() ? base.resolve("Nekkora").resolve("AgentClient") : Path.of(chosen);
        root = root.toAbsolutePath().normalize();
        Path agent = base.resolve("Nekkora").resolve("Agent").toAbsolutePath().normalize();
        if (root.startsWith(agent)) {
            throw new IllegalStateException("a pasta de dados do Client nao pode ser a do Nekkora Agent");
        }
        return new ClientDataDir(root);
    }

    private static Path localAppData(Properties properties, Map<String, String> env) {
        String local = env.get("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local);
        }
        return Path.of(properties.getProperty("user.home", "."), "AppData", "Local");
    }

    public Path root() {
        return root;
    }

    public Path preferencesFile() {
        return root.resolve("preferencias.properties");
    }

    public Path lockFile() {
        return root.resolve("client.lock");
    }

    public void create() throws IOException {
        Files.createDirectories(root);
    }
}
