package com.nekkora.client.pack;

import com.nekkora.client.pack.ImportProblem.Code;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Classificacao das falhas de disco (sem texto do sistema na tela: so o motivo do vocabulario). */
final class Io {
    private Io() {}

    /** Falha na pasta de trabalho do Agent. */
    static ImportProblem work(IOException e, Path dir) {
        return noSpace(e, dir) ? new ImportProblem(Code.NO_SPACE) : new ImportProblem(Code.WORK_FAILED);
    }

    /** Falha na pasta escolhida pela pessoa. */
    static ImportProblem target(IOException e, Path dir) {
        return noSpace(e, dir) ? new ImportProblem(Code.NO_SPACE) : new ImportProblem(Code.WRITE_FAILED);
    }

    static boolean noSpace(IOException e, Path dir) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (m.contains("not enough space") || m.contains("no space") || m.contains("disk full") || m.contains("espaço")) return true;
        try {
            return dir != null && Files.getFileStore(dir).getUsableSpace() < 1024 * 1024;
        } catch (IOException | RuntimeException x) {
            return false;
        }
    }

    /** Apaga uma arvore da operacao (melhor esforco; nunca segue ligacao). */
    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // melhor esforco: a proxima operacao limpa as sobras
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // idem
        }
    }
}
