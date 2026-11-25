package com.nekkora.client.run;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Remocao de uma pasta do aplicativo SEM seguir ligacoes. No Windows, uma juncao ({@code mklink /J}) se apresenta ao Java como
 * pasta comum E como "outro" (ponto de nova analise): {@code Files.walk} entraria nela e apagaria o que esta do outro lado. Aqui,
 * ligacao simbolica, juncao ou qualquer ponto de nova analise e removido como ENTRADA (a ligacao some; o destino fica intacto),
 * nunca percorrido.
 */
public final class SafeTree {

    private SafeTree() {
    }

    /** Apaga {@code dir} e o que esta dentro, sem seguir ligacoes. {@code true}: nada sobrou. Falha de disco: fica o que nao saiu. */
    public static boolean delete(Path dir) {
        try {
            BasicFileAttributes a = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            remove(dir, a, 0);
        } catch (java.nio.file.NoSuchFileException e) {
            return true;
        } catch (IOException | RuntimeException e) {
            // o que ficou sai na proxima limpeza
        }
        return !Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
    }

    /** Ligacao simbolica, juncao ou outro ponto de nova analise: e apagado como entrada, sem entrar. */
    public static boolean link(BasicFileAttributes a) {
        return a.isSymbolicLink() || a.isOther();
    }

    private static void remove(Path p, BasicFileAttributes a, int depth) throws IOException {
        if (a.isDirectory() && !link(a) && depth < 64) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path child : ds) {
                    try {
                        remove(child, Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), depth + 1);
                    } catch (IOException e) {
                        // segue com o resto; a pasta nao sai, e a proxima limpeza tenta de novo
                    }
                }
            }
        }
        try {
            Files.deleteIfExists(p);
        } catch (java.nio.file.AccessDeniedException e) {
            // arquivo somente leitura (o pip as vezes deixa assim): tira o atributo e tenta de novo
            try {
                Files.setAttribute(p, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                Files.deleteIfExists(p);
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException x) {
                throw e;
            }
        }
    }
}
