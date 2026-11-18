package com.nekkora.client.run;

import com.nekkora.client.pack.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A sondagem de UM interpretador Python: versao, se e um ambiente virtual (e qual a instalacao-base) e a versao instalada
 * de cada distribuicao pedida. Nada e instalado, criado ou alterado.
 *
 * <p>Como roda: {@code <python> -I -B -c <codigo fixo> <nomes...>}, com caminho absoluto, argumentos separados, sem shell,
 * na pasta de trabalho do aplicativo (nunca na pasta do projeto) e so com as variaveis de {@link PythonRuntime#ENV_KEEP}.
 * O codigo e o deste arquivo: nao importa modulo do projeto e le so os METADADOS das distribuicoes instaladas
 * ({@code importlib.metadata}), sem importar nenhuma delas. {@code -I} tira da busca de modulos a pasta atual, a do script
 * e a de usuario, e ignora as variaveis {@code PYTHON*}; {@code -B} nao grava {@code .pyc}.
 *
 * <p>Limite que isto NAO remove: iniciar um interpretador executa a inicializacao dele (o modulo {@code site}, os
 * arquivos {@code .pth} e um eventual {@code sitecustomize} do proprio ambiente). O aplicativo nao tem como provar que um
 * arquivo chamado {@code python.exe} e um Python legitimo: a sondagem so e feita em candidatos que a descoberta limitada
 * encontrou ou no ambiente da pasta que a PESSOA indicou, com prazo, e o processo iniciado aqui e encerrado se nao
 * responder.
 */
public final class PythonProbe {
    private PythonProbe() {}

    /** Quem chama pode desistir: a sondagem em curso e encerrada (so o processo iniciado aqui). */
    @FunctionalInterface
    public interface Cancel {
        boolean cancelled();

        Cancel NEVER = () -> false;
    }

    public enum Failure {
        /** O arquivo nao existe mais, nao e um arquivo comum ou nao tem nome de interpretador. */
        MISSING,
        /** Iniciou e nao respondeu como um Python (saida inesperada, codigo de erro, nao iniciou). */
        NO_ANSWER,
        /** Nao respondeu dentro do prazo; o processo foi encerrado. */
        TIMEOUT,
        /** Quem chamou desistiu; o processo foi encerrado. */
        CANCELLED
    }

    public static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        private final Failure failure;

        Failed(Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /**
     * O que o interpretador respondeu. {@code installed}: nome pedido -&gt; versao instalada ({@code null} = nao instalada);
     * o mapa inteiro e {@code null} se o interpretador nao tem como ler metadados de distribuicao.
     */
    public record Info(Path python, String version, String prefix, String basePrefix, Map<String, String> installed) {
        /** Ambiente virtual: o prefixo do ambiente nao e o da instalacao-base. */
        public boolean virtual() {
            return prefix != null && basePrefix != null && !prefix.equalsIgnoreCase(basePrefix);
        }

        public int major() {
            return Integer.parseInt(version.split("\\.")[0]);
        }

        public int minor() {
            return Integer.parseInt(version.split("\\.")[1]);
        }
    }

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(12);
    static final String MARK = "NEKKORA-CLIENT-AMBIENTE ";
    static final String CODE = String.join("\n",
            "import json, sys",
            "info = {'python': '%d.%d.%d' % sys.version_info[:3], 'prefix': sys.prefix, 'base': getattr(sys, 'base_prefix', sys.prefix), 'deps': {}}",
            "try:",
            "    import importlib.metadata as m",
            "    for n in sys.argv[1:]:",
            "        try:",
            "            info['deps'][n] = m.version(n)",
            "        except Exception:",
            "            info['deps'][n] = None",
            "except Exception:",
            "    info['deps'] = None",
            "sys.stdout.write('NEKKORA-CLIENT-AMBIENTE ' + json.dumps(info) + '\\n')",
            "");

    /**
     * Sonda {@code python}. Bloqueia ate {@code timeout} (fora da linha de eventos). {@code distributions}: so nomes de
     * distribuicao ja validados pelo descritor do pacote; vao como argumentos separados, nunca dentro do codigo.
     */
    public static Info run(Path python, List<String> distributions, Path workDir, Duration timeout, Cancel cancel) throws Failed {
        Path exe;
        try {
            exe = PythonRuntime.validate(python);
        } catch (com.nekkora.client.pack.ExecRefused e) {
            throw new Failed(Failure.MISSING);
        }
        if (cancel.cancelled()) throw new Failed(Failure.CANCELLED);
        Process p = null;
        Path output = null;
        try {
            Files.createDirectories(workDir);
            output = workDir.resolve("sonda-ambiente-" + UUID.randomUUID() + ".saida");
            List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-I", "-B", "-c", CODE));
            cmd.addAll(distributions);
            // saida em arquivo: a espera com prazo nao depende de o processo fechar o fluxo
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
                    .redirectInput(ProcessBuilder.Redirect.PIPE);
            PythonRuntime.minimalEnvironment(pb.environment());
            p = pb.start();
            p.getOutputStream().close();
            long limit = System.nanoTime() + timeout.toNanos();
            while (!p.waitFor(40, TimeUnit.MILLISECONDS)) {
                if (cancel.cancelled()) throw new Failed(Failure.CANCELLED);
                if (System.nanoTime() >= limit) throw new Failed(Failure.TIMEOUT);
            }
            String text;
            try (InputStream in = Files.newInputStream(output)) {
                text = new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
            }
            int at = text.lastIndexOf(MARK);
            if (p.exitValue() != 0 || at < 0) throw new Failed(Failure.NO_ANSWER);
            Map<String, Object> m = JsonReader.object(text.substring(at + MARK.length()).lines().findFirst().orElse(""));
            String version = JsonReader.text(m, "python");
            String prefix = JsonReader.text(m, "prefix");
            String base = JsonReader.text(m, "base");
            if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || prefix == null || base == null) throw new Failed(Failure.NO_ANSWER);
            Map<String, String> installed = null;
            if (m.get("deps") instanceof Map<?, ?> deps) {
                installed = new LinkedHashMap<>();
                for (String name : distributions) {
                    Object v = deps.get(name);
                    installed.put(name, v instanceof String s && !s.isBlank() ? s : null);
                }
            }
            return new Info(exe, version, prefix, base, installed);
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            throw new Failed(Failure.NO_ANSWER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed(Failure.CANCELLED);
        } finally {
            if (p != null && p.isAlive()) {
                // so o que foi iniciado aqui: o processo da sondagem e o que ele tiver aberto
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                try {
                    p.waitFor(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (output != null) remove(output);
        }
    }

    /**
     * Apaga o arquivo de saida da sondagem. No Windows, logo depois de o processo terminar (ou ser encerrado), o arquivo pode
     * ficar "em uso por outro processo" por um instante (o identificador do processo ainda fechando, ou o antivirus lendo o
     * arquivo recem-criado): a remocao e tentada de novo por ate 1 segundo. Se ainda assim nao der, o arquivo fica na pasta de
     * trabalho do aplicativo e a proxima limpeza o remove.
     */
    private static void remove(Path output) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(output);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
