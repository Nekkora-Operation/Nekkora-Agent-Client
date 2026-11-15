package com.nekkora.client.signin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nekkora.client.signin.FakeSignIn.FakeAttempt;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInState.Notice;
import com.nekkora.client.signin.SignInState.Phase;

/** Regras do fluxo de entrada, sem interface: uma tentativa por vez, nada atrasado entra, fechar encerra. */
class SignInFlowTest {

    private FakeSignIn fake;
    private SignInFlow flow;
    private List<SignInState> seen;

    @BeforeEach
    void setUp() {
        fake = new FakeSignIn();
        flow = new SignInFlow(fake, Runnable::run);
        seen = new ArrayList<>();
        flow.addListener(seen::add);
    }

    private static SignInState s(Phase p, Notice n) {
        return new SignInState(p, n);
    }

    @Test
    void caminhoFeliz() {
        assertEquals(SignInState.INITIAL, flow.state());
        flow.start();
        assertEquals(s(Phase.OPENING_BROWSER, Notice.NONE), flow.state());
        fake.last().listener.browserOpened();
        assertEquals(s(Phase.WAITING, Notice.NONE), flow.state());
        FakeSession session = new FakeSession();
        fake.last().listener.signedIn(session);
        assertEquals(s(Phase.SIGNED_IN, Notice.NONE), flow.state());
        assertEquals(0, session.ended);
        assertEquals(List.of(s(Phase.OPENING_BROWSER, Notice.NONE), s(Phase.WAITING, Notice.NONE), s(Phase.SIGNED_IN, Notice.NONE)), seen);
        assertEquals(1, fake.last().cancelled, "os recursos locais da tentativa sao liberados no sucesso");
    }

    @Test
    void cliquesRepetidosAbremUmaUnicaTentativa() {
        for (int i = 0; i < 10; i++) {
            flow.start();
        }
        assertEquals(1, fake.attempts.size());
        fake.last().listener.browserOpened();
        for (int i = 0; i < 10; i++) {
            flow.start();
        }
        assertEquals(1, fake.attempts.size());
        fake.last().listener.signedIn(new FakeSession());
        flow.start();
        assertEquals(1, fake.attempts.size(), "ja dentro: Entrar nao abre outra tentativa");
    }

    @Test
    void cancelarNoAplicativoVoltaAoInicioComAvisoNeutro() {
        flow.start();
        FakeAttempt a = fake.last();
        a.listener.browserOpened();
        flow.cancel();
        assertEquals(s(Phase.IDLE, Notice.CANCELLED), flow.state());
        assertEquals(1, a.cancelled);
        flow.cancel();
        assertEquals(1, a.cancelled, "cancelar sem tentativa em curso nao faz nada");
        assertEquals(s(Phase.IDLE, Notice.CANCELLED), flow.state());
        // cancelar antes de o navegador abrir tambem vale, e a tentativa nao fica viva
        flow.start();
        assertEquals(s(Phase.OPENING_BROWSER, Notice.NONE), flow.state(), "a nova tentativa limpa o aviso anterior");
        flow.cancel();
        assertEquals(s(Phase.IDLE, Notice.CANCELLED), flow.state());
        assertEquals(1, fake.last().cancelled);
    }

    @Test
    void recusaNoNavegadorEResultadoDaTentativaNaoCancelamento() {
        flow.start();
        FakeAttempt a = fake.last();
        a.listener.browserOpened();
        a.listener.failed(Notice.DECLINED);
        assertEquals(s(Phase.IDLE, Notice.DECLINED), flow.state());
        assertEquals(1, a.cancelled, "recursos da tentativa liberados");
        // depois do resultado, nada mais daquela tentativa muda a tela
        a.listener.failed(Notice.SERVER);
        a.listener.signedIn(new FakeSession());
        assertEquals(s(Phase.IDLE, Notice.DECLINED), flow.state());
    }

    @Test
    void cancelarDepoisDoResultadoNaoApagaAMensagem() {
        flow.start();
        fake.last().listener.browserOpened();
        fake.last().listener.failed(Notice.SERVER);
        flow.cancel();
        assertEquals(s(Phase.IDLE, Notice.SERVER), flow.state(), "sem tentativa em curso, Cancelar nao faz nada");
    }

    @Test
    void depoisDeCadaResultadoUmaNovaTentativaEntra() {
        for (Notice n : new Notice[] {Notice.DECLINED, Notice.ACCESS_DENIED, Notice.SERVER, Notice.NETWORK, Notice.EXPIRED, Notice.INVALID_RETURN,
                Notice.TLS}) {
            SignInFlow f = new SignInFlow(fake, Runnable::run);
            f.start();
            FakeAttempt falhou = fake.last();
            falhou.listener.browserOpened();
            falhou.listener.failed(n);
            assertEquals(s(Phase.IDLE, n), f.state());
            f.start();
            FakeAttempt nova = fake.last();
            assertFalse(nova == falhou, n + ": tentativa nova, nao a anterior");
            assertEquals(s(Phase.OPENING_BROWSER, Notice.NONE), f.state(), n + ": mensagem limpa");
            // o que chegar da tentativa que falhou nao decide a nova
            FakeSession daAntiga = new FakeSession();
            falhou.listener.signedIn(daAntiga);
            falhou.listener.failed(Notice.NETWORK);
            assertEquals(1, daAntiga.ended, n + ": sessao atrasada descartada");
            assertEquals(s(Phase.OPENING_BROWSER, Notice.NONE), f.state());
            nova.listener.browserOpened();
            nova.listener.signedIn(new FakeSession());
            assertEquals(s(Phase.SIGNED_IN, Notice.NONE), f.state(), n + ": a nova tentativa entra");
            f.close();
        }
    }

