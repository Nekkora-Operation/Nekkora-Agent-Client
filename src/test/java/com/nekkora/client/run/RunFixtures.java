package com.nekkora.client.run;

import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecutionPackage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pacotes Python externos de teste (gerados em pasta temporaria), o Python dos testes e uma sessao controlavel. Os
 * scripts sao texto deste arquivo: nenhum deles fala com a rede, instala algo ou sai da pasta temporaria.
 */
final class RunFixtures {
    private RunFixtures() {}

    static final String PYTHON_ENV = "NEKKORA_TEST_PYTHON";
    static final String SKIP = "sem Python de teste (" + PYTHON_ENV + "): execucao real NAO conferida; teste pulado, nunca dado por aprovado";

    static final UUID ORG = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID WORKSPACE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final UUID OTHER_ORG = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID OTHER_WORKSPACE = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    static final UUID AUTOMATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final UUID AUTOMATION_VERSION = UUID.fromString("44444444-4444-4444-8444-444444444444");

    /** O Python dos testes que executam processos: SO a variavel {@code NEKKORA_TEST_PYTHON}. Sem ela, {@code null} (teste pulado). */
    static Path python() {
        String env = System.getenv(PYTHON_ENV);
        if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) return Path.of(env).toAbsolutePath();
        return null;
    }

    static RunContext context(ExecutionPackage p) {
        return new RunContext(ORG, WORKSPACE, p.fingerprint(), "Pacote de teste", null, null);
    }

    /** Modulo comum dos scripts: pasta de saida e sinal, pelas variaveis que o aplicativo entrega. */
    static final String COMUM = String.join("\n",
            "import json, os, pathlib",
            "",
            "def saida():",
            "    return pathlib.Path(os.environ['NEKKORA_SAIDA'])",
            "",
            "def sinal(desfecho):",
            "    pathlib.Path(os.environ['NEKKORA_SINAL']).write_text(json.dumps({'schema': 'nekkora-agent/external-signal', 'version': '1.0.0', 'outcome': desfecho}),",
            "                                                       encoding='utf-8')",
            "");

    /** Sucesso de negocio sinalizado; imprime um valor que NAO pode aparecer em nada que o aplicativo grava; anota onde e como rodou. */
    static final String SUCESSO = String.join("\n",
            "import json, os, sys",
            "from comum import saida, sinal",
            "print('SENTINELA-VALOR-DA-AUTOMACAO 12345')",
            "print('SENTINELA-VALOR-DA-AUTOMACAO erro', file=sys.stderr)",
            "(saida() / 'onde.json').write_text(json.dumps({'cwd': os.getcwd(), 'argv': sys.argv, 'env': sorted(os.environ.keys()), 'flags': [sys.flags.ignore_environment,",
            "    sys.flags.no_user_site, sys.flags.dont_write_bytecode]}), encoding='utf-8')",
            "sinal('BUSINESS_SUCCESS')",
            "");

    static final String SEM_SINAL = "print('terminei sem sinalizar')\n";
    static final String CODIGO_3 = "import sys\nsys.exit(3)\n";
    static final String FALHA_DE_NEGOCIO = "from comum import sinal\nsinal('BUSINESS_FAILURE')\n";

    /** Lanca um processo FILHO (um script do proprio pacote) e os dois esperam muito; grava os PIDs na pasta de saida. */
    static final String LENTO = String.join("\n",
            "import json, os, pathlib, subprocess, sys, time",
            "from comum import saida",
            "filho = subprocess.Popen([sys.executable, '-E', '-s', '-B', str(pathlib.Path(__file__).with_name('filho.py'))])",
            "(saida() / 'pids.json').write_text(json.dumps({'pai': os.getpid(), 'filho': filho.pid}), encoding='utf-8')",
            "time.sleep(900)",
            "");

    static final String FILHO = "import time\ntime.sleep(900)\n";

    /** ~12 MiB em cada fluxo: a saida precisa ser drenada para o processo nao travar. */
    static final String VOLUMOSO = String.join("\n",
            "import sys",
            "from comum import sinal",
            "bloco = ('SENTINELA-VALOR-DA-AUTOMACAO ' * 40 + '\\n') * 100",
            "for _ in range(100):",
            "    sys.stdout.write(bloco)",
            "    sys.stderr.write(bloco)",
            "sinal('BUSINESS_SUCCESS')",
            "");

    /** Efeito NAO idempotente: acrescenta uma linha num arquivo da pasta acima da saida e espera. */
    static final String EFEITO = String.join("\n",
            "import time",
            "from comum import saida",
            "with open(saida().parent / 'efeitos.txt', 'a', encoding='utf-8') as f:",
            "    f.write('efeito\\n')",
            "time.sleep(900)",
            "");

    /** Pacote externo valido em {@code dir}, com {@code main.py} = {@code main} e o modulo comum. */
    static Path pkg(Path dir, String name, String main) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.py", main);
        files.put("comum.py", COMUM);
        files.put("filho.py", FILHO);
        return pkg(dir, name, files, "main.py", ">=3.10", List.of());
    }

    static Path pkg(Path dir, String name, Map<String, String> content, String entry, String python, List<String> dependencies) throws IOException {
        Files.createDirectories(dir);
        Map<String, String> hashes = new TreeMap<>();
        for (Map.Entry<String, String> e : content.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            hashes.put(e.getKey(), ExecutionPackage.fileHash(e.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        StringBuilder b = new StringBuilder("{\n  \"schema\": \"nekkora-agent/external-python-package\",\n  \"version\": \"1.0.0\",\n  \"name\": \"").append(name)
                .append("\",\n  \"packageVersion\": \"1.0.0\",\n  \"entry\": \"").append(entry).append("\",\n  \"python\": \"").append(python)
                .append("\",\n  \"dependencies\": [");
        for (int i = 0; i < dependencies.size(); i++) b.append(i == 0 ? "" : ", ").append('"').append(dependencies.get(i)).append('"');
        b.append("],\n  \"files\": {");
        int n = 0;
        for (Map.Entry<String, String> e : hashes.entrySet()) b.append(n++ == 0 ? "\n" : ",\n").append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
        b.append("\n  }\n}\n");
        Files.writeString(dir.resolve(ExecutionPackage.EXTERNAL_DESCRIPTOR), b, StandardCharsets.UTF_8);
        return dir;
    }

    /** Sessao controlavel pelo teste. */
    static final class FakeSession implements SessionProbe {
        final AtomicBoolean locked = new AtomicBoolean();
        final AtomicReference<ErrorCode> problem = new AtomicReference<>();

        @Override
        public Session probe() {
            ErrorCode p = problem.get();
            if (p != null) return new Session(false, 0, false, p);
            boolean l = locked.get();
            return new Session(true, 1, l, l ? ErrorCode.EXEC_SESSION_LOCKED : null);
        }
    }

    /** Relogio do sistema com um deslocamento ajustavel (simula o salto de uma suspensao). */
    static final class ShiftClock extends Clock {
        final AtomicLong shift = new AtomicLong();

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plusMillis(shift.get());
        }
    }
}
