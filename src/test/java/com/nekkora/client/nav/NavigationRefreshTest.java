package com.nekkora.client.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nekkora.client.nav.Directory.Failure;
import com.nekkora.client.nav.Loadable.Status;
import com.nekkora.client.nav.Navigation.Refresh;
import com.nekkora.client.nav.Navigation.Refresh.Outcome;
import com.nekkora.client.nav.Navigation.Refresh.Phase;

/**
 * "Atualizar" (AGENT-CLIENT-NAVEGACAO-002 T01), sem interface e com a Platform SIMULADA: o que a pessoa passou a poder ver
 * aparece sem novo login; a escolha e mantida pelos identificadores; o que saiu do alcance sai da tela; falha nao vira
 * lista vazia nem sucesso; resposta atrasada nao entra em outro contexto.
 */
class NavigationRefreshTest {

    private FakeDirectory d;
    private FakeDirectory.Manual background;
    private Navigation nav;
    private final List<Navigation.State> seen = new ArrayList<>();
    private final Organization a = FakeDirectory.org(1, "Acme");
    private final Organization b = FakeDirectory.org(2, "Beta");
    private final Organization nova = FakeDirectory.org(3, "Criada depois do login");
    private final Workspace a1 = FakeDirectory.ws(1, "Financeiro");
    private final Workspace a2 = FakeDirectory.ws(2, "Fiscal");
    private final Workspace a3 = FakeDirectory.ws(5, "Criado depois");
    private final Workspace b1 = FakeDirectory.ws(3, "Financeiro");
    private final Automation conc = FakeDirectory.automation(1, "Conciliação", 3, "1.2.0");

    @BeforeEach
    void setUp() {
        d = new FakeDirectory();
        d.organizations = List.of(a, b);
        d.workspaces.put(a.id(), List.of(a1, a2));
        d.workspaces.put(b.id(), List.of(b1));
        d.automations.put(a1.id(), FakeDirectory.listing(true, true, conc));
        d.automations.put(a2.id(), FakeDirectory.listing(false, false));
        d.automations.put(b1.id(), FakeDirectory.listing(false, true, FakeDirectory.automation(9, "Folha", 1, null)));
        background = new FakeDirectory.Manual();
        nav = new Navigation(d, background, Runnable::run);
        nav.addListener(seen::add);
        nav.start();
        background.runAll();
    }

    private void enter(Organization o, Workspace w) {
        assertTrue(nav.select(o));
        background.runAll();
        if (w != null) {
            assertTrue(nav.selectWorkspace(w));
            background.runAll();
        }
        d.calls.clear();
        seen.clear();
    }

    private Refresh refresh() {
        return nav.state().refresh();
    }

    // ------------------------------------------------------------------ seletor de organizacoes

    @Test
    void organizacaoCriadaDepoisDoLoginApareceSemEntrarDeNovo() {
        int before = d.organizationCalls.get();
        d.organizations = List.of(a, b, nova);
        assertTrue(nav.refresh());
        // enquanto consulta, a lista de antes continua na tela (nao vira "carregando" nem vazia)
        assertEquals(Phase.RUNNING, refresh().phase());
        assertEquals(List.of(a, b), nav.state().organizations().value());
        background.runAll();
        assertEquals(List.of(a, b, nova), nav.state().organizations().value());
        assertEquals(new Refresh(Phase.DONE, null, Outcome.UPDATED, null), refresh());
        assertNull(nav.state().selected(), "nada e escolhido sozinho");
        assertEquals(before + 1, d.organizationCalls.get(), "uma consulta, com a sessao que ja existe");
        assertEquals(0, d.profileCalls.get() - 1, "atualizar nao refaz a entrada nem o perfil");
        // e a nova pode ser escolhida em seguida
        d.workspaces.put(nova.id(), List.of());
        assertTrue(nav.select(nova));
    }

    @Test
    void plataformaDoContratoAnteriorNaoEDadaComoAtualizada() {
        d.organizationsCurrent = false;
        assertTrue(nav.refresh());
        background.runAll();
        assertEquals(Phase.DONE, refresh().phase());
        assertEquals(Outcome.UPDATED_WITHOUT_NEW_ORGANIZATIONS, refresh().outcome(), "a lista foi lida, mas nao traz organizacoes novas");
        assertEquals(List.of(a, b), nav.state().organizations().value());
    }

