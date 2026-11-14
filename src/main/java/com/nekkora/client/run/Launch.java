package com.nekkora.client.run;

import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.ExecutionPackage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Do pacote aprovado ao processo: copia CONFERIDA para a pasta da execucao (o processo roda da copia: mudar a pasta
 * guardada depois da conferencia nao alcanca a execucao), a linha de comando (lista fechada, argumentos separados, sem
 * shell) e a leitura do sinal que o script publica.
 *
 * <p>Portado de {@code com.nekkora.agent.exec.Launch} (Nekkora-Agent 9b1e7e1), so para o pacote Python externo.
 */
public final class Launch {
    private Launch() {}

    /** O que lancar: executavel conhecido, argumentos em lista, pasta de trabalho controlada. */
    public record Spec(Path executable, List<String> args, Path workDir) {
        public Spec {
            args = List.copyOf(args);
        }

        public List<String> command() {
            List<String> c = new ArrayList<>();
            c.add(executable.toString());
            c.addAll(args);
            return c;
        }
    }

    /** Copia so os arquivos listados (e o descritor) e confere a copia contra a impressao digital aprovada. */
    public static Path snapshot(ExecutionPackage pkg, Path runDir) throws ExecRefused {
        Path copy = runDir.resolve("pacote");
        try {
            Files.createDirectories(copy);
            List<String> all = new ArrayList<>(pkg.files().keySet());
            all.add(ExecutionPackage.EXTERNAL_DESCRIPTOR);
            for (String rel : all) {
                Path from = pkg.dir().resolve(rel).normalize();
                Path to = copy.resolve(rel).normalize();
                if (!from.startsWith(pkg.dir()) || !to.startsWith(copy) || Files.isSymbolicLink(from)) throw new ExecRefused(ErrorCode.EXEC_PACKAGE_UNSAFE);
                Files.createDirectories(to.getParent());
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            }
            ExecutionPackage.verify(copy, pkg.files());
            if (!pkg.fingerprint().equals(ExecutionPackage.fingerprint(copy, pkg.files()))) {
                throw new ExecRefused(ErrorCode.EXEC_PACKAGE_CHANGED);
            }
            return copy;
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
        }
    }

    /**
     * Linha de comando: {@code python -E -s -B <entrada>}, da copia conferida, com o Python que a pessoa preparou e sem
     * nenhum argumento de fora (a pasta de saida e o sinal opcional chegam por variavel de ambiente).
     */
    public static Spec spec(ExecutionPackage pkg, Path python, Path copy) {
        return new Spec(python, List.of("-E", "-s", "-B", pkg.entry()), copy);
    }

    /** O sinal que o script publicou na pasta de saida da execucao, ou {@code NONE}. */
    public static ExternalSignal.Signal reported(Path output) {
        return ExternalSignal.readSignal(output.resolve(ExternalSignal.SIGNAL_FILE));
    }

    /** Estado final: codigo 0 sem sinal = termino sem erro sinalizado ({@code AGT-EXT-001}), nunca sucesso de negocio. */
    public static Outcome classify(ExternalSignal.Signal signal, int exitCode) {
        ExternalSignal.Outcome o = ExternalSignal.classify(exitCode, signal);
        return new Outcome(o.state(), o.code());
    }

    public record Outcome(ExecutionState state, ErrorCode code) {}
}
