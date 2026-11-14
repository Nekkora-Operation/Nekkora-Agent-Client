package com.nekkora.client.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nekkora.client.nav.Directory;
import com.nekkora.client.nav.Directory.Failure;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Profile;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.signin.SignInState;
import com.nekkora.client.signin.SignInState.Notice;
import com.nekkora.client.signin.SignInState.Phase;

/**
 * Consultas da pessoa pelo contrato desktop-navigation/1, com o cliente HTTPS real contra a Platform SIMULADA
 * ({@link FakePlatform}): forma das respostas, classificacao de cada falha e o fim da sessao.
 */
class PlatformDirectoryTest {

    private static final String A = "00000000-0000-4000-8000-000000000001";
    private static final String B = "00000000-0000-4000-8000-000000000002";

    private FakePlatform platform;
    private PlatformApi api;
    private PlatformApi.Token token;
    private final AtomicInteger ended = new AtomicInteger();
    private Directory directory;

    @BeforeEach
    void up() throws Exception {
        platform = new FakePlatform();
        api = new PlatformApi(platform.origin(), platform.clientTrust, Duration.ofSeconds(3), Duration.ofSeconds(3));
        token = new PlatformApi.Token(FakePlatform.token(), 900);
        platform.sessions.put(token.value(), "ana@empresa.test");
        directory = new PlatformDirectory(api, () -> token, ended::incrementAndGet);
    }

    @AfterEach
    void down() {
        platform.close();
    }

    private Failure failure(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(Directory.Failed.class, call).failure();
    }

    private static String org(String id, String name, String handle, String state) {
        return "{\"organizationId\":\"" + id + "\",\"name\":\"" + name + "\",\"handle\":" + (handle == null ? "null" : "\"" + handle + "\"")
                + ",\"activationState\":\"" + state + "\"}";
    }

    @Test
    void perfilEOrganizacoesPeloContrato() throws Exception {
        assertEquals(new Profile("ana@empresa.test", "Ana Souza"), directory.profile());
        platform.displayName = null;
        assertNull(directory.profile().displayName(), "nome ausente e aceito; o avatar usa o desenho neutro");

        assertEquals(List.of(), directory.organizations(), "vazio legitimo: 200 com items vazio");
        platform.organizationsBody = "{\"items\":[" + org(A, "Acme", "acme", "ATIVADA") + "," + org(B, "Acme", null, "AGUARDANDO_ATIVACAO") + "]}";
        List<Organization> list = directory.organizations();
        assertEquals(2, list.size());
        assertEquals(new Organization(java.util.UUID.fromString(A), "Acme", "acme", false), list.get(0));
        assertTrue(list.get(1).awaitingActivation());
        assertEquals("Acme", list.get(1).name(), "homonimas chegam as duas, na ordem da Platform");

        // o que foi para a rede: so o token no cabecalho, sem cookie (a Platform simulada recusa cookie com 418)
        assertTrue(platform.readAuthorizations.stream().allMatch(h -> h.equals("Bearer " + token.value())));
        assertFalse(platform.requests.stream().anyMatch(r -> r.contains("?")), "nada da pessoa vai na URL");
        assertEquals(0, ended.get());
    }

    @Test
    void duzentasOrganizacoesCabemNaLeitura() throws Exception {
        StringBuilder b = new StringBuilder("{\"items\":[");
        for (int i = 1; i <= 200; i++) {
            b.append(i > 1 ? "," : "").append(org(String.format("00000000-0000-4000-8000-%012d", i), "Organização com um nome bem comprido " + i, "org-" + i, "ATIVADA"));
        }
        platform.organizationsBody = b.append("]}").toString();
        assertEquals(200, directory.organizations().size());
    }

    @Test
    void cadaRespostaDeErroTemASuaClassificacaoENenhumaViraListaVazia() {
        Object[][] casos = {{401, "{\"error\":\"unauthenticated\"}", Failure.SESSION_ENDED}, {403, "{\"error\":\"forbidden\"}", Failure.DENIED},
                {403, "<html>403</html>", Failure.INVALID}, {404, "{\"error\":\"not_found\"}", Failure.NOT_FOUND},
                {429, "{\"error\":\"rate_limited\"}", Failure.RATE_LIMITED}, {500, "{\"error\":\"internal_error\"}", Failure.SERVER},
                {503, "{\"error\":\"service_unavailable\"}", Failure.SERVER}, {502, "<html>bad gateway</html>", Failure.SERVER},
                {302, "{}", Failure.INVALID}, {400, "{\"error\":\"invalid_request\"}", Failure.REJECTED},
                {409, "{\"error\":\"conflict\"}", Failure.CONFLICT}};
        for (Object[] c : casos) {
            platform.readStatus = (Integer) c[0];
            platform.readBody = (String) c[1];
            assertEquals(c[2], failure(directory::organizations), "organizacoes " + c[0]);
            assertEquals(c[2], failure(directory::profile), "perfil " + c[0]);
        }
        assertEquals(2, ended.get(), "so o 401 encerra a sessao (uma vez por consulta)");
    }