    @Test
    void cliqueRepetidoNaoAcumulaConsultas() {
        int before = d.organizationCalls.get();
        assertTrue(nav.refresh());
        assertFalse(nav.refresh(), "ja ha uma atualizacao em curso");
        assertFalse(nav.refresh());
        assertEquals(1, background.size());
        background.runAll();
        assertEquals(before + 1, d.organizationCalls.get());
        // terminada, pode de novo; repetir da o mesmo resultado, sem duplicar itens
        assertTrue(nav.refresh());
        background.runAll();
        assertEquals(List.of(a, b), nav.state().organizations().value());
        assertEquals(before + 2, d.organizationCalls.get());
    }

    @Test
    void falhaNoSeletorMantemAListaDeAntesEDizQueNaoAtualizou() {
        for (Failure f : new Failure[] {Failure.NETWORK, Failure.TIMEOUT, Failure.TLS, Failure.SERVER, Failure.RATE_LIMITED, Failure.DENIED, Failure.INVALID,
                Failure.SESSION_ENDED}) {
            d.organizationsFailure = f;
            d.organizations = List.of();
            assertTrue(nav.refresh());
            background.runAll();
            assertEquals(new Refresh(Phase.FAILED, f, null, null), refresh(), f.toString());
            assertEquals(Status.READY, nav.state().organizations().status(), f + ": falha nao apaga nem esvazia a lista");
            assertEquals(List.of(a, b), nav.state().organizations().value(), f + ": continuam os dados de antes");
            // nova tentativa funciona
            d.organizationsFailure = null;
            d.organizations = List.of(a, b);
            assertTrue(nav.refresh());
            background.runAll();
            assertEquals(Phase.DONE, refresh().phase(), f.toString());
        }
        // resposta que nao e do contrato (excecao inesperada) tambem e falha, nunca lista vazia
        d.crash = new IllegalStateException("x");
        assertTrue(nav.refresh());
        background.runAll();
        assertEquals(Phase.FAILED, refresh().phase());
        assertEquals(Failure.INVALID, refresh().failure());
        assertEquals(List.of(a, b), nav.state().organizations().value());
    }

    @Test
    void antesDaPrimeiraListaAtualizarEACargaNormal() {
        Navigation fresh = new Navigation(d, background, Runnable::run);
        d.organizationsFailure = Failure.NETWORK;
        fresh.start();
        background.runAll();
        assertEquals(Status.FAILED, fresh.state().organizations().status());
        d.organizationsFailure = null;
        assertTrue(fresh.refresh());
        assertEquals(Status.LOADING, fresh.state().organizations().status(), "sem lista anterior, e a carga normal");
        assertEquals(Phase.NONE, fresh.state().refresh().phase());
        background.runAll();
        assertEquals(List.of(a, b), fresh.state().organizations().value());
    }

    // ------------------------------------------------------------------ dentro da organizacao

    @Test
    void semAmbienteEscolhidoAtualizaAListaSemEscolherNenhum() {
        enter(a, null);
        d.workspaces.put(a.id(), List.of(a1, a2, a3));
        assertTrue(nav.refresh());
        assertEquals(List.of(a1, a2), nav.state().workspaces().value(), "os de antes ate a resposta chegar");
        background.runAll();
        assertEquals(List.of(a1, a2, a3), nav.state().workspaces().value(), "o ambiente novo aparece");
        assertNull(nav.state().workspace(), "nenhum ambiente e escolhido sozinho");
        assertEquals(Status.IDLE, nav.state().automations().status());
        assertEquals(a.id(), nav.state().selected().id());
        assertEquals(Outcome.UPDATED, refresh().outcome());
        assertEquals(List.of("workspaces " + a.id()), d.calls, "so o que a tela mostra; nenhuma automacao consultada");
    }

