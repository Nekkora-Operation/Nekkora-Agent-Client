package com.nekkora.client.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nekkora.client.nav.Directory.Failure;
import com.nekkora.client.nav.Loadable.Status;

/**
 * Organizacao, ambiente e automacoes (AGENT-CLIENT-NAVEGACAO-001 T03), sem interface e com a Platform SIMULADA: a troca
 * de contexto apaga o que dependia do contexto anterior, e a resposta atrasada nunca aparece no contexto novo.
 */
class NavigationContextTest {

    private FakeDirectory d;
    private FakeDirectory.Manual background;
    private Navigation nav;
    private final Organization a = FakeDirectory.org(1, "Acme");
    private final Organization b = FakeDirectory.org(2, "Beta");
    private final Workspace a1 = FakeDirectory.ws(1, "Financeiro");
    private final Workspace a2 = FakeDirectory.ws(2, "Fiscal");
    private final Workspace b1 = FakeDirectory.ws(3, "Financeiro");

    @BeforeEach
    void setUp() {
        d = new FakeDirectory();
        d.organizations = List.of(a, b);
        d.workspaces.put(a.id(), List.of(a1, a2));
        d.workspaces.put(b.id(), List.of(b1));
        d.automations.put(a1.id(), FakeDirectory.listing(true, true, FakeDirectory.automation(1, "Conciliação", 3, "1.2.0")));
        d.automations.put(a2.id(), FakeDirectory.listing(false, false));
        d.automations.put(b1.id(), FakeDirectory.listing(false, true, FakeDirectory.automation(9, "Folha", 1, null)));
        background = new FakeDirectory.Manual();
        nav = new Navigation(d, background, Runnable::run);
        nav.start();
        background.runAll();
    }

    @Test
    void escolherAOrganizacaoCarregaOsAmbientesENaoEscolheNenhum() {
        assertTrue(nav.select(a));
        assertEquals(Status.LOADING, nav.state().workspaces().status());
        assertNull(nav.state().workspace());
        background.runAll();
        assertEquals(List.of(a1, a2), nav.state().workspaces().value());
        assertNull(nav.state().workspace(), "nenhum ambiente e escolhido sozinho");
        assertEquals(Status.IDLE, nav.state().automations().status(), "sem ambiente, nenhuma consulta de automacoes");
        assertNull(nav.state().context());
        assertFalse(d.calls.stream().anyMatch(c -> c.startsWith("automations")));
    }

    @Test
    void umSoAmbienteTambemNaoEEscolhidoSozinho() {
        nav.select(b);
        background.runAll();
        assertEquals(List.of(b1), nav.state().workspaces().value());
        assertNull(nav.state().workspace());
    }

    @Test
    void escolherOAmbienteCarregaAsAutomacoesDele() {
        nav.select(a);
        background.runAll();
        assertTrue(nav.selectWorkspace(a1));
        assertEquals(Status.LOADING, nav.state().automations().status());
        background.runAll();
        assertEquals("Conciliação", nav.state().automations().value().items().get(0).name());
        assertEquals(new Navigation.Context(a, a1), nav.state().context());
        assertTrue(nav.state().automations().value().capabilities().rename());
    }

    @Test
    void soEscolheAmbienteListadoNaOrganizacaoAtual() {
        nav.select(a);
        assertFalse(nav.selectWorkspace(a1), "ambientes ainda carregando");
        background.runAll();
        assertFalse(nav.selectWorkspace(b1), "ambiente de OUTRA organizacao nao esta na lista desta");
        assertFalse(nav.selectWorkspace(new Workspace(FakeDirectory.id(777), "Financeiro")), "mesmo nome, outro identificador");
        assertNull(nav.state().workspace());
        assertFalse(d.calls.stream().anyMatch(c -> c.startsWith("automations")), "nenhuma consulta com ambiente nao listado");
    }

    @Test
    void trocarDeAmbienteApagaAsAutomacoesDoAnteriorAntesDeCarregarAsDoNovo() {
        nav.select(a);
        background.runAll();
        nav.selectWorkspace(a1);
        background.runAll();
        assertEquals(1, nav.state().automations().value().items().size());
        nav.selectWorkspace(a2);
        assertEquals(a2, nav.state().workspace());
        assertNull(nav.state().automations().value(), "as automacoes do ambiente anterior somem na hora");
        assertEquals(Status.LOADING, nav.state().automations().status());
        background.runAll();
        assertEquals(List.of(), nav.state().automations().value().items(), "vazio legitimo do ambiente novo");
        // voltar a "Selecione um ambiente"
        assertTrue(nav.selectWorkspace(null));
        assertNull(nav.state().workspace());
        assertEquals(Status.IDLE, nav.state().automations().status());
        assertNull(nav.state().context());
    }