    @Test
    void respostaDeSucessoForaDoContratoNaoEAceita() {
        platform.readStatus = 200;
        for (String body : new String[] {"", "[]", "{}", "{\"items\":null}", "{\"items\":{}}", "{\"items\":[null]}", "{\"items\":[{}]}",
                "{\"items\":[" + org("nao-e-uuid", "Acme", "acme", "ATIVADA") + "]}",
                "{\"items\":[" + org(A, "", "acme", "ATIVADA") + "]}",
                "{\"items\":[" + org(A, "Acme", "acme", "SUSPENSA") + "]}",
                "{\"items\":[{\"organizationId\":\"" + A + "\",\"name\":\"Acme\",\"handle\":7,\"activationState\":\"ATIVADA\"}]}",
                "{\"items\":[" + org(A, "Acme", "a", "ATIVADA") + "," + org(A, "Outra", "b", "ATIVADA") + "]}", // identificador repetido
                "{\"items\":[" + org(A, "Acme", "a", "ATIVADA") + "]} sobra"}) {
            platform.readBody = body;
            assertEquals(Failure.INVALID, failure(directory::organizations), body);
        }
        for (String body : new String[] {"{}", "{\"email\":\"\"}", "{\"email\":7}", "{\"email\":\"a@b.test\",\"displayName\":7}", "[]"}) {
            platform.readBody = body;
            assertEquals(Failure.INVALID, failure(directory::profile), body);
        }
        platform.readBody = "{\"items\":[]}";
        platform.readContentType = "text/html";
        assertEquals(Failure.INVALID, failure(directory::organizations), "tipo de conteudo");
        assertEquals(0, ended.get());
    }

    // ------------------------------------------------------------------ ambientes e automacoes (T03)

    private static final String WS1 = "00000000-0000-4000-8000-000000001001";
    private static final String WS2 = "00000000-0000-4000-8000-000000001002";
    private static final String AU1 = "00000000-0000-4000-8000-000000002001";
    private static final String V1 = "00000000-0000-4000-8000-000000003001";

    private static String wsPath(String org) {
        return "/api/v1/desktop/organizations/" + org + "/workspaces";
    }

    private static String auPath(String org, String ws) {
        return "/api/v1/desktop/organizations/" + org + "/workspaces/" + ws + "/automations";
    }

    @Test
    void ambientesPaginadosComOCursorDevolvidoComoVeio() throws Exception {
        java.util.UUID org = java.util.UUID.fromString(A);
        String cursor = "eyJwIjoxfQ.c2lnbmF0dXJl-_~";
        platform.pages.put(wsPath(A) + "?limit=100", "{\"items\":[{\"workspaceId\":\"" + WS1 + "\",\"name\":\"Financeiro\"}],\"nextCursor\":\"" + cursor + "\"}");
        platform.pages.put(wsPath(A) + "?limit=100&cursor=" + cursor, "{\"items\":[{\"workspaceId\":\"" + WS2 + "\",\"name\":\"Fiscal\"}],\"nextCursor\":null}");
        assertEquals(List.of(new com.nekkora.client.nav.Workspace(java.util.UUID.fromString(WS1), "Financeiro"),
                new com.nekkora.client.nav.Workspace(java.util.UUID.fromString(WS2), "Fiscal")), directory.workspaces(org), "duas paginas, na ordem da Platform");
        assertEquals(List.of(wsPath(A) + "?limit=100", wsPath(A) + "?limit=100&cursor=" + cursor), platform.pageRequests);
        // vazio legitimo
        platform.pages.put(wsPath(A) + "?limit=100", "{\"items\":[],\"nextCursor\":null}");
        assertEquals(List.of(), directory.workspaces(org));
        // organizacao fora do alcance da pessoa: 404 da Platform, nunca lista vazia
        assertEquals(Failure.NOT_FOUND, failure(() -> directory.workspaces(java.util.UUID.fromString(B))));
    }