    @Test
    void comAmbienteEscolhidoMantemAEscolhaPelosIdsETrazVersaoNovaECapacidades() {
        enter(a, a1);
        // na Platform: organizacao e ambiente renomeados, automacao nova, versao nova publicada, permissao de renomear retirada
        Organization aRenomeada = new Organization(a.id(), "Acme Renomeada", a.handle(), false);
        Workspace a1Renomeado = new Workspace(a1.id(), "Financeiro 2");
        Automation concV4 = FakeDirectory.automation(1, "Conciliação", 4, "1.3.0");
        Automation novaAuto = FakeDirectory.automation(7, "Nova na Platform", null, null);
        d.organizations = List.of(aRenomeada, b);
        d.workspaces.put(a.id(), List.of(a3, a1Renomeado, a2));
        d.automations.put(a1.id(), FakeDirectory.listing(false, true, concV4, novaAuto));
        Automation.Listing antes = nav.state().automations().value();
        assertTrue(nav.refresh());
        assertSame(antes, nav.state().automations().value(), "a lista de antes continua na tela enquanto consulta");
        assertEquals(a1, nav.state().workspace());
        background.runAll();

        Navigation.State s = nav.state();
        assertEquals(new Refresh(Phase.DONE, null, Outcome.UPDATED, null), s.refresh());
        assertEquals(a.id(), s.selected().id());
        assertEquals("Acme Renomeada", s.selected().name(), "mesma organizacao (id), com o nome de agora");
        assertEquals(a1.id(), s.workspace().id(), "mesmo ambiente (id): nenhum outro foi escolhido no lugar");
        assertEquals("Financeiro 2", s.workspace().name());
        assertEquals(List.of(a3, a1Renomeado, a2), s.workspaces().value(), "na ordem da Platform, sem duplicar");
        assertEquals(List.of(concV4, novaAuto), s.automations().value().items());
        assertEquals(4, s.automations().value().items().get(0).published().number(), "a versao publicada nova aparece");
        assertFalse(s.automations().value().capabilities().rename(), "capacidades de agora");
        assertEquals(new Navigation.Context(aRenomeada, a1Renomeado), s.context());
        assertEquals(List.of("workspaces " + a.id(), "automations " + a.id() + " " + a1.id()), d.calls, "cada lista consultada uma vez, do inicio");
        // a troca e de uma vez: nenhum estado intermediario misturou lista nova com lista antiga
        for (Navigation.State st : seen) {
            boolean novo = st.workspaces().isReady() && st.workspaces().value().contains(a3);
            boolean automacoesNovas = st.automations().isReady() && st.automations().value().items().contains(novaAuto);
            assertEquals(novo, automacoesNovas, "ambientes e automacoes trocam juntos");
        }
    }

    @Test
    void repetirAtualizacaoNaoDuplicaNemReordena() {
        enter(a, a1);
        for (int i = 0; i < 3; i++) {
            assertTrue(nav.refresh());
            background.runAll();
            assertEquals(List.of(a, b), nav.state().organizations().value());
            assertEquals(List.of(a1, a2), nav.state().workspaces().value());
            assertEquals(List.of(conc), nav.state().automations().value().items());
            assertEquals(a1, nav.state().workspace());
        }
    }

    @Test
    void ambienteQueSaiuDoAlcanceLimpaAEscolhaSemEscolherOutro() {
        // 1. o ambiente nao esta mais na lista da organizacao
        enter(a, a1);
        d.workspaces.put(a.id(), List.of(a2));
        assertTrue(nav.refresh());
        background.runAll();
        Navigation.State s = nav.state();
        assertEquals(new Refresh(Phase.DONE, null, Outcome.WORKSPACE_LOST, "Financeiro"), s.refresh());
        assertNull(s.workspace(), "a escolha sai; o ambiente que sobrou NAO e escolhido no lugar");
        assertEquals(Status.IDLE, s.automations().status(), "as automacoes do ambiente perdido saem da tela");
        assertNull(s.context(), "nenhuma acao tem contexto");
        assertEquals(List.of(a2), s.workspaces().value());
        assertEquals(a.id(), s.selected().id(), "continua na organizacao");
        assertFalse(d.calls.stream().anyMatch(c -> c.startsWith("automations")), "nao consulta automacoes de ambiente que nao e mais listado");

        // 2. o ambiente e listado, mas a Platform responde que as automacoes dele estao fora do alcance (404 confirmado)
        d.workspaces.put(a.id(), List.of(a1, a2));
        nav.loadWorkspaces();
        background.runAll();
        assertTrue(nav.selectWorkspace(a1));
        background.runAll();
        d.automations.remove(a1.id());
        assertTrue(nav.refresh());
        background.runAll();
        assertEquals(Outcome.WORKSPACE_LOST, refresh().outcome());
        assertNull(nav.state().workspace());
    }