    @Test
    void respostaAtrasadaDeTentativaCanceladaNaoEntra() {
        flow.start();
        FakeAttempt antiga = fake.last();
        antiga.listener.browserOpened();
        flow.cancel();
        FakeSession tardia = new FakeSession();
        antiga.listener.signedIn(tardia);
        assertEquals(s(Phase.IDLE, Notice.CANCELLED), flow.state(), "a tela inicial nao abre");
        assertEquals(1, tardia.ended, "a sessao que chegou tarde e encerrada, nao guardada");
        antiga.listener.failed(Notice.NETWORK);
        antiga.listener.browserOpened();
        assertEquals(s(Phase.IDLE, Notice.CANCELLED), flow.state(), "nenhum aviso atrasado muda a tela");
    }

    @Test
    void tentativaAnteriorNaoAutenticaANova() {
        flow.start();
        FakeAttempt primeira = fake.last();
        primeira.listener.browserOpened();
        flow.cancel();
        flow.start();
        FakeAttempt segunda = fake.last();
        segunda.listener.browserOpened();
        FakeSession daPrimeira = new FakeSession();
        primeira.listener.signedIn(daPrimeira);
        assertEquals(s(Phase.WAITING, Notice.NONE), flow.state(), "a segunda continua esperando");
        assertEquals(1, daPrimeira.ended);
        primeira.listener.failed(Notice.INVALID_RETURN);
        assertEquals(s(Phase.WAITING, Notice.NONE), flow.state());
        segunda.listener.signedIn(new FakeSession());
        assertEquals(Phase.SIGNED_IN, flow.state().phase());
    }

    @Test
    void cadaFalhaVoltaAoInicioComOProprioAvisoEPermiteNovaTentativa() {
        for (Notice n : new Notice[] {Notice.DECLINED, Notice.ACCESS_DENIED, Notice.EXPIRED, Notice.BROWSER_FAILED, Notice.RETURN_UNAVAILABLE, Notice.NETWORK, Notice.TIMEOUT, Notice.TLS,
                Notice.SERVER, Notice.RATE_LIMITED, Notice.INVALID_RETURN, Notice.UNAVAILABLE}) {
            flow.start();
            FakeAttempt a = fake.last();
            a.listener.failed(n);
            assertEquals(s(Phase.IDLE, n), flow.state());
            assertEquals(1, a.cancelled, n + ": recursos liberados");
        }
        int antes = fake.attempts.size();
        flow.start();
        assertEquals(antes + 1, fake.attempts.size(), "depois de uma falha, Entrar abre uma tentativa nova");
        assertEquals(Notice.NONE, flow.state().notice(), "a mensagem antiga some ao tentar de novo");
    }

    @Test
    void falhaAoPrepararNaoDeixaATelaPresa() {
        fake.failOnBegin = new IllegalStateException("x");
        flow.start();
        assertEquals(s(Phase.IDLE, Notice.UNAVAILABLE), flow.state());
    }

    @Test
    void sessaoExpiradaVoltaParaAEntradaComAviso() {
        flow.start();
        FakeSession session = new FakeSession();
        fake.last().listener.signedIn(session);
        flow.checkSession();
        assertEquals(1, session.checks);
        session.expire();
        assertEquals(s(Phase.IDLE, Notice.SESSION_EXPIRED), flow.state());
        assertEquals(1, session.ended, "o token e descartado");
        session.expire();
        assertEquals(s(Phase.IDLE, Notice.SESSION_EXPIRED), flow.state());
        flow.checkSession();
        assertEquals(1, session.checks, "sem sessao, nada a conferir");
    }

    @Test
    void fecharDuranteAEsperaCancelaEInvalidaATentativa() {
        flow.start();
        FakeAttempt a = fake.last();
        a.listener.browserOpened();
        int avisos = seen.size();
        flow.close();
        assertEquals(1, a.cancelled);
        FakeSession tardia = new FakeSession();
        a.listener.signedIn(tardia);
        assertEquals(1, tardia.ended, "resposta que chega depois de fechar nao vira sessao");
        assertFalse(flow.state().phase() == Phase.SIGNED_IN);
        assertEquals(avisos, seen.size(), "nenhum aviso depois de fechar");
        flow.start();
        assertEquals(1, fake.attempts.size(), "fechado: nao abre tentativa");
    }

    @Test
    void fecharDepoisDeEntrarEncerraASessao() {
        flow.start();
        FakeSession session = new FakeSession();
        fake.last().listener.signedIn(session);
        flow.close();
        flow.close();
        assertEquals(1, session.ended);
        session.expire();
        assertTrue(flow.state().phase() == Phase.SIGNED_IN, "inerte depois de fechar: nenhum estado novo");
    }

    @Test
    void umFluxoNovoNaoHerdaNadaDoAnterior() {
        flow.start();
        fake.last().listener.signedIn(new FakeSession());
        flow.close();
        SignInFlow outro = new SignInFlow(new FakeSignIn(), Runnable::run);
        assertEquals(SignInState.INITIAL, outro.state(), "reabrir o aplicativo comeca na entrada");
    }
}