    @Test
    void automacoesComVersaoPublicadaPacoteECapacidades() throws Exception {
        java.util.UUID org = java.util.UUID.fromString(A);
        java.util.UUID ws = java.util.UUID.fromString(WS1);
        platform.pages.put(auPath(A, WS1) + "?limit=100", "{\"items\":["
                + "{\"automationId\":\"" + AU1 + "\",\"name\":\"Conciliação\",\"latestPublishedVersion\":{\"automationVersionId\":\"" + V1
                + "\",\"versionNumber\":3,\"publishedAt\":\"2026-11-10T12:00:00Z\",\"package\":{\"name\":\"conciliacao\",\"version\":\"1.2.0\",\"fingerprint\":\"sha256:abc\"}}},"
                + "{\"automationId\":\"00000000-0000-4000-8000-000000002002\",\"name\":\"Só rascunho\",\"latestPublishedVersion\":null},"
                + "{\"automationId\":\"00000000-0000-4000-8000-000000002003\",\"name\":\"Sem artefato\",\"latestPublishedVersion\":{\"automationVersionId\":\"" + V1
                + "\",\"versionNumber\":1,\"publishedAt\":\"2026-11-10T12:00:00Z\",\"package\":null}}"
                + "],\"nextCursor\":null,\"capabilities\":{\"rename\":true,\"execute\":false}}");
        com.nekkora.client.nav.Automation.Listing l = directory.automations(org, ws);
        assertEquals(3, l.items().size());
        assertEquals(new com.nekkora.client.nav.Automation.Published(java.util.UUID.fromString(V1), 3, "conciliacao", "1.2.0", "sha256:abc"), l.items().get(0).published());
        assertNull(l.items().get(1).published(), "so rascunho: nenhuma versao publicada");
        assertEquals(1, l.items().get(2).published().number());
        assertNull(l.items().get(2).published().packageVersion());
        assertEquals(new com.nekkora.client.nav.Automation.Capabilities(true, false), l.capabilities());
        assertFalse(l.truncated());
        // ambiente de outra organizacao no caminho: a Platform responde 404
        assertEquals(Failure.NOT_FOUND, failure(() -> directory.automations(java.util.UUID.fromString(B), ws)));
        assertEquals(Failure.NOT_FOUND, failure(() -> directory.automations(org, java.util.UUID.fromString(WS2))));
    }

    @Test
    void paginasForaDoContratoNaoSaoAceitas() {
        java.util.UUID org = java.util.UUID.fromString(A);
        java.util.UUID ws = java.util.UUID.fromString(WS1);
        String item = "{\"workspaceId\":\"" + WS1 + "\",\"name\":\"Financeiro\"}";
        for (String body : new String[] {"{}", "{\"items\":null}", "{\"items\":[{}]}", "{\"items\":[{\"workspaceId\":\"x\",\"name\":\"F\"}]}",
                "{\"items\":[{\"workspaceId\":\"" + WS1 + "\",\"name\":\"\"}]}", "{\"items\":[" + item + "," + item + "]}",
                "{\"items\":[" + item + "],\"nextCursor\":7}", "{\"items\":[" + item + "],\"nextCursor\":\"com espaco\"}",
                "{\"items\":[" + item + "],\"nextCursor\":\"a&organization=outra\"}", "{\"items\":[" + item + "],\"nextCursor\":\"../x\"}"}) {
            platform.pages.put(wsPath(A) + "?limit=100", body);
            assertEquals(Failure.INVALID, failure(() -> directory.workspaces(org)), body);
        }
        String ok = "{\"automationId\":\"" + AU1 + "\",\"name\":\"X\",\"latestPublishedVersion\":null}";
        for (String body : new String[] {"{\"items\":[" + ok + "],\"nextCursor\":null}", // sem capacidades
                "{\"items\":[" + ok + "],\"nextCursor\":null,\"capabilities\":{\"rename\":\"sim\",\"execute\":true}}",
                "{\"items\":[" + ok + "],\"nextCursor\":null,\"capabilities\":{\"rename\":true}}",
                "{\"items\":[{\"automationId\":\"" + AU1 + "\",\"name\":\"X\",\"latestPublishedVersion\":{\"automationVersionId\":\"" + V1
                        + "\",\"versionNumber\":0}}],\"nextCursor\":null,\"capabilities\":{\"rename\":true,\"execute\":true}}",
                "{\"items\":[{\"automationId\":\"" + AU1 + "\",\"name\":\"X\",\"latestPublishedVersion\":{\"automationVersionId\":\"" + V1
                        + "\",\"versionNumber\":1.5}}],\"nextCursor\":null,\"capabilities\":{\"rename\":true,\"execute\":true}}",
                "{\"items\":[{\"automationId\":\"" + AU1 + "\",\"name\":\"X\",\"latestPublishedVersion\":{\"automationVersionId\":\"" + V1
                        + "\",\"versionNumber\":1,\"package\":\"zip\"}}],\"nextCursor\":null,\"capabilities\":{\"rename\":true,\"execute\":true}}",
                "{\"items\":[" + ok + "," + ok + "],\"nextCursor\":null,\"capabilities\":{\"rename\":true,\"execute\":true}}"}) {
            platform.pages.put(auPath(A, WS1) + "?limit=100", body);
            assertEquals(Failure.INVALID, failure(() -> directory.automations(org, ws)), body);
        }
        assertEquals(0, ended.get());
    }