    @Test
    void organizacaoQueSaiuDoAlcanceVoltaAoSeletorSemNadaDela() {
        // 1. saiu da lista de organizacoes
        enter(a, a1);
        d.organizations = List.of(b);
        assertTrue(nav.refresh());
        background.runAll();
        Navigation.State s = nav.state();
        assertEquals(new Refresh(Phase.DONE, null, Outcome.ORGANIZATION_LOST, "Acme"), s.refresh());
        assertNull(s.selected(), "volta ao seletor; a outra organizacao NAO e escolhida no lugar");
        assertEquals(List.of(b), s.organizations().value());
        assertEquals(Status.IDLE, s.workspaces().status());
        assertEquals(Status.IDLE, s.automations().status());
        assertNull(s.workspace());
        assertTrue(d.calls.isEmpty(), "nada da organizacao perdida e consultado");
        assertFalse(nav.select(a), "e ela nao pode mais ser escolhida");

        // 2. ainda listada, mas a Platform responde 404 para os ambientes dela (revogacao confirmada)
        d.organizations = List.of(a, b);
        nav.loadOrganizations();
        background.runAll();
        enter(a, a1);
        d.workspaces.remove(a.id());
        assertTrue(nav.refresh());
        background.runAll();
        assertEquals(Outcome.ORGANIZATION_LOST, refresh().outcome());
        assertNull(nav.state().selected());
    }

    @Test
    void indisponibilidadeNaoEExclusaoNemListaVazia() {
        enter(a, a1);
        Navigation.State antes = nav.state();
        Object[][] casos = {{"organizacoes", Failure.NETWORK}, {"organizacoes", Failure.RATE_LIMITED}, {"ambientes", Failure.SERVER}, {"ambientes", Failure.TIMEOUT},
                {"ambientes", Failure.DENIED}, {"automacoes", Failure.RATE_LIMITED}, {"automacoes", Failure.NETWORK}, {"automacoes", Failure.SERVER},
                {"automacoes", Failure.INVALID}};
        for (Object[] c : casos) {
            Failure f = (Failure) c[1];
            d.organizationsFailure = "organizacoes".equals(c[0]) ? f : null;
            d.workspacesFailure = "ambientes".equals(c[0]) ? f : null;
            d.automationsFailure = "automacoes".equals(c[0]) ? f : null;
            // a "Platform" ate teria novidades, mas a atualizacao falhou no meio: NADA entra (nem a parte que chegou)
            d.organizations = List.of(a, b, nova);
            d.workspaces.put(a.id(), List.of(a1, a2, a3));
            assertTrue(nav.refresh());
            background.runAll();
            Navigation.State s = nav.state();
            String caso = c[0] + " " + f;
            assertEquals(new Refresh(Phase.FAILED, f, null, null), s.refresh(), caso);
            assertEquals(antes.organizations(), s.organizations(), caso + ": atualizacao parcial nao entra");
            assertEquals(antes.workspaces(), s.workspaces(), caso);
            assertEquals(antes.automations(), s.automations(), caso + ": a lista de antes continua, nao vira vazia");
            assertEquals(antes.selected(), s.selected(), caso + ": falha nao tira a organizacao");
            assertEquals(antes.workspace(), s.workspace(), caso + ": falha nao tira o ambiente");
        }
        // passou a indisponibilidade: a nova tentativa traz tudo
        d.organizationsFailure = null;
        d.workspacesFailure = null;
        d.automationsFailure = null;
        assertTrue(nav.refresh());
        background.runAll();
        assertEquals(Outcome.UPDATED, refresh().outcome());
        assertEquals(List.of(a1, a2, a3), nav.state().workspaces().value());
        assertEquals(List.of(a, b, nova), nav.state().organizations().value());
    }

