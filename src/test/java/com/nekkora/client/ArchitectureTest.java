package com.nekkora.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Fronteiras do Client, conferidas no codigo-fonte e no pom, por CAMADA:
 * <ul>
 * <li>{@code auth}: a unica camada que fala com a rede e abre o navegador;</li>
 * <li>{@code storage}: pasta de dados, tema e instancia unica; nao conhece a entrada;</li>
 * <li>{@code pack}: pacote local (leitura segura de ZIP, validador, impressao digital, empacotador, pacotes guardados por
 * organizacao e ambiente). Le e grava arquivos; sem rede, sem tela, sem processo;</li>
 * <li>{@code run}: execucao local de um pacote aprovado. A UNICA camada que cria processos; sem rede e sem tela;</li>
 * <li>{@code nav}: regras da navegacao; sem rede, sem tela, sem disco, sem processo;</li>
 * <li>{@code ui}: a unica camada com Swing.</li>
 * </ul>
 * E, em todas: nenhum codigo, classe ou integracao do Nekkora Agent administrativo (maquina pareada, canal da maquina,
 * pedidos remotos, envio de registros, instalacao de dependencias); uma unica dependencia de execucao (FlatLaf); nenhum
 * login simulado; nenhum segredo, endereco de laboratorio ou certificado embarcado; nenhum registro em log ou console.
 */
class ArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** Camadas sem interface grafica. */
    private static final Set<String> NO_SCREEN = Set.of("theme", "storage", "signin", "auth", "pack", "run", "nav");
    /** Nucleo local: nem rede, nem tela. */
    private static final Set<String> LOCAL_CORE = Set.of("pack", "run", "nav");
    /** Camadas que podem ler e gravar arquivos. */
    private static final Set<String> DISK = Set.of("storage", "pack", "run");

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String code(Path file) throws IOException {
        // sem comentarios: as regras valem para o codigo, e os comentarios explicam o que NAO existe
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** A camada do arquivo: o ultimo nome do pacote ({@code client} para a raiz). */
    private static String layer(Path file) {
        return file.getParent().getFileName().toString();
    }

    private static List<String> matches(Path file, String code, Pattern p, String what) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(code);
        while (m.find()) {
            out.add(file.getFileName() + ": " + what + " (" + m.group() + ")");
        }
        return out;
    }

    /**
     * Nada do Nekkora Agent administrativo: nem os pacotes e classes dele, nem a integracao da maquina com a Platform
     * (credencial da maquina, canal {@code /agent/api/}, codigos de vinculo), nem as funcoes que nao pertencem ao Client.
     * O nucleo local foi PORTADO (ver THIRD-PARTY-NOTICES.md), nao referenciado: as classes cujo papel mudou tem outro
     * nome aqui, e o nome antigo continua proibido.
     */
    @Test
    void nenhumCodigoNemIntegracaoDoAgentAdministrativo() throws IOException {
        Pattern proibido = Pattern.compile("com\\.nekkora\\.agent\\b|com\\.nekkora\\.ui\\b|com\\.nekkora\\.tokens\\b"
                + "|ImportFlow|ImportDialog|PackageHistory|MachineClient|PlatformService|RemoteCatalog|AgentBootstrap|AgentApp|CredentialVault|DpapiSecretStore"
                + "|RunRequest|ExecutionController|ApprovalStore|PackageShelf|ExternalBindings|ZipStager|FaultPoints"
                + "|/agent/api/|nka1\\.|nkp1\\.|enrollments|Runtime\\.getRuntime\\(\\)\\.exec");
        Pattern funcao = Pattern.compile("(?iu)\\b(pareamento|pairing|worker|catalog|cat[aá]logo|supervis|licen[cç]a|upload|pip install|venv)\\w*");
        List<String> achados = new ArrayList<>();
        for (Path f : javaFiles(MAIN)) {
            String c = code(f);
            // unico lugar em que "venv" pode aparecer: a lista fechada de pastas que NUNCA entram num pacote (regra do validador,
            // mantida byte a byte). E uma recusa, nao um uso: nenhum ambiente virtual e criado, lido ou instalado
            if (f.endsWith("ExecutionPackage.java")) {
                assertTrue(c.contains("List.of(\".venv\", \"venv\", \"env\", \"__pycache__\""), "a lista de pastas proibidas mudou");
                c = c.replace("List.of(\".venv\", \"venv\", \"env\", \"__pycache__\"", "");
            }
            achados.addAll(matches(f, c, proibido, "Nekkora Agent administrativo"));
            achados.addAll(matches(f, c, funcao, "funcao fora do Client"));
        }
        assertEquals(List.of(), achados);
    }

    @Test
    void pacotesDoAplicativoSaoSoOsEsperados() throws IOException {
        Set<String> pacotes = new TreeSet<>();
        for (Path f : javaFiles(MAIN)) {
            Matcher m = Pattern.compile("(?m)^package ([a-z.]+);").matcher(Files.readString(f));
            assertTrue(m.find(), f.toString());
            pacotes.add(m.group(1));
            // o pacote declarado e o da pasta: as regras por camada olham a pasta
            assertEquals(MAIN.relativize(f.getParent()).toString().replace('\\', '/').replace('/', '.'), m.group(1), f.toString());
        }
        Set<String> permitidos = Set.of("com.nekkora.client", "com.nekkora.client.theme", "com.nekkora.client.storage", "com.nekkora.client.signin",
                "com.nekkora.client.auth", "com.nekkora.client.ui", "com.nekkora.client.pack", "com.nekkora.client.run", "com.nekkora.client.nav");
        for (String p : pacotes) {
            assertTrue(permitidos.contains(p), "pacote fora da lista: " + p);
        }
    }

    @Test
    void umaUnicaDependenciaDeExecucao() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher dep = Pattern.compile("(?s)<dependency>(.*?)</dependency>").matcher(pom);
        Set<String> execucao = new TreeSet<>();
        while (dep.find()) {
            String d = dep.group(1);
            if (!d.contains("<scope>test</scope>")) {
                Matcher a = Pattern.compile("<groupId>(.*?)</groupId>\\s*<artifactId>(.*?)</artifactId>").matcher(d);
                assertTrue(a.find());
                execucao.add(a.group(1) + ":" + a.group(2));
            }
        }
        assertEquals(Set.of("com.formdev:flatlaf"), execucao);
        assertFalse(pom.contains("nekkora-agent<"), "o Client nao depende do JAR do Nekkora Agent");
        assertFalse(pom.contains("<systemPath>"), "nenhum JAR local embarcado");
        assertFalse(pom.contains("net.java.dev.jna") || pom.contains("nekkora-swing-ui"), "nem JNA nem a biblioteca de telas do Studio");
    }

    /** So {@code ui} (e a raiz, que monta a janela) conhece Swing; so {@code auth} fala com a rede e abre o navegador. */
    @Test
    void camadasSemInterfaceNaoImportamSwingNemRede() throws IOException {
        List<String> achados = new ArrayList<>();
        Pattern tela = Pattern.compile("javax\\.swing|com\\.formdev|com\\.nekkora\\.client\\.ui\\b");
        Pattern rede = Pattern.compile("java\\.net\\.http|HttpURLConnection|java\\.net\\.ServerSocket|com\\.sun\\.net|java\\.awt\\.Desktop");
        for (Path f : javaFiles(MAIN)) {
            String pkg = layer(f);
            String c = code(f);
            if (NO_SCREEN.contains(pkg)) {
                achados.addAll(matches(f, c, tela, "interface grafica numa camada sem tela"));
            }
            if (!"auth".equals(pkg)) {
                achados.addAll(matches(f, c, rede, "rede ou navegador fora de auth"));
            }
            if (NO_SCREEN.contains(pkg) && !"auth".equals(pkg) && c.contains("java.awt.")) {
                achados.add(f.getFileName() + ": AWT numa camada sem tela");
            }
        }
        assertEquals(List.of(), achados);
    }

    /**
     * O nucleo local ({@code pack}, {@code run}, {@code nav}) nao tem NENHUMA rede e NENHUMA tela: importar, empacotar,
     * guardar e executar um pacote nao falam com a Platform nem dependem de maquina pareada. Mais estrito que a regra
     * geral: qualquer coisa de {@code java.net}, soquete, AWT, Swing ou {@code java.beans}, e qualquer uso de {@code auth},
     * {@code signin} ou {@code ui}.
     */
    @Test
    void nucleoLocalSemRedeESemTela() throws IOException {
        Pattern rede = Pattern.compile("java\\.net\\.|javax\\.net\\.|javax\\.naming|java\\.rmi|\\bSocket|SocketChannel|HttpClient|URLConnection|\\bURL\\b|\\bURI\\b|InetAddress");
        Pattern tela = Pattern.compile("java\\.awt\\b|javax\\.swing|java\\.beans|com\\.formdev|GraphicsEnvironment|SwingUtilities|EventQueue");
        Pattern camadas = Pattern.compile("com\\.nekkora\\.client\\.(auth|signin|ui)\\b");
        List<String> achados = new ArrayList<>();
        int conferidos = 0;
        for (Path f : javaFiles(MAIN)) {
            if (!LOCAL_CORE.contains(layer(f))) {
                continue;
            }
            conferidos++;
            String c = code(f);
            achados.addAll(matches(f, c, rede, "rede no nucleo local"));
            achados.addAll(matches(f, c, tela, "tela no nucleo local"));
            achados.addAll(matches(f, c, camadas, "o nucleo local nao conhece a entrada nem a tela"));
        }
        assertEquals(List.of(), achados);
        assertTrue(conferidos > 0, "o nucleo local existe e foi conferido");
    }

    /**
     * So {@code run} cria, observa ou encerra processos (o interpretador escolhido pela pessoa, sem shell). Nenhuma outra
     * camada tem {@code ProcessBuilder}, {@code Process} ou {@code ProcessHandle}; {@code Runtime.exec} nao existe em lugar
     * nenhum. Em {@code run}, nada passa por um shell.
     */
    @Test
    void soACamadaDeExecucaoCriaProcessos() throws IOException {
        Pattern processo = Pattern.compile("ProcessBuilder|ProcessHandle|\\bProcess\\b|Runtime\\.getRuntime\\(\\)\\.exec");
        Pattern shell = Pattern.compile("(?i)\"(cmd|cmd\\.exe|powershell|powershell\\.exe|pwsh|sh|bash|/bin/sh)\"|\"/c\"|\"-Command\"|Runtime\\.getRuntime\\(\\)\\.exec");
        List<String> achados = new ArrayList<>();
        boolean runTemProcesso = false;
        for (Path f : javaFiles(MAIN)) {
            String c = code(f);
            if ("run".equals(layer(f))) {
                runTemProcesso |= c.contains("ProcessBuilder");
                achados.addAll(matches(f, c, shell, "shell na execucao"));
            } else {
                achados.addAll(matches(f, c, processo, "processo fora de run"));
            }
        }
        assertEquals(List.of(), achados);
        if (Files.isDirectory(MAIN.resolve("com/nekkora/client/run"))) {
            assertTrue(runTemProcesso, "a camada de execucao e a que cria o processo");
        }
    }

    /**
     * O aplicativo nunca le nem grava o perfil do Nekkora Agent administrativo ({@code %LOCALAPPDATA%\Nekkora\Agent}): o
     * nome dessa pasta so aparece na guarda de {@code ClientDataDir}, que RECUSA qualquer pasta de dados dentro dela. As
     * variaveis e arquivos do perfil antigo nao sao citados em lugar nenhum.
     */
    @Test
    void nadaReferenciaOPerfilDoAgentAdministrativo() throws IOException {
        Pattern pasta = Pattern.compile("\"Agent\"|Nekkora\\\\\\\\Agent\\b(?!Client)|Nekkora/Agent\\b(?!Client)");
        Pattern perfil = Pattern.compile("nekkora\\.agent\\.dataDir|NEKKORA_AGENT_DATA_DIR|agent-config\\.json|agent\\.lock|pacotes-zip|\"nekkora-agent\"");
        List<String> achados = new ArrayList<>();
        boolean guarda = false;
        for (Path f : javaFiles(MAIN)) {
            String c = code(f);
            if (f.endsWith("ClientDataDir.java")) {
                guarda = c.contains("resolve(\"Nekkora\").resolve(\"Agent\")") && c.contains("root.startsWith(agent)") && c.contains("throw new IllegalStateException");
                c = c.replace("resolve(\"Nekkora\").resolve(\"Agent\")", "");
            }
            achados.addAll(matches(f, c, pasta, "pasta do Nekkora Agent"));
            achados.addAll(matches(f, c, perfil, "perfil do Nekkora Agent"));
        }
        assertEquals(List.of(), achados);
        assertTrue(guarda, "ClientDataDir recusa a pasta do Nekkora Agent");
    }

    @Test
    void nenhumLoginSimuladoNoAplicativo() throws IOException {
        for (Path f : javaFiles(MAIN)) {
            String nome = f.getFileName().toString().toLowerCase(Locale.ROOT);
            assertFalse(nome.contains("fake") || nome.contains("mock") || nome.contains("simulad") || nome.contains("stub"), f.toString());
            String c = code(f).toLowerCase(Locale.ROOT);
            assertFalse(c.contains("success=true") || c.contains("\"success\""), f + ": a entrada nunca e decidida por um parametro de sucesso");
        }
    }

    @Test
    void nadaDeLaboratorioNemSegredoEmbarcado() throws IOException {
        List<String> achados = new ArrayList<>();
        Pattern suspeito = Pattern.compile("(?i)localhost|127\\.0\\.0\\.1:\\d|frontlab|client_secret|BEGIN (CERTIFICATE|PRIVATE KEY)|password\\s*=|senha\\s*=");
        for (Path f : javaFiles(MAIN)) {
            // unico lugar em que "localhost" pode aparecer: a lista fechada de origens de desenvolvimento
            String c = f.endsWith("PlatformOrigin.java") ? code(f).replace("localhost", "") : code(f);
            Matcher m = suspeito.matcher(c);
            while (m.find()) {
                achados.add(f.getFileName() + ": " + m.group());
            }
        }
        try (Stream<Path> s = Files.walk(RESOURCES)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                assertTrue(n.endsWith(".png") || n.endsWith(".txt") || n.endsWith(".properties"), "recurso inesperado: " + f);
                assertFalse(n.endsWith(".p12") || n.endsWith(".pem") || n.endsWith(".jks") || n.endsWith(".cfg"), f.toString());
            }
        }
        assertEquals(List.of(), achados);
    }

    /**
     * O aplicativo nao registra nem imprime nada: sem log e sem saida de console, em nenhuma camada. Disco so em
     * {@code storage} (pasta de dados, tema, instancia unica), {@code pack} (pacotes) e {@code run} (registros da execucao);
     * a entrada nao grava nada. Nao pede nem le credencial: sem campo de senha, sem cookie, sem area de transferencia; campo
     * de texto comum so na camada de tela (nome local de um pacote).
     */
    @Test
    void semRegistroDiscoSoNasCamadasDeArquivoESemLeituraDeCredencial() throws IOException {
        List<String> achados = new ArrayList<>();
        Pattern registro = Pattern.compile("System\\.(out|err)|printStackTrace|java\\.util\\.logging|org\\.slf4j|Logger\\b");
        Pattern disco = Pattern.compile("java\\.nio\\.file|java\\.io\\.File\\b|FileOutputStream|FileWriter|java\\.util\\.prefs|[^A-Za-z]Preferences\\b|KeyStore\\b");
        Pattern credencial = Pattern.compile("JPasswordField|JEditorPane|Cookie|Clipboard|getSystemClipboard|char\\[\\] (senha|password)");
        Pattern campoDeTexto = Pattern.compile("JTextField|JTextArea|JTextPane|JFormattedTextField");
        for (Path f : javaFiles(MAIN)) {
            String pkg = layer(f);
            String c = code(f);
            achados.addAll(matches(f, c, registro, "registro"));
            if (!DISK.contains(pkg)) {
                achados.addAll(matches(f, c, disco, "disco fora das camadas de arquivo"));
            }
            achados.addAll(matches(f, c, credencial, "credencial"));
            if (!"ui".equals(pkg)) {
                achados.addAll(matches(f, c, campoDeTexto, "campo de texto fora da tela"));
            }
            if ("storage".equals(pkg) && Pattern.compile("(?i)token|sess[aã]o|session|auth|code|verifier|state\\b").matcher(c).find()) {
                achados.add(f.getFileName() + ": o armazenamento nao conhece a entrada");
            }
        }
        assertEquals(List.of(), achados);
    }

    /**
     * O nucleo local nao instala nem baixa nada e nao tem fila de envio: o interpretador e as dependencias sao os que a
     * pessoa preparou (so conferidos), e os registros das execucoes ficam no disco desta maquina.
     */
    @Test
    void nucleoLocalNaoInstalaNadaNemEnviaRegistros() throws IOException {
        Pattern instalar = Pattern.compile("(?i)\"-m\"|\"pip\"|\"pip3\"|ensurepip|\"install\"|\"--target\"|\"conda\"|\"uv\"|\"poetry\"|\"virtualenv\"");
        Pattern enviar = Pattern.compile("(?i)delivery|outbox|pendingDelivery|\\bqueue|retention\\(");
        List<String> achados = new ArrayList<>();
        for (Path f : javaFiles(MAIN)) {
            if (!LOCAL_CORE.contains(layer(f))) {
                continue;
            }
            String c = code(f);
            achados.addAll(matches(f, c, instalar, "instalacao"));
            achados.addAll(matches(f, c, enviar, "envio de registros"));
        }
        assertEquals(List.of(), achados);
    }
}
