package com.nekkora.client.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.signin.SignInState;
import com.nekkora.client.signin.SignInState.Notice;
import com.nekkora.client.signin.SignInState.Phase;

/**
 * A entrada inteira do aplicativo, com as pecas reais ({@link SignInFlow}, {@link BrowserSignIn}, retorno local, cliente
 * HTTPS), contra a Platform e o navegador SIMULADOS dos testes. Nada e substituido dentro do aplicativo: as requisicoes
 * passam pela rede local de verdade, e o PKCE e conferido do lado do servidor simulado.
 */
class BrowserSignInTest {

    private FakePlatform platform;
    private FakeBrowser browser;
    private BrowserSignIn signIn;
    private SignInFlow flow;
    private final List<SignInState> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws Exception {
        platform = new FakePlatform();
        browser = new FakeBrowser(platform);
        start(new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
    }

    private void start(BrowserSignIn.Timing timing) {
        PlatformApi api = new PlatformApi(platform.origin(), platform.clientTrust, Duration.ofSeconds(3), Duration.ofSeconds(4));
        signIn = new BrowserSignIn(platform.origin(), api, browser, timing);
        flow = new SignInFlow(signIn, Runnable::run);
        flow.addListener(seen::add);
    }

    @AfterEach
    void down() {
        flow.close();
        signIn.close();
        platform.close();
    }

    private static void await(String what, BooleanSupplier ok) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!ok.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("nao aconteceu: " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private void awaitPhase(Phase p) {
        await("fase " + p + " (estado " + flow.state() + ")", () -> flow.state().phase() == p);
    }

    private void awaitNotice(Notice n) {
        await("aviso " + n + " (estado " + flow.state() + ")", () -> flow.state().phase() == Phase.IDLE && flow.state().notice() == n);
    }

    private FakeBrowser.Visit visit(int i) throws Exception {
        await("visita " + i, () -> browser.visits.size() > i);
        return browser.visits.get(i).get(40, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ caminho real

    @Test
    void entradaCompleta() throws Exception {
        flow.start();
        awaitPhase(Phase.SIGNED_IN);
        assertEquals(List.of(Phase.OPENING_BROWSER, Phase.WAITING, Phase.SIGNED_IN), seen.stream().map(SignInState::phase).toList());

        // o navegador foi aberto na pagina certa, com um pedido do contrato e nada alem dele
        URI page = browser.lastPage();
        assertEquals(platform.issuer() + "/entrar/aplicativo", page.toString().split("\\?")[0]);
        Map<String, String> q = FakePlatform.query(page.getRawQuery());
        assertEquals(java.util.Set.of("response_type", "client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "scope"), q.keySet());
        assertEquals("S256", q.get("code_challenge_method"));
        assertTrue(q.get("redirect_uri").matches("http://127\\.0\\.0\\.1:\\d{4,5}/callback"), "IP literal de loopback");
        assertFalse(page.toString().contains("client_secret") || page.toString().contains("code_verifier") || page.toString().contains("token"));

        // a pagina do retorno so disse "concluido" depois da API; a Platform recebeu troca e conferencia da sessao
        FakeBrowser.Visit v = visit(0);
        assertEquals(200, v.status());
        assertTrue(v.body().contains("Login concluído"));
        assertEquals(1, platform.requestsTo("POST /api/v1/desktop/token").size());
        assertEquals(1, platform.requestsTo("GET /api/v1/desktop/session").size());
        assertEquals(1, platform.sessions.size());
        // o verificador que chegou a API e o do desafio que foi ao navegador (conferido pelo servidor simulado)
        assertEquals(q.get("code_challenge"), Pkce.s256(FakePlatform.query(platform.tokenBodies.get(0)).get("code_verifier")));
        // so as rotas do contrato foram chamadas
        assertEquals(java.util.Set.of("POST /api/v1/desktop/token", "GET /api/v1/desktop/session"), java.util.Set.copyOf(platform.requests));

        // o retorno local fecha depois do sucesso
        int port = Integer.parseInt(q.get("redirect_uri").replaceAll(".*:(\\d+)/callback", "$1"));
        await("retorno local fechado", () -> FakeBrowser.get("http://127.0.0.1:" + port + "/callback").status() == -1);
    }

    @Test
    void cliquesRepetidosAbremUmaAbaEUmRetorno() throws Exception {
        browser.decide = (page, redirect) -> null; // a pessoa ainda nao fez nada no navegador
        for (int i = 0; i < 10; i++) {
            flow.start();
        }
        awaitPhase(Phase.WAITING);
        for (int i = 0; i < 10; i++) {
            flow.start();
        }
        Thread.sleep(200);
        assertEquals(1, browser.opened.size(), "uma unica aba");
        assertEquals(Phase.WAITING, flow.state().phase());
    }

    // ------------------------------------------------------------------ retorno nao confirmado

    @Test
    void retornoSemConfirmacaoDaApiNaoAutentica() throws Exception {
        platform.tokenStatus = 503;
        platform.tokenBody = "{\"error\":\"service_unavailable\"}";
        flow.start();
        awaitNotice(Notice.SERVER);
        assertFalse(seen.stream().anyMatch(s -> s.phase() == Phase.SIGNED_IN), "o retorno do navegador, sozinho, nao abre a tela inicial");
        assertTrue(visit(0).body().contains("Não foi possível confirmar o login"));
        assertEquals(0, platform.sessions.size());
    }

    @Test
    void parametroDeSucessoNoRetornoNaoVale() throws Exception {
        browser.decide = (page, redirect) -> redirect.split("\\?")[0] + "?success=true&state=" + FakePlatform.query(page.getRawQuery()).get("state");
        flow.start();
        awaitPhase(Phase.WAITING);
        assertEquals(400, visit(0).status());
        Thread.sleep(150);
        assertEquals(Phase.WAITING, flow.state().phase(), "continua esperando o retorno de verdade");
        assertEquals(0, platform.requestsTo("POST /api/v1/desktop/token").size());
    }

    @Test
    void stateIncorretoOuAusenteNaoConcluiENaoDerrubaATentativa() throws Exception {
        browser.decide = (page, redirect) -> redirect.replaceAll("state=[^&]+", "state=" + Pkce.generate().state());
        flow.start();
        awaitPhase(Phase.WAITING);
        assertEquals(400, visit(0).status());
        assertEquals(0, platform.requestsTo("POST /api/v1/desktop/token").size(), "codigo com state errado nem chega a API");
        // sem state
        String redirect = FakePlatform.query(browser.lastPage().getRawQuery()).get("redirect_uri");
        assertEquals(400, FakeBrowser.get(redirect + "?code=" + FakePlatform.token()).status());
        assertEquals(Phase.WAITING, flow.state().phase());
        // o retorno legitimo ainda conclui
        String certo = platform.authorize(browser.lastPage());
        assertEquals(200, FakeBrowser.get(certo).status());
        awaitPhase(Phase.SIGNED_IN);
    }

    @Test
    void emissorDiferenteNaoEAceito() throws Exception {
        browser.decide = (page, redirect) -> redirect.replaceAll("iss=[^&]+", "iss=https%3A%2F%2Foutro.exemplo.test");
        flow.start();
        awaitPhase(Phase.WAITING);
        assertEquals(400, visit(0).status());
        assertEquals(0, platform.requestsTo("POST /api/v1/desktop/token").size());
    }

    @Test
    void codigoQueNaoEDestaTentativaERecusadoPelaApi() throws Exception {
        // um codigo valido emitido para OUTRO pedido (outro desafio PKCE), entregue com o state certo desta tentativa
        Pkce outro = Pkce.generate();
        browser.decide = (page, redirect) -> {
            String alheio = platform.authorize(URI.create(page.toString().replaceAll("code_challenge=[^&]+", "code_challenge=" + outro.challenge())));
            return redirect.replaceAll("code=[^&]+", "code=" + FakePlatform.query(URI.create(alheio).getRawQuery()).get("code"));
        };
        flow.start();
        awaitNotice(Notice.INVALID_RETURN);
        assertEquals(0, platform.sessions.size(), "PKCE: o verificador desta tentativa nao abre o codigo de outra");
        assertEquals("Não foi possível validar a entrada com segurança. Inicie uma nova tentativa.", com.nekkora.client.ui.SignInText.of(flow.state().notice()));
    }

    @Test
    void retornoDeTentativaAnteriorNaoAutenticaANova() throws Exception {
        browser.decide = (page, redirect) -> null;
        flow.start();
        awaitPhase(Phase.WAITING);
        URI primeira = browser.lastPage();
        String retornoDaPrimeira = platform.authorize(primeira);
        flow.cancel();
        assertEquals(new SignInState(Phase.IDLE, Notice.CANCELLED), flow.state());
        flow.start();
        awaitPhase(Phase.WAITING);
        URI segunda = browser.lastPage();
        Map<String, String> q1 = FakePlatform.query(primeira.getRawQuery());
        Map<String, String> q2 = FakePlatform.query(segunda.getRawQuery());
        assertNotEquals(q1.get("state"), q2.get("state"));
        assertNotEquals(q1.get("code_challenge"), q2.get("code_challenge"));
        // o retorno antigo, na porta antiga: ninguem escutando
        assertEquals(-1, FakeBrowser.get(retornoDaPrimeira).status());
        // o codigo e o state antigos na porta da tentativa nova: recusado, sem chegar a API
        String naNova = q2.get("redirect_uri") + retornoDaPrimeira.substring(retornoDaPrimeira.indexOf('?'));
        assertEquals(400, FakeBrowser.get(naNova).status());
        assertEquals(0, platform.requestsTo("POST /api/v1/desktop/token").size());
        assertEquals(Phase.WAITING, flow.state().phase());
    }

    @Test
    void duasTentativasConcorrentesNaoSeAutenticamUmaAOutra() throws Exception {
        browser.decide = (page, redirect) -> null;
        // dois aplicativos (duas instancias do fluxo) esperando ao mesmo tempo
        PlatformApi api2 = new PlatformApi(platform.origin(), platform.clientTrust, Duration.ofSeconds(3), Duration.ofSeconds(4));
        BrowserSignIn signIn2 = new BrowserSignIn(platform.origin(), api2, browser, new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
        SignInFlow flow2 = new SignInFlow(signIn2, Runnable::run);
        try {
            flow.start();
            awaitPhase(Phase.WAITING);
            URI pageA = browser.lastPage();
            flow2.start();
            await("segunda esperando", () -> flow2.state().phase() == Phase.WAITING && browser.opened.size() == 2);
            URI pageB = browser.lastPage();
            String redirectA = FakePlatform.query(pageA.getRawQuery()).get("redirect_uri");
            String redirectB = FakePlatform.query(pageB.getRawQuery()).get("redirect_uri");
            assertNotEquals(redirectA, redirectB, "portas proprias");
            // o retorno de B entregue na porta de A
            String retornoB = platform.authorize(pageB);
            assertEquals(400, FakeBrowser.get(redirectA + retornoB.substring(retornoB.indexOf('?'))).status());
            assertEquals(Phase.WAITING, flow.state().phase());
            assertEquals(Phase.WAITING, flow2.state().phase());
            // B conclui com o proprio retorno; A continua esperando
            assertEquals(200, FakeBrowser.get(retornoB).status());
            await("B dentro", () -> flow2.state().phase() == Phase.SIGNED_IN);
            assertEquals(Phase.WAITING, flow.state().phase());
        } finally {
            flow2.close();
            signIn2.close();
        }
    }

    // ------------------------------------------------------------------ cancelamento, prazo, falhas

    @Test
    void recusaNoNavegadorTerminaComAMensagemDeEntradaNaoAutorizada() throws Exception {
        browser.decide = (page, redirect) -> platform.deny(page);
        flow.start();
        awaitNotice(Notice.DECLINED);
        assertEquals("Entrada não autorizada. Você pode tentar novamente quando quiser.", com.nekkora.client.ui.SignInText.of(flow.state().notice()));
        assertTrue(visit(0).body().contains("Entrada cancelada"));
        assertEquals(0, platform.requestsTo("POST /api/v1/desktop/token").size(), "sem codigo, nada vai a API");
        assertFalse(seen.stream().anyMatch(s -> s.phase() == Phase.SIGNED_IN));
        int port = Integer.parseInt(FakePlatform.query(browser.lastPage().getRawQuery()).get("redirect_uri").replaceAll(".*:(\\d+)/callback", "$1"));
        await("retorno local fechado depois da recusa", () -> FakeBrowser.get("http://127.0.0.1:" + port + "/callback").status() == -1);
    }

    @Test
    void recusaSemACorrelacaoDaTentativaNaoEncerraNada() throws Exception {
        browser.decide = (page, redirect) -> null;
        flow.start();
        awaitPhase(Phase.WAITING);
        Map<String, String> q = FakePlatform.query(browser.lastPage().getRawQuery());
        String retorno = q.get("redirect_uri");
        String iss = "&iss=" + java.net.URLEncoder.encode(platform.issuer(), java.nio.charset.StandardCharsets.UTF_8);
        for (String ruim : List.of(
                "error=access_denied", // parametro solto
                "error=access_denied" + iss, // sem state
                "error=access_denied&state=" + Pkce.generate().state() + iss, // state de outra tentativa
                "error=access_denied&state=" + q.get("state"), // sem emissor
                "error=access_denied&state=" + q.get("state") + "&iss=" + java.net.URLEncoder.encode("https://outro.exemplo.test", java.nio.charset.StandardCharsets.UTF_8),
                "error=server_error&error_description=falha&state=" + q.get("state") + iss, // parametro fora do contrato
                "error=access_denied&message=" + java.net.URLEncoder.encode("Acesso negado", java.nio.charset.StandardCharsets.UTF_8) + "&state=" + q.get("state") + iss)) {
            assertEquals(400, FakeBrowser.get(retorno + "?" + ruim).status(), ruim);
        }
        Thread.sleep(150);
        assertEquals(new SignInState(Phase.WAITING, Notice.NONE), flow.state(), "a tentativa legitima continua esperando, sem mensagem");
        // e ainda pode ser concluida pela pessoa
        assertEquals(200, FakeBrowser.get(platform.authorize(browser.lastPage())).status());
        awaitPhase(Phase.SIGNED_IN);
    }

    @Test
    void erroDesconhecidoComACorrelacaoCertaEncerraComoRetornoInvalidoNuncaComoRecusaOuErroDoServidor() throws Exception {
        browser.decide = (page, redirect) -> redirect.split("\\?")[0] + "?error=server_error&state=" + FakePlatform.query(page.getRawQuery()).get("state")
                + "&iss=" + java.net.URLEncoder.encode(platform.issuer(), java.nio.charset.StandardCharsets.UTF_8);
        flow.start();
        awaitNotice(Notice.INVALID_RETURN);
        assertEquals(0, platform.requestsTo("POST /api/v1/desktop/token").size());
    }

    @Test
    void acessoNegadoPelaPlatformSoComAEvidenciaDoContrato() throws Exception {
        // SIMULADO: a Platform atual nao tem regra que negue uma conta nesta etapa; aqui o servidor simulado responde o 403 do contrato
        platform.tokenStatus = 403;
        platform.tokenBody = "{\"error\":\"forbidden\"}";
        flow.start();
        awaitNotice(Notice.ACCESS_DENIED);
        assertEquals("Acesso não permitido. Sua conta não está autorizada a entrar neste aplicativo.",
                com.nekkora.client.ui.SignInText.of(Notice.ACCESS_DENIED));
        assertTrue(visit(0).body().contains("Não foi possível confirmar o login"));
        assertEquals(0, platform.sessions.size());

        // um 403 que nao e o do contrato (pagina de um intermediario) nao vira "acesso negado"
        platform.tokenContentType = "text/html";
        platform.tokenBody = "<html>403 Forbidden</html>";
        flow.start();
        awaitNotice(Notice.INVALID_RETURN);
        assertFalse(seen.stream().anyMatch(s -> s.phase() == Phase.SIGNED_IN));
    }

    @Test
    void semConexaoNaTrocaEFalhaDeConexao() throws Exception {
        // a API fica fora do ar depois de a pessoa autorizar: a troca nao conecta
        flow.close();
        signIn.close();
        java.net.ServerSocket livre = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        int porta = livre.getLocalPort();
        livre.close();
        java.util.Properties p = new java.util.Properties();
        p.setProperty(PlatformOrigin.BROWSER_PROPERTY, platform.issuer());
        p.setProperty(PlatformOrigin.API_PROPERTY, "https://localhost:" + porta);
        PlatformOrigin semApi = PlatformOrigin.resolve(p);
        signIn = new BrowserSignIn(semApi, new PlatformApi(semApi, platform.clientTrust, Duration.ofSeconds(2), Duration.ofSeconds(2)), browser,
                new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
        flow = new SignInFlow(signIn, Runnable::run);
        flow.addListener(seen::add);
        flow.start();
        awaitNotice(Notice.NETWORK);
        assertEquals("Não foi possível conectar à Platform. Verifique sua conexão e tente novamente.", com.nekkora.client.ui.SignInText.of(Notice.NETWORK));
        assertEquals(0, platform.tokenBodies.size());
        assertFalse(seen.stream().anyMatch(s -> s.phase() == Phase.SIGNED_IN));
    }

    @Test
    void depoisDeCadaErroANovaTentativaUsaIdentificadoresNovosEEntra() throws Exception {
        // 1) recusa no navegador
        browser.decide = (page, redirect) -> platform.deny(page);
        flow.start();
        awaitNotice(Notice.DECLINED);
        // 2) erro do servidor na troca
        browser.decide = (page, redirect) -> redirect;
        platform.tokenStatus = 500;
        platform.tokenBody = "{\"error\":\"internal\"}";
        flow.start();
        awaitNotice(Notice.SERVER);
        assertEquals("Não foi possível concluir a entrada. A Platform encontrou um problema. Tente novamente em instantes.",
                com.nekkora.client.ui.SignInText.of(Notice.SERVER));
        // 3) a Platform volta: nova tentativa, do zero
        platform.tokenStatus = null;
        platform.tokenBody = null;
        flow.start();
        awaitPhase(Phase.SIGNED_IN);

        assertEquals(3, browser.opened.size(), "uma aba por tentativa");
        java.util.Set<String> states = new java.util.HashSet<>();
        java.util.Set<String> challenges = new java.util.HashSet<>();
        for (URI page : browser.opened) {
            Map<String, String> q = FakePlatform.query(page.getRawQuery());
            states.add(q.get("state"));
            challenges.add(q.get("code_challenge"));
        }
        assertEquals(3, states.size(), "state novo a cada tentativa");
        assertEquals(3, challenges.size(), "desafio PKCE novo a cada tentativa");
        // os dois verificadores que chegaram a API sao diferentes, e cada um e o do desafio da propria tentativa
        assertEquals(2, platform.tokenBodies.size());
        String v2 = FakePlatform.query(platform.tokenBodies.get(0)).get("code_verifier");
        String v3 = FakePlatform.query(platform.tokenBodies.get(1)).get("code_verifier");
        assertNotEquals(v2, v3);
        assertEquals(FakePlatform.query(browser.opened.get(1).getRawQuery()).get("code_challenge"), Pkce.s256(v2));
        assertEquals(FakePlatform.query(browser.opened.get(2).getRawQuery()).get("code_challenge"), Pkce.s256(v3));
        assertNotEquals(FakePlatform.query(platform.tokenBodies.get(0)).get("code"), FakePlatform.query(platform.tokenBodies.get(1)).get("code"));
        // os retornos locais das tentativas que falharam ja estao fechados
        for (int i = 0; i < 2; i++) {
            String antigo = FakePlatform.query(browser.opened.get(i).getRawQuery()).get("redirect_uri");
            await("retorno " + i + " fechado", () -> FakeBrowser.get(antigo).status() == -1);
        }
        assertEquals(1, platform.sessions.size());
    }

    @Test
    void cancelarNoAplicativoFechaORetornoLocal() throws Exception {
        browser.decide = (page, redirect) -> null;
        flow.start();
        awaitPhase(Phase.WAITING);
        String retorno = platform.authorize(browser.lastPage());
        flow.cancel();
        assertEquals(new SignInState(Phase.IDLE, Notice.CANCELLED), flow.state());
        assertEquals(-1, FakeBrowser.get(retorno).status(), "depois de cancelar, o retorno nao e mais recebido");
        assertEquals(0, platform.requestsTo("POST /api/v1/desktop/token").size());
    }

    @Test
    void cancelarDuranteATrocaDescartaASessaoQueChegarDepois() throws Exception {
        platform.tokenDelayMillis = 1200;
        flow.start();
        await("troca em andamento", () -> platform.tokenBodies.size() == 1);
        flow.cancel();
        assertEquals(new SignInState(Phase.IDLE, Notice.CANCELLED), flow.state());
        // a API termina a troca e emite a sessao; o aplicativo nao a usa e a encerra
        await("sessao tardia encerrada", () -> platform.revoked.size() == 1);
        assertEquals(0, platform.sessions.size());
        assertEquals(new SignInState(Phase.IDLE, Notice.CANCELLED), flow.state(), "resposta atrasada nao abre a tela inicial");
        assertFalse(seen.stream().anyMatch(s -> s.phase() == Phase.SIGNED_IN));
    }

    @Test
    void tentativaExpiraEFechaORetorno() throws Exception {
        flow.close();
        signIn.close();
        start(new BrowserSignIn.Timing(Duration.ofMillis(700), Duration.ZERO));
        browser.decide = (page, redirect) -> null; // a pessoa fechou a aba: o aplicativo nao tem como saber
        flow.start();
        awaitPhase(Phase.WAITING);
        String retorno = platform.authorize(browser.lastPage());
        awaitNotice(Notice.EXPIRED);
        assertEquals("O tempo para entrar terminou. Clique em Entrar para iniciar uma nova tentativa.", com.nekkora.client.ui.SignInText.of(Notice.EXPIRED));
        await("retorno fechado apos o prazo", () -> FakeBrowser.get(retorno).status() == -1);
        assertEquals(0, platform.sessions.size(), "retorno depois do prazo nao vira sessao");
    }

    @Test
    void falhaAoAbrirONavegadorEDepoisNovaTentativa() throws Exception {
        browser.failToOpen = new IOException("sem navegador");
        flow.start();
        awaitNotice(Notice.BROWSER_FAILED);
        browser.failToOpen = null;
        flow.start();
        awaitPhase(Phase.SIGNED_IN);
    }

    @Test
    void falhasDeRedeTlsETempoNaTroca() throws Exception {
        // TLS: a JVM nao confia no certificado da Platform simulada
        flow.close();
        signIn.close();
        PlatformApi semConfianca = new PlatformApi(platform.origin(), null, Duration.ofSeconds(3), Duration.ofSeconds(3));
        signIn = new BrowserSignIn(platform.origin(), semConfianca, browser, new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
        flow = new SignInFlow(signIn, Runnable::run);
        flow.start();
        awaitNotice(Notice.TLS);
        assertEquals(0, platform.tokenBodies.size());

        // tempo esgotado
        flow.close();
        signIn.close();
        platform.tokenDelayMillis = 2500;
        PlatformApi lento = new PlatformApi(platform.origin(), platform.clientTrust, Duration.ofSeconds(2), Duration.ofMillis(500));
        signIn = new BrowserSignIn(platform.origin(), lento, browser, new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
        flow = new SignInFlow(signIn, Runnable::run);
        flow.start();
        awaitNotice(Notice.TIMEOUT);
        // resultado incerto: o aplicativo nao reapresenta o codigo
        Thread.sleep(300);
        assertEquals(1, platform.requestsTo("POST /api/v1/desktop/token").size());
    }

    @Test
    void sessaoQueAApiNaoConfirmaNaoEntra() throws Exception {
        platform.sessionStatus = 401;
        platform.sessionBody = "{\"error\":\"unauthenticated\"}";
        flow.start();
        awaitNotice(Notice.INVALID_RETURN);
        await("token emitido e encerrado", () -> platform.revoked.size() == 1);
    }

    // ------------------------------------------------------------------ sessao

    @Test
    void sessaoExpiraNoPrazoEVoltaParaAEntrada() throws Exception {
        platform.expiresIn = 1;
        flow.start();
        awaitPhase(Phase.SIGNED_IN);
        awaitNotice(Notice.SESSION_EXPIRED);
        assertEquals("Sua sessão terminou. Entre novamente.", com.nekkora.client.ui.SignInText.of(Notice.SESSION_EXPIRED));
        // e uma nova entrada funciona
        platform.expiresIn = 900;
        flow.start();
        awaitPhase(Phase.SIGNED_IN);
    }

    @Test
    void sessaoRevogadaNaContaEDetectadaNaConferencia() throws Exception {
        flow.start();
        awaitPhase(Phase.SIGNED_IN);
        flow.checkSession();
        await("conferida", () -> platform.requestsTo("GET /api/v1/desktop/session").size() == 2);
        assertEquals(Phase.SIGNED_IN, flow.state().phase());
        platform.sessions.clear(); // logout no navegador, troca de senha...: a Platform deixa de reconhecer a sessao
        flow.checkSession();
        awaitNotice(Notice.SESSION_EXPIRED);
    }

    @Test
    void falhaDeRedeNaConferenciaNaoEncerraASessao() throws Exception {
        flow.start();
        awaitPhase(Phase.SIGNED_IN);
        platform.sessionStatus = 503;
        platform.sessionBody = "{}";
        flow.checkSession();
        await("conferida", () -> platform.requestsTo("GET /api/v1/desktop/session").size() == 2);
        Thread.sleep(150);
        assertEquals(Phase.SIGNED_IN, flow.state().phase());
    }

    @Test
    void conferenciaRespeitaOIntervaloMinimo() throws Exception {
        flow.close();
        signIn.close();
        start(new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ofMinutes(1)));
        flow.start();
        awaitPhase(Phase.SIGNED_IN);
        for (int i = 0; i < 20; i++) {
            flow.checkSession();
        }
        Thread.sleep(250);
        assertEquals(1, platform.requestsTo("GET /api/v1/desktop/session").size(), "sem consulta continua: so a conferencia da entrada");
    }

    @Test
    void fecharOAplicativoEncerraASessaoNaPlatformENaoGuardaNada() throws Exception {
        flow.start();
        awaitPhase(Phase.SIGNED_IN);
        assertEquals(1, platform.sessions.size());
        flow.close();
        signIn.close();
        assertEquals(1, platform.revoked.size(), "sessao encerrada na Platform ao fechar");
        assertEquals(0, platform.sessions.size());
        // reabrir: fluxo novo comeca na entrada
        start(new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
        assertEquals(SignInState.INITIAL, flow.state());
    }

    @Test
    void fecharDuranteAEsperaInvalidaATentativa() throws Exception {
        browser.decide = (page, redirect) -> null;
        flow.start();
        awaitPhase(Phase.WAITING);
        String retorno = platform.authorize(browser.lastPage());
        flow.close();
        signIn.close();
        assertEquals(-1, FakeBrowser.get(retorno).status());
        assertEquals(0, platform.requestsTo("POST /api/v1/desktop/token").size());
        start(new BrowserSignIn.Timing(Duration.ofSeconds(20), Duration.ZERO));
    }
}
