package com.nekkora.client.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import com.nekkora.client.theme.ClientTheme;

/**
 * A unica coisa que o Client grava em disco: a preferencia visual ({@code tema}). Nada de sessao, token, conta,
 * organizacao ou tentativa de login. Arquivo ausente, ilegivel ou com valor desconhecido: Sakura Dark.
 */
public final class ClientPreferences {

    static final String THEME = "tema";

    private final Path file;

    public ClientPreferences(Path file) {
        this.file = file;
    }

    public ClientTheme theme() {
        if (!Files.isRegularFile(file)) {
            return ClientTheme.DEFAULT;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return ClientTheme.DEFAULT;
        }
        return ClientTheme.byLibId(p.getProperty(THEME));
    }

    /** Grava so a chave do tema, por arquivo temporario e troca atomica. Falha de disco nao impede o uso do aplicativo. */
    public boolean saveTheme(ClientTheme theme) {
        Properties p = new Properties();
        p.setProperty(THEME, theme.libId());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "Nekkora Agent - preferencia visual");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
