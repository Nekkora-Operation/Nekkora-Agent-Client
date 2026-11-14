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
                {302, "{}", Failure.INVALID}, {400, "{\"error\":\"invalid_request\"}", Failure.INVALID}};
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