    @Test
    void listaMaiorQueOTetoDePaginasEAvisadaComoIncompleta() throws Exception {
        java.util.UUID org = java.util.UUID.fromString(A);
        java.util.UUID ws = java.util.UUID.fromString(WS1);
        for (int i = 0; i < PlatformDirectory.MAX_PAGES + 2; i++) {
            String key = auPath(A, WS1) + "?limit=100" + (i == 0 ? "" : "&cursor=c" + i);
            platform.pages.put(key, "{\"items\":[{\"automationId\":\"" + String.format("00000000-0000-4000-8000-%012d", 5000 + i)
                    + "\",\"name\":\"A" + i + "\",\"latestPublishedVersion\":null}],\"nextCursor\":\"c" + (i + 1) + "\",\"capabilities\":{\"rename\":false,\"execute\":false}}");
        }
        com.nekkora.client.nav.Automation.Listing l = directory.automations(org, ws);
        assertEquals(PlatformDirectory.MAX_PAGES, l.items().size());
        assertTrue(l.truncated(), "a tela avisa que ha mais do que mostra");
        assertEquals(PlatformDirectory.MAX_PAGES, platform.pageRequests.size(), "o aplicativo para no teto: nao consulta sem fim");
    }

    // ------------------------------------------------------------------ renomear (T04)

    @Test
    void renomearEnviaSoONomeEConfiaNoQueOServidorConfirmou() throws Exception {
        java.util.UUID org = java.util.UUID.fromString(A);
        java.util.UUID automation = java.util.UUID.fromString(AU1);
        platform.writeBody = "{\"automationId\":\"" + AU1 + "\",\"name\":\"Conciliação \\\"diária\\\"\"}";
        assertEquals("Conciliação \"diária\"", directory.rename(org, automation, "Conciliação \"diária\""), "o nome que vale e o que o servidor gravou");
        assertEquals(List.of("PATCH /api/v1/desktop/organizations/" + A + "/automations/" + AU1 + " | application/json | {\"name\":\"Conciliação \\\"diária\\\"\"}"),
                platform.writes, "so o nome vai no corpo: nem papel, nem conta, nem versao");
        // cada recusa do servidor com a sua classificacao; nenhuma e tratada como sucesso
        Object[][] casos = {{403, "{\"error\":\"forbidden\"}", Failure.DENIED}, {404, "{\"error\":\"not_found\"}", Failure.NOT_FOUND},
                {400, "{\"error\":\"invalid_request\"}", Failure.REJECTED}, {409, "{\"error\":\"conflict\"}", Failure.CONFLICT},
                {429, "{\"error\":\"rate_limited\"}", Failure.RATE_LIMITED}, {500, "{\"error\":\"internal_error\"}", Failure.SERVER}};
        for (Object[] c : casos) {
            platform.writeStatus = (Integer) c[0];
            platform.writeBody = (String) c[1];
            assertEquals(c[2], failure(() -> directory.rename(org, automation, "Outro")), "renomear " + c[0]);
        }
        // 200 que nao confirma ESTA automacao ou nao traz o nome: nao e sucesso
        platform.writeStatus = 200;
        for (String body : new String[] {"{}", "{\"automationId\":\"" + AU1 + "\"}", "{\"automationId\":\"" + WS1 + "\",\"name\":\"Outro\"}",
                "{\"automationId\":\"" + AU1 + "\",\"name\":\"\"}", "{\"automationId\":\"" + AU1 + "\",\"name\":7}"}) {
            platform.writeBody = body;
            assertEquals(Failure.INVALID, failure(() -> directory.rename(org, automation, "Outro")), body);
        }
        assertEquals(0, ended.get());
        // sessao encerrada: 401 leva a entrada
        platform.sessions.clear();
        assertEquals(Failure.SESSION_ENDED, failure(() -> directory.rename(org, automation, "Outro")));
        assertEquals(1, ended.get());
    }

