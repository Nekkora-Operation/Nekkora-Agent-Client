package com.nekkora.client.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pacote Python EXTERNO. O vetor sintetico e o MESMO do Nekkora Agent (9b1e7e1), da API da Platform e do empacotador
 * Python: o valor esperado esta escrito aqui, de modo que qualquer mudanca na regra da impressao, no hash por arquivo ou
 * no formato do descritor quebra este teste.
 */
class ExternalPackageTest {
    static final String MAIN = "sha256:dbccf933401a15cfe345e90891e20b5a05a495f552e4577b80c052276604e24f";
    static final String BIN = "sha256:d2ad9277baaee14856d20ec2b21f87a0cb8a7f86c6ef090fd5a082b1e85135ac";
    static final String FINGERPRINT = "sha256:e71ca1e0aedd2842d09809d5634134f3f23fb4d2ca0c5d90c370bb15474595f9";

    @TempDir
    Path tmp;

    static String descriptor(String entry, String deps, String files) {
        return "{\"schema\": \"nekkora-agent/external-python-package\", \"version\": \"1.0.0\", \"name\": \"vetor\", \"packageVersion\": \"1.0.0\", "
                + "\"entry\": \"" + entry + "\", \"python\": \">=3.10,<3.14\", \"dependencies\": " + deps + ", \"files\": " + files + "}\r\n";
    }

    static final String FILES = "{\"main.py\": \"" + MAIN + "\", \"util/x.bin\": \"" + BIN + "\"}";

    Path vector(String name, String descriptor) throws Exception {
        return vector(tmp.resolve(name), descriptor);
    }

