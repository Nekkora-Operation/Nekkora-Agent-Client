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
 * As preferencias que o Client grava em disco: a visual ({@code tema}) e, desde AMBIENTE-PYTHON-001, o limite do cache do pip do
 * ambiente gerenciado ({@code ambienteGerenciado.cacheMiB}). Nada de sessao, conta, organizacao ou tentativa de entrada. Arquivo
 * ausente, ilegivel ou com valor desconhecido: Sakura Dark e o limite padrao. Gravar uma preferencia preserva as outras.
 */
public final class ClientPreferences {

    static final String THEME = "tema";
    static final String CACHE_MIB = "ambienteGerenciado.cacheMiB";
    /** O limite padrao do cache (MiB) e a faixa aceita: 0 desliga o cache. */
    public static final int DEFAULT_CACHE_MIB = 2048;
    public static final int MAX_CACHE_MIB = 20480;

    private final Path file;

    public ClientPreferences(Path file) {
        this.file = file;
    }

    private synchronized Properties read() {
        Properties p = new Properties();
        if (!Files.isRegularFile(file)) {
            return p;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return new Properties();
        }
        return p;
    }

    public ClientTheme theme() {
        return ClientTheme.byLibId(read().getProperty(THEME));
    }

    /** Grava a chave do tema (as outras preferencias ficam), por arquivo temporario e troca atomica. Falha de disco nao impede o uso do aplicativo. */
    public boolean saveTheme(ClientTheme theme) {
        return save(THEME, theme.libId());
    }

    /** O limite do cache do pip do ambiente gerenciado, em MiB (0 a 20480; ausente ou fora da faixa: 2048). */
    public int managedCacheMiB() {
        String v = read().getProperty(CACHE_MIB);
        try {
            int n = v == null ? DEFAULT_CACHE_MIB : Integer.parseInt(v.strip());
            return n < 0 || n > MAX_CACHE_MIB ? DEFAULT_CACHE_MIB : n;
        } catch (NumberFormatException e) {
            return DEFAULT_CACHE_MIB;
        }
    }

    /** Grava o limite do cache (MiB). Fora de 0 a 20480: nada e gravado. */
    public boolean saveManagedCacheMiB(int mib) {
        return mib >= 0 && mib <= MAX_CACHE_MIB && save(CACHE_MIB, Integer.toString(mib));
    }

    private synchronized boolean save(String key, String value) {
        Properties p = read();
        p.setProperty(key, value);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "Nekkora Agent - preferencias");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