    // ------------------------------------------------------------------ resposta atrasada e troca de contexto

    @Test
    void trocarDeContextoDescartaAAtualizacaoEmCurso() {
        // trocar de ambiente
        enter(a, a1);
        d.automations.put(a1.id(), FakeDirectory.listing(true, true, conc, FakeDirectory.automation(7, "Tardia", 1, null)));
        assertTrue(nav.refresh());
        Runnable atrasada = background.take(0);
        assertTrue(nav.selectWorkspace(a2));
        background.runAll();
        assertEquals(Phase.NONE, refresh().phase(), "a troca de contexto encerra a atualizacao");
        atrasada.run();
        assertEquals(a2, nav.state().workspace());
        assertTrue(nav.state().automations().value().items().isEmpty(), "a resposta atrasada do outro ambiente nao aparece");
        assertEquals(Phase.NONE, refresh().phase(), "nem vira 'Dados atualizados' do contexto novo");

        // voltar ao seletor e entrar em outra organizacao
        assertTrue(nav.selectWorkspace(a1));
        background.runAll();
        d.organizations = List.of(a, b, nova);
        assertTrue(nav.refresh());
        Runnable atrasada2 = background.take(0);
        nav.back();
        assertTrue(nav.select(b));
        background.runAll();
        atrasada2.run();
        assertEquals(b.id(), nav.state().selected().id());
        assertEquals(List.of(b1), nav.state().workspaces().value(), "os ambientes sao os da organizacao atual");
        assertEquals(List.of(a, b), nav.state().organizations().value(), "a lista da atualizacao descartada nao entrou");
        assertNull(nav.state().workspace());

        // recarregar as organizacoes ("Tentar novamente") tambem descarta
        nav.back();
        assertTrue(nav.refresh());
        Runnable atrasada3 = background.take(0);
        nav.loadOrganizations();
        atrasada3.run();
        assertEquals(Status.LOADING, nav.state().organizations().status());
        background.runAll();
        assertEquals(Phase.NONE, refresh().phase());
    }

    @Test
    void depoisDeSairNenhumaRespostaRepovoaATela() {
        enter(a, a1);
        d.organizations = List.of(a, b, nova);
        assertTrue(nav.refresh());
        Runnable atrasada = background.take(0);
        Navigation.State antes = nav.state();
        nav.close();
        seen.clear();
        atrasada.run();
        assertSame(antes, nav.state(), "fechada (saida ou outra conta): a resposta atrasada nao muda nada");
        assertTrue(seen.isEmpty(), "e nenhum ouvinte e chamado");
        assertFalse(nav.refresh(), "fechada, nao consulta mais");
    }

    @Test
    void consultaIsoladaEmCursoNaoCaiPorCimaDaAtualizacao() {
        enter(a, a1);
        // "Tentar novamente" das automacoes em curso quando a pessoa clica em Atualizar
        nav.loadAutomations();
        Runnable isolada = background.take(0);
        Automation v4 = FakeDirectory.automation(1, "Conciliação", 4, "1.3.0");
        assertTrue(nav.refresh());
        d.automations.put(a1.id(), FakeDirectory.listing(true, true, v4));
        background.runAll();
        assertEquals(List.of(v4), nav.state().automations().value().items());
        d.automations.put(a1.id(), FakeDirectory.listing(true, true, conc));
        isolada.run();
        assertEquals(List.of(v4), nav.state().automations().value().items(), "a resposta antiga nao substitui a da atualizacao");
        assertEquals(Outcome.UPDATED, refresh().outcome());
    }

    @Test
    void atualizarSoLeENaoEscreveNada() {
        enter(a, a1);
        assertTrue(nav.refresh());
        background.runAll();
        assertTrue(d.renames.isEmpty());
        assertFalse(d.calls.stream().anyMatch(c -> c.startsWith("rename")), "nenhuma escrita na Platform");
    }
}
