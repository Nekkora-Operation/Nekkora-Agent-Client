package com.nekkora.client.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nekkora.client.nav.Directory.Failure;
import com.nekkora.client.nav.Loadable.Status;

/** Regras da navegacao, sem interface e com a Platform SIMULADA ({@link FakeDirectory}). */
class NavigationTest {

    private FakeDirectory directory;
    private FakeDirectory.Manual background;
    private Navigation nav;
    private List<Navigation.State> seen;

    @BeforeEach
    void setUp() {
        directory = new FakeDirectory();
        background = new FakeDirectory.Manual();
        nav = new Navigation(directory, background, Runnable::run);
        seen = new ArrayList<>();
        nav.addListener(seen::add);
    }

    @Test
    void primeiraCargaTrazAPessoaEAsOrganizacoes() {
        directory.organizations = FakeDirectory.orgs(3);
        assertEquals(Status.IDLE, nav.state().organizations().status());
        nav.start();
        assertEquals(Status.LOADING, nav.state().profile().status());
        assertEquals(Status.LOADING, nav.state().organizations().status(), "carregando nao e lista vazia");
        background.runAll();
        assertEquals("Ana Souza", nav.state().profile().value().displayName());
        assertEquals(FakeDirectory.orgs(3), nav.state().organizations().value(), "na ordem que a Platform devolveu");
        assertNull(nav.state().selected(), "nada e escolhido sozinho");
    }

    @Test
    void umaUnicaOrganizacaoNaoEEscolhidaSozinha() {
        directory.organizations = FakeDirectory.orgs(1);
        nav.start();
        background.runAll();
        assertNull(nav.state().selected());
        assertTrue(nav.select(FakeDirectory.org(1, "Organização 01")));
        assertEquals(FakeDirectory.id(1), nav.state().selected().id());
    }

    @Test
    void falhaNaoEListaVaziaECadaFalhaTemASuaClassificacao() {
        for (Failure f : new Failure[] {Failure.NETWORK, Failure.TIMEOUT, Failure.TLS, Failure.SERVER, Failure.RATE_LIMITED, Failure.DENIED, Failure.INVALID}) {
            directory.organizationsFailure = f;
            nav.loadOrganizations();
            background.runAll();
            assertEquals(Status.FAILED, nav.state().organizations().status(), f.toString());
            assertEquals(f, nav.state().organizations().failure());
            assertNull(nav.state().organizations().value(), f + ": sem lista, nem vazia");
        }
        // vazio legitimo e outro estado
        directory.organizationsFailure = null;
        directory.organizations = List.of();
        nav.loadOrganizations();
        background.runAll();
        assertEquals(Loadable.ready(List.of()), nav.state().organizations());
    }

    @Test
    void erroInesperadoDaConsultaNaoDeixaATelaCarregandoParaSempre() {
        directory.crash = new IllegalStateException("x");
        nav.loadOrganizations();
        background.runAll();
        assertEquals(Loadable.failed(Failure.INVALID), nav.state().organizations());
    }

    @Test
    void tentarNovamenteRepetidoNaoAcumulaConsultas() {
        for (int i = 0; i < 10; i++) {
            nav.loadOrganizations();
        }
        assertEquals(1, background.size());
        background.runAll();
        assertEquals(1, directory.organizationCalls.get());
        directory.organizationsFailure = Failure.SERVER;
        nav.loadOrganizations();
        background.runAll();
        directory.organizationsFailure = null;
        directory.organizations = FakeDirectory.orgs(2);
        nav.loadOrganizations();
        background.runAll();
        assertEquals(FakeDirectory.orgs(2), nav.state().organizations().value(), "depois da falha, tentar de novo traz a lista");
    }

    @Test
    void soEntraEmOrganizacaoListadaEAChaveEOIdentificador() {
        Organization a = new Organization(FakeDirectory.id(1), "Acme", "acme", false);
        Organization homonima = new Organization(FakeDirectory.id(2), "Acme", "acme-2", false);
        directory.organizations = List.of(a, homonima);
        assertFalse(nav.select(a), "antes de carregar, nada a escolher");
        nav.start();
        assertFalse(nav.select(a), "carregando: nada a escolher");
        background.runAll();
        assertFalse(nav.select(new Organization(FakeDirectory.id(9), "Acme", "acme", false)), "mesmo nome, outro identificador: nao esta na lista");
        assertFalse(nav.select(null));
        assertNull(nav.state().selected());
        assertTrue(nav.select(homonima));
        assertEquals(FakeDirectory.id(2), nav.state().selected().id(), "a homonima escolhida e a do identificador, nao a do nome");
        // quem escolhe com um nome adulterado recebe os dados que a Platform listou
        assertTrue(nav.select(new Organization(FakeDirectory.id(1), "Outro nome", "x", true)));
        assertEquals(a, nav.state().selected());
    }

