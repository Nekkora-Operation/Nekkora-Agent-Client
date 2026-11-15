package com.nekkora.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.nekkora.client.nav.Directory;
import com.nekkora.client.run.RunController;
import com.nekkora.client.run.Runner;

/**
 * AGENT-CLIENT-PACOTES-001 T04 - a origem da execucao determina o acompanhamento, e neste aplicativo so existe uma origem:
 * a execucao iniciada nele, que e LOCAL. Estes testes fixam o contrato de separacao no codigo:
 * <ul>
 * <li>o que o aplicativo pode pedir a Platform e uma lista fechada de consultas (identidade, catalogo, download) e uma
 * escrita (renomear) - nenhuma operacao de execucao, evento, registro ou monitoramento;</li>
 * <li>as rotas que o aplicativo conhece sao so as do contrato {@code /api/v1/desktop/}, e nenhuma e de execucao;</li>
 * <li>quem inicia e acompanha uma execucao nao recebe "origem" de fora: nao ha parametro para forjar uma execucao remota.</li>
 * </ul>
 * A integracao de maquina (pedido de execucao vindo da Platform, com Worker e autorizacoes) NAO existe neste aplicativo; se
 * um dia existir, entra por um caminho proprio, com credencial de maquina, e estes testes terao de mudar de proposito.
 */
class ExecutionOriginTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    @Test
    void oQueOAplicativoPedeAPlatformEUmaListaFechadaSemNadaDeExecucao() {
        Set<String> operacoes = new TreeSet<>();
        for (Method m : Directory.class.getDeclaredMethods()) {
            if (!m.isSynthetic()) {
                operacoes.add(m.getName());
            }
        }
        assertEquals(new TreeSet<>(Set.of("profile", "organizations", "workspaces", "automations", "versions", "download", "rename")), operacoes,
                "identidade, catalogo, download e renomear: nada de execucao, evento, registro ou monitoramento");
    }

    @Test
    void asRotasConhecidasSaoAsDoContratoDoAplicativoENenhumaEDeExecucao() throws IOException {
        Pattern rota = Pattern.compile("\"(/api/v1/[^\"]*)\"");
        Set<String> rotas = new TreeSet<>();
        List<String> foraDeAuth = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = rota.matcher(Files.readString(f));
                while (m.find()) {
                    rotas.add(m.group(1));
                    if (!f.toString().replace('\\', '/').contains("/auth/")) {
                        foraDeAuth.add(f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), foraDeAuth, "so a camada de rede conhece enderecos da Platform");
        assertFalse(rotas.isEmpty());
        for (String r : rotas) {
            assertTrue(r.startsWith("/api/v1/desktop/"), "fora do contrato do aplicativo desktop: " + r);
            assertFalse(Pattern.compile("(?i)run|execu|event|log|monitor|heartbeat|agent|worker|telemetr|report").matcher(r).find(),
                    "rota de execucao ou de maquina no aplicativo: " + r);
        }
    }

    @Test
    void quemExecutaNaoRecebeOrigemDeFora() {
        for (Class<?> c : List.of(Runner.class, RunController.class)) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers())) {
                    assertFalse(Pattern.compile("(?i)origin|origem|platform|remote|remot|monitor").matcher(m.getName()).find(),
                            c.getSimpleName() + "." + m.getName() + ": nao ha execucao remota nem origem informada por quem chama");
                    for (Class<?> p : m.getParameterTypes()) {
                        assertFalse(p.getName().startsWith("com.nekkora.client.auth") || p.getName().startsWith("com.nekkora.client.nav"),
                                c.getSimpleName() + "." + m.getName() + " recebe " + p.getSimpleName() + ": a execucao nao conhece sessao nem Platform");
                    }
                }
            }
        }
    }
}