    static Path vector(Path d, String descriptor) throws Exception {
        Files.createDirectories(d);
        Files.writeString(d.resolve("nekkora-external.json"), descriptor, StandardCharsets.UTF_8);
        Files.writeString(d.resolve("main.py"), "print('ola')\r\n", StandardCharsets.UTF_8);
        Files.createDirectories(d.resolve("util"));
        Files.write(d.resolve("util").resolve("x.bin"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        return d;
    }

    @Test
    void vetorSinteticoIgualAoDoAgentDaApiEDoGerador() throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(vector("v", descriptor("main.py", "[]", FILES)));
        assertEquals(ExecutionPackage.Kind.EXTERNAL_PYTHON, p.kind());
        assertEquals(FINGERPRINT, p.fingerprint());
        assertEquals("nekkora-agent/external-python-fingerprint/1", ExecutionPackage.EXTERNAL_RULE);
        assertEquals("nekkora-agent/external-python-package", ExecutionPackage.EXTERNAL_SCHEMA);
        assertEquals("nekkora-external.json", ExecutionPackage.EXTERNAL_DESCRIPTOR);
        assertEquals("EXTERNAL_PYTHON", ExecutionPackage.Kind.EXTERNAL_PYTHON.name(), "o nome do tipo entra no texto da impressao");
        assertEquals("main.py", p.entry());
        assertEquals("vetor", p.name());
        assertEquals(">=3.10,<3.14", p.pythonRequirement());
        assertEquals("1.0.0", p.packageVersion());
        assertEquals("e71ca1e0aedd", p.shortFingerprint());
        // hash por arquivo: texto com CRLF conta como LF; binario conta em bytes
        assertEquals(MAIN, ExecutionPackage.fileHash("print('ola')\r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(MAIN, ExecutionPackage.fileHash("print('ola')\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(BIN, ExecutionPackage.fileHash(new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01}));
        // a mesma conta a partir dos bytes do descritor (o empacotador calcula antes de gravar)
        assertEquals(FINGERPRINT, ExecutionPackage.externalFingerprint(Files.readAllBytes(p.dir().resolve("nekkora-external.json")), p.files()));
        assertEquals(FINGERPRINT, ExecutionPackage.fingerprint(p.dir(), p.files()));
    }

    @Test
    void recusasDoPacoteExterno() throws Exception {
        Path changed = vector("alterado", descriptor("main.py", "[]", FILES));
        Files.writeString(changed.resolve("main.py"), "print('outro')\n");
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(changed)).code());
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSAFE,
                assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(vector("fora", descriptor("../fora.py", "[]", FILES)))).code());
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID,
                assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(vector("dep", descriptor("main.py", "[\"requests>=2\"]", FILES)))).code());
        Path venv = vector("venv", descriptor("main.py", "[]", "{\"main.py\": \"" + MAIN + "\", \"venv/pyvenv.cfg\": \"" + BIN + "\"}"));
        Files.createDirectories(venv.resolve("venv"));
        Files.write(venv.resolve("venv").resolve("pyvenv.cfg"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSAFE, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(venv)).code());
        Path both = vector("ambos", descriptor("main.py", "[]", FILES));
        Files.writeString(both.resolve("manifest.json"), "{}");
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(both)).code());
        assertEquals(ErrorCode.EXEC_PACKAGE_NOT_FOUND, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(tmp.resolve("nao-existe"))).code());
        Path code = Files.createDirectories(tmp.resolve("so-codigo"));
        Files.writeString(code.resolve("main.py"), "print(1)\n");
        assertEquals(ErrorCode.EXEC_PACKAGE_UNKNOWN, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(code)).code());
    }

    /** Arquivos que nunca entram num pacote: a lista e a do Nekkora Agent, sem mudanca. */
    @Test
    void arquivosProibidos() {
        for (String rel : new String[] {".venv/x.py", "venv/x.py", "env/x.py", "a/__pycache__/x.py", ".git/config", ".hg/x", ".svn/x", ".idea/x", ".vscode/x",
                "node_modules/x.js", ".env", ".env.local", "chave.pem", "a/b.key", "c.pfx", "c.p12", "id_rsa", "id_rsa.pub", "id_ed25519", ".netrc", ".pypirc",
                "m.pyc", "VENV/x.py", "A/CERT.PEM"}) {
            assertTrue(ExecutionPackage.forbidden(rel), rel);
        }
        for (String rel : new String[] {"main.py", "config/parametros.json", "ambiente/x.py", "envio.py", "chaves/leia-me.txt"}) {
            assertFalse(ExecutionPackage.forbidden(rel), rel);
        }
    }

    /**
     * Pacote do Studio e pacote de laboratorio existem, mas este aplicativo nao os aceita: a recusa tem codigo e texto
     * proprios (nunca "pacote desconhecido", nunca aceito em silencio). Um {@code manifest.json} de outra ferramenta nao e
     * pacote nenhum.
     */
    @Test
    void pacoteDoStudioEDeLaboratorioSaoRecusadosComMotivoClaro() throws Exception {
        Path studio = Files.createDirectories(tmp.resolve("studio"));
        Files.writeString(studio.resolve("main.py"), "print(1)\n");
        Files.writeString(studio.resolve("manifest.json"), "{\"schema\": \"nekkora-studio/codegen-manifest\", \"version\": \"1.4.0\"}");
        ExecRefused s = assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(studio));
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSUPPORTED, s.code());
        assertEquals("AGT-EXE-030", s.code().code());
        assertTrue(s.code().description().contains("Studio") && s.code().description().contains("nekkora-external.json"), s.code().description());
        Path lab = Files.createDirectories(tmp.resolve("lab"));
        Files.writeString(lab.resolve("lab.py"), "print(1)\n");
        Files.writeString(lab.resolve("nekkora-agent-lab.json"), "{\"schema\": \"nekkora-agent/lab-package\", \"version\": \"1.0.0\"}");
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSUPPORTED, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(lab)).code());
        Path other = Files.createDirectories(tmp.resolve("outra-ferramenta"));
        Files.writeString(other.resolve("main.py"), "print(1)\n");
        Files.writeString(other.resolve("manifest.json"), "{\"name\": \"app web\"}");
        assertEquals(ErrorCode.EXEC_PACKAGE_UNKNOWN, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(other)).code());
    }

    @Test
    void faixaDePython() {
        assertTrue(ExecutionPackage.pythonSatisfies(">=3.10,<3.14", 3, 12));
        assertFalse(ExecutionPackage.pythonSatisfies(">=3.10,<3.14", 3, 14));
        assertFalse(ExecutionPackage.pythonSatisfies(">=3.10,<3.14", 3, 9));
        assertTrue(ExecutionPackage.pythonSatisfies("==3.12.*", 3, 12));
        assertFalse(ExecutionPackage.pythonSatisfies("==3.12.*", 3, 13));
        assertTrue(ExecutionPackage.pythonSatisfies("~=3.11", 3, 13));
        assertFalse(ExecutionPackage.pythonSatisfies("~=3.11", 4, 0));
    }

    /** Os codigos e os textos mantidos sao os do Nekkora Agent, letra por letra (amostra dos que a tela mais mostra). */
    @Test
    void textosECodigosPreservados() {
        assertEquals("AGT-EXE-004", ErrorCode.EXEC_PACKAGE_CHANGED.code());
        assertEquals("Um arquivo do pacote difere do que o descritor registra (alterado depois da geração).", ErrorCode.EXEC_PACKAGE_CHANGED.description());
        assertEquals("AGT-EXE-007", ErrorCode.EXEC_NOT_APPROVED.code());
        assertEquals("O pacote não foi aprovado nesta máquina, ou mudou depois da aprovação.", ErrorCode.EXEC_NOT_APPROVED.description());
        assertEquals("AGT-EXE-014", ErrorCode.EXEC_ALREADY_RUNNING.code());
        assertEquals("AGT-EXT-001", ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL.code());
        assertEquals("O processo terminou com código 0, sem erro sinalizado; o sucesso de negócio não foi sinalizado pelo script.",
                ErrorCode.EXT_EXIT_ZERO_NO_SIGNAL.description());
        assertEquals("AGT-EXT-022", ErrorCode.EXT_ENVIRONMENT_INCOMPATIBLE.code());
        assertEquals("AGT-PAC-020", ImportProblem.Code.UNSAFE_PATH.code());
        assertEquals("AGT-PAC-008", ImportProblem.Code.ENCRYPTED.code());
        assertEquals("O ZIP está protegido por senha. Gere o ZIP sem senha.", ImportProblem.Code.ENCRYPTED.text());
        assertEquals("O ZIP passa do limite de 50 MiB. [AGT-PAC-011]", new ImportProblem(ImportProblem.Code.ZIP_TOO_LARGE).message());
        assertEquals("O processo não pôde ser iniciado. [AGT-EXE-015]", ErrorCode.EXEC_LAUNCH_FAILED.message());
        assertEquals(new ZipLimits(50L * 1024 * 1024, 1000, 100L * 1024 * 1024, 50L * 1024 * 1024, 16, 200, 1024 * 1024), ZipLimits.DEFAULT);
        assertEquals(500, ZipLimits.PACKAGE_FILES);
        assertEquals(500, ExecutionPackage.MAX_FILES);
        assertEquals(50L * 1024 * 1024, ExecutionPackage.MAX_BYTES);
    }
}