    @Test
    void voltarLimpaAEscolhaEMantemALista() {
        directory.organizations = FakeDirectory.orgs(2);
        nav.start();
        background.runAll();
        nav.select(directory.organizations.get(1));
        int calls = directory.organizationCalls.get();
        nav.back();
        assertNull(nav.state().selected());
        assertEquals(FakeDirectory.orgs(2), nav.state().organizations().value());
        assertEquals(calls, directory.organizationCalls.get(), "voltar nao consulta de novo");
        nav.back();
        assertNull(nav.state().selected());
    }

    @Test
    void recarregarInvalidaAEscolha() {
        directory.organizations = FakeDirectory.orgs(2);
        nav.start();
        background.runAll();
        nav.select(directory.organizations.get(0));
        // a pessoa perdeu o acesso a primeira: a lista nova nao a traz, e a escolha antiga nao sobrevive
        directory.organizations = List.of(FakeDirectory.org(2, "Organização 02"));
        nav.loadOrganizations();
        assertNull(nav.state().selected(), "a escolha cai ao recarregar");
        background.runAll();
        assertFalse(nav.select(FakeDirectory.org(1, "Organização 01")), "a que saiu da lista nao pode mais ser escolhida");
    }

    @Test
    void respostaQueChegaDepoisDeFecharNaoMudaNadaNemAvisaNinguem() {
        directory.organizations = FakeDirectory.orgs(2);
        nav.start();
        int avisos = seen.size();
        nav.close();
        background.runAll();
        assertEquals(avisos, seen.size(), "nenhum aviso depois de fechar");
        assertEquals(Status.LOADING, nav.state().organizations().status(), "o estado nao recebe a resposta atrasada");
        nav.loadOrganizations();
        nav.loadProfile();
        assertEquals(0, background.size(), "fechado: nenhuma consulta nova");
        assertFalse(nav.select(FakeDirectory.org(1, "Organização 01")));
    }

    @Test
    void falhaDoPerfilNaoImpedeAsOrganizacoes() {
        directory.profileFailure = Failure.SERVER;
        directory.organizations = FakeDirectory.orgs(1);
        nav.start();
        background.runAll();
        assertEquals(Loadable.failed(Failure.SERVER), nav.state().profile());
        assertEquals(FakeDirectory.orgs(1), nav.state().organizations().value());
        directory.profileFailure = null;
        nav.loadProfile();
        background.runAll();
        assertTrue(nav.state().profile().isReady());
    }

    @Test
    void iniciaisVemSoDoNome() {
        assertEquals("AS", new Profile("x@y.test", "Ana Souza").initials());
        assertEquals("AS", new Profile("x@y.test", "  ana   maria de souza ").initials());
        assertEquals("A", new Profile("x@y.test", "Ana").initials(), "um termo so: uma letra");
        assertEquals("ÉÁ", new Profile("x@y.test", "élcio ávila").initials());
        assertEquals("JS", new Profile("x@y.test", "(João) [Silva]").initials(), "pontuacao nao vira inicial");
        assertEquals("", new Profile("zeca@y.test", null).initials(), "sem nome: nada, e nunca a inicial do e-mail");
        assertEquals("", new Profile("zeca@y.test", "   ").initials());
        assertEquals("", new Profile("zeca@y.test", "--").initials());
        assertFalse(new Profile("zeca@y.test", " ").hasName());
    }

    @Test
    void identificadorComplementarDoCartao() {
        assertEquals("acme", new Organization(FakeDirectory.id(1), "Acme", "acme", false).label());
        assertEquals("00000000", new Organization(FakeDirectory.id(1), "Acme", null, false).label(), "sem handle: o comeco do identificador");
        assertEquals("00000000", new Organization(FakeDirectory.id(1), "Acme", " ", false).label());
    }
}