    @Test
    void semConexaoTlsETempoSaoFalhasProprias() throws Exception {
        Directory semConfianca = new PlatformDirectory(new PlatformApi(platform.origin(), null, Duration.ofSeconds(3), Duration.ofSeconds(3)), () -> token, ended::incrementAndGet);
        assertEquals(Failure.TLS, failure(semConfianca::organizations));
        assertEquals(0, platform.readAuthorizations.size(), "sem confianca no certificado, o token nao sai do aplicativo");
        PlatformOrigin origin = platform.origin();
        platform.close();
        Directory fora = new PlatformDirectory(new PlatformApi(origin, platform.clientTrust, Duration.ofSeconds(2), Duration.ofSeconds(2)), () -> token, ended::incrementAndGet);
        assertEquals(Failure.NETWORK, failure(fora::organizations));
        assertEquals(Failure.SESSION_ENDED, failure(new PlatformDirectory(api, () -> null, ended::incrementAndGet)::profile), "sem token: nem tenta");
    }

    @Test
    void quatrocentosEUmNumaConsultaEncerraASessaoELevaAEntrada() throws Exception {
        FakeBrowser browser = new FakeBrowser(platform);
        BrowserSignIn signIn = new BrowserSignIn(platform.origin(), api, browser, new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
        SignInFlow flow = new SignInFlow(signIn, Runnable::run);
        List<SignInState> seen = new CopyOnWriteArrayList<>();
        flow.addListener(seen::add);
        try {
            flow.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (flow.state().phase() != Phase.SIGNED_IN && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertEquals(Phase.SIGNED_IN, flow.state().phase());
            // o pedido que o navegador recebeu leva o escopo novo, e so com ele a entrada vale
            assertEquals("identity navigation", FakePlatform.query(browser.lastPage().getRawQuery()).get("scope"));
            Directory d = flow.directory();
            assertEquals("ana@empresa.test", d.profile().email());
            // a sessao e encerrada na conta (logout no navegador): a consulta seguinte recebe 401
            platform.sessions.clear();
            assertEquals(Failure.SESSION_ENDED, failure(d::organizations));
            assertEquals(new SignInState(Phase.IDLE, Notice.SESSION_EXPIRED), flow.state(), "volta para a entrada, com o aviso de sessao terminada");
            assertNull(flow.directory(), "sem sessao, sem consultas");
            assertEquals(Failure.SESSION_ENDED, failure(d::profile), "o token foi descartado: a porta antiga nao consulta mais");
        } finally {
            flow.close();
            signIn.close();
        }
    }

    @Test
    void terminarSessaoDescartaOTokenEPedeOEncerramentoAPlatform() throws Exception {
        FakeBrowser browser = new FakeBrowser(platform);
        BrowserSignIn signIn = new BrowserSignIn(platform.origin(), api, browser, new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
        SignInFlow flow = new SignInFlow(signIn, Runnable::run);
        try {
            flow.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (flow.state().phase() != Phase.SIGNED_IN && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            Directory d = flow.directory();
            flow.signOut();
            assertEquals(new SignInState(Phase.IDLE, Notice.SIGNED_OUT), flow.state());
            assertEquals(Failure.SESSION_ENDED, failure(d::organizations), "depois de terminar a sessao, o aplicativo nao consulta mais nada");
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (platform.revoked.size() < 1 && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertEquals(1, platform.revoked.size(), "encerramento pedido a Platform");
            flow.signOut();
            assertEquals(new SignInState(Phase.IDLE, Notice.SIGNED_OUT), flow.state(), "terminar de novo nao faz nada");
            // nova entrada funciona, do zero
            flow.start();
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (flow.state().phase() != Phase.SIGNED_IN && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertEquals(Phase.SIGNED_IN, flow.state().phase());
        } finally {
            flow.close();
            signIn.close();
        }
    }
}