    @Test
    void respostaAtrasadaDoAmbienteAnteriorNaoApareceNoNovo() {
        nav.select(a);
        background.runAll();
        nav.selectWorkspace(a1);
        Runnable lenta = background.take(0); // a consulta do primeiro ambiente fica presa
        nav.selectWorkspace(a2);
        background.runAll();
        assertEquals(List.of(), nav.state().automations().value().items());
        lenta.run(); // chega agora, atrasada
        assertEquals(a2, nav.state().workspace());
        assertEquals(List.of(), nav.state().automations().value().items(), "a automacao do ambiente anterior nao aparece");
    }

    @Test
    void respostaAtrasadaDaOrganizacaoAnteriorNaoApareceNaNova() {
        nav.select(a);
        Runnable ambientesDeA = background.take(0);
        nav.back();
        nav.select(b);
        background.runAll();
        assertEquals(List.of(b1), nav.state().workspaces().value());
        ambientesDeA.run();
        assertEquals(b, nav.state().selected());
        assertEquals(List.of(b1), nav.state().workspaces().value(), "os ambientes da organizacao anterior nao aparecem");

        // e as automacoes: A/financeiro pendente, troca para B, escolhe o "Financeiro" de B (homonimo)
        nav.back();
        nav.select(a);
        background.runAll();
        nav.selectWorkspace(a1);
        Runnable automacoesDeA = background.take(0);
        nav.back();
        nav.select(b);
        background.runAll();
        nav.selectWorkspace(b1);
        background.runAll();
        automacoesDeA.run();
        assertEquals("Folha", nav.state().automations().value().items().get(0).name(), "mesmo nome de ambiente, organizacoes diferentes: vale a de B");
        assertEquals(new Navigation.Context(b, b1), nav.state().context());
    }

    @Test
    void voltarLimpaAmbientesAmbienteEAutomacoes() {
        nav.select(a);
        background.runAll();
        nav.selectWorkspace(a1);
        background.runAll();
        nav.back();
        assertNull(nav.state().selected());
        assertEquals(Status.IDLE, nav.state().workspaces().status());
        assertNull(nav.state().workspace());
        assertEquals(Status.IDLE, nav.state().automations().status());
        assertNull(nav.state().context(), "nenhum contexto para uma acao depois de voltar");
        assertFalse(nav.selectWorkspace(a1), "sem organizacao, nenhum ambiente pode ser escolhido");
    }

    @Test
    void falhasDosAmbientesEDasAutomacoesNaoSaoListasVazias() {
        d.workspacesFailure = Failure.RATE_LIMITED;
        nav.select(a);
        background.runAll();
        assertEquals(Loadable.failed(Failure.RATE_LIMITED), nav.state().workspaces());
        d.workspacesFailure = null;
        nav.loadWorkspaces();
        background.runAll();
        assertEquals(List.of(a1, a2), nav.state().workspaces().value());
        for (Failure f : new Failure[] {Failure.NETWORK, Failure.SERVER, Failure.RATE_LIMITED, Failure.NOT_FOUND, Failure.DENIED}) {
            d.automationsFailure = f;
            nav.selectWorkspace(a1);
            background.runAll();
            assertEquals(Loadable.failed(f), nav.state().automations(), f.toString());
        }
        d.automationsFailure = null;
        nav.loadAutomations();
        background.runAll();
        assertTrue(nav.state().automations().isReady());
    }

    @Test
    void acessoRevogadoComATelaAbertaApareceNaConsultaSeguinte() {
        nav.select(a);
        background.runAll();
        nav.selectWorkspace(a1);
        background.runAll();
        // o vinculo com a organizacao A e revogado na Platform
        d.workspaces.remove(a.id());
        d.automations.remove(a1.id());
        nav.loadAutomations();
        background.runAll();
        assertEquals(Loadable.failed(Failure.NOT_FOUND), nav.state().automations(), "a lista antiga nao e mantida depois da recusa");
        nav.loadWorkspaces();
        background.runAll();
        assertEquals(Loadable.failed(Failure.NOT_FOUND), nav.state().workspaces());
        assertNull(nav.state().context(), "sem ambiente valido, nenhuma acao tem contexto");
    }

    @Test
    void recarregarAmbientesDesfazAEscolha() {
        nav.select(a);
        background.runAll();
        nav.selectWorkspace(a1);
        background.runAll();
        d.workspaces.put(a.id(), List.of(a2)); // a pessoa perdeu o acesso ao ambiente a1
        nav.loadWorkspaces();
        assertNull(nav.state().workspace());
        background.runAll();
        assertFalse(nav.selectWorkspace(a1), "o ambiente que saiu da lista nao pode mais ser escolhido");
    }
}
