package com.nekkora.client.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-CLIENT-PREPARACAO-002 T03: o requisito de Python do pacote e OPCIONAL (manifesto 1.1) e tem uma unica leitura
 * ({@link PythonRequirement}). O que a pessoa digita e interpretado e mostrado antes de gerar; o manifesto 1.0 continua
 * como era, byte a byte.
 */
class PythonRequirementTest {

    @TempDir
    Path tmp;

    @Test
    void formasDoManifestoEOQueCadaUmaAceita() {
        PythonRequirement faixa = PythonRequirement.parse(">=3.10,<3.14").orElseThrow();
        assertTrue(faixa.accepts("3.10.0") && faixa.accepts("3.13.9"));
        assertFalse(faixa.accepts("3.9.18") || faixa.accepts("3.14.0"));
        assertFalse(faixa.exact());
        PythonRequirement familia = PythonRequirement.parse("==3.12.*").orElseThrow();
        assertTrue(familia.accepts("3.12.0") && familia.accepts("3.12.10"));
        assertFalse(familia.accepts("3.13.0") || familia.accepts("3.11.9"));
        assertTrue(PythonRequirement.parse("==3.12").orElseThrow().accepts("3.12.7"), "==3.12 sempre foi a familia 3.12");
        PythonRequirement exata = PythonRequirement.parse("==3.12.0").orElseThrow();
        assertTrue(exata.exact());
        assertTrue(exata.accepts("3.12.0"));
        assertFalse(exata.accepts("3.12.1") || exata.accepts("3.12.10") || exata.accepts("3.13.0"), "versao exata compara a versao inteira");
        PythonRequirement compativel = PythonRequirement.parse("~=3.11").orElseThrow();
        assertTrue(compativel.accepts("3.13.1"));
        assertFalse(compativel.accepts("4.0.0") || compativel.accepts("3.10.9"));
        // versao que a sondagem nao respondeu direito nunca atende
        assertFalse(faixa.accepts("3.12") || faixa.accepts(null) || faixa.accepts("tres"));
        for (String ruim : new String[] {"", "3.12", ">=3", ">3.10", "!=3.12", "==3.12.0,<3.13", ">=3.10, <3.14", "latest", ">=3.10;rm", "==3.12.0.1"}) {
            assertTrue(PythonRequirement.parse(ruim).isEmpty(), ruim);
        }
        assertTrue(PythonRequirement.parse(null).isEmpty());
    }

    @Test
    void oQueAPessoaDigitaEInterpretadoSemInventarPadrao() {
        assertEquals("==3.12.*", PythonRequirement.interpret("3.12").text(), "3.12 e a familia 3.12.x");
        assertEquals("==3.12.*", PythonRequirement.interpret(" 3.12.* ").text());
        assertEquals("==3.12.0", PythonRequirement.interpret("3.12.0").text(), "3.12.0 e a versao exata");
        assertEquals(">=3.10,<3.14", PythonRequirement.interpret(">= 3.10, < 3.14").text(), "especificador do manifesto: so os espacos saem");
        assertEquals("~=3.11", PythonRequirement.interpret("~=3.11").text());
        // em branco: sem restricao - valido, e nenhum requisito e posto no lugar
        for (String vazio : new String[] {"", "   ", null}) {
            PythonRequirement.Typed t = PythonRequirement.interpret(vazio);
            assertTrue(t.valid());
            assertNull(t.text());
        }
        for (String ruim : new String[] {"python 3.12", "3", "3.12.0.1", ">=3", "novo", "3,12", ">=0"}) {
            PythonRequirement.Typed t = PythonRequirement.interpret(ruim);
            assertFalse(t.valid(), ruim);
            assertNull(t.text(), "texto invalido nunca vira um requisito: " + ruim);
        }
    }

    @Test
    void interpretacaoEmPalavras() {
        assertEquals("qualquer Python 3.12 (3.12.x)", PythonRequirement.parse("==3.12.*").orElseThrow().meaning());
        assertEquals("exatamente o Python 3.12.0", PythonRequirement.parse("==3.12.0").orElseThrow().meaning());
        assertEquals("Python 3.10 ou mais novo, abaixo do 3.14", PythonRequirement.parse(">=3.10,<3.14").orElseThrow().meaning());
        assertEquals("Python 3.11 ou mais novo, dentro da série 3.x", PythonRequirement.parse("~=3.11").orElseThrow().meaning());
        assertEquals("Sem restrição de Python declarada", Workshop.pythonMeaning(null));
        Workshop.PythonReading vazio = Workshop.readPython("");
        assertTrue(vazio.valid() && !vazio.declared());
        assertFalse(Workshop.readPython("3.12.x").valid());
    }

    @Test
    void semRequisitoQualquerVersaoRespondidaAtendeERequisitoIlegivelNunca() {
        assertTrue(ExecutionPackage.pythonSatisfies(null, "3.9.1"), "sem restricao declarada pelo autor");
        assertFalse(ExecutionPackage.pythonSatisfies(null, null), "mas o interpretador precisa ter respondido a versao");
        assertFalse(ExecutionPackage.pythonSatisfies(null, "3.12"));
        assertFalse(ExecutionPackage.pythonSatisfies(">=0", "3.12.0"));
        assertTrue(ExecutionPackage.pythonSatisfies("==3.12.0", "3.12.0"));
        assertFalse(ExecutionPackage.pythonSatisfies("==3.12.0", "3.12.1"));
    }

    // ------------------------------------------------------------------ manifesto 1.0 e 1.1

    private static final String MAIN = ExecutionPackage.fileHash("print('ola')\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private Path pacote(String nome, String versao, String linhaPython) throws Exception {
        Path d = Files.createDirectories(tmp.resolve(nome));
        Files.writeString(d.resolve("main.py"), "print('ola')\n");
        Files.writeString(d.resolve("nekkora-external.json"), "{\"schema\": \"nekkora-agent/external-python-package\", \"version\": \"" + versao
                + "\", \"name\": \"p\", \"packageVersion\": \"1.0.0\", \"entry\": \"main.py\", " + linhaPython + "\"dependencies\": [], \"files\": {\"main.py\": \""
                + MAIN + "\"}}\n");
        return d;
    }

    @Test
    void manifestoUmPontoUmAceitaAusenciaEVersaoExataEOUmPontoZeroContinuaExigindo() throws Exception {
        // 1.1: sem a chave -> sem restricao declarada
        assertNull(ExecutionPackage.inspect(pacote("sem", "1.1.0", "")).pythonRequirement());
        assertEquals("==3.12.0", ExecutionPackage.inspect(pacote("exata", "1.1.0", "\"python\": \"==3.12.0\", ")).pythonRequirement());
        assertEquals(">=3.10,<3.14", ExecutionPackage.inspect(pacote("faixa11", "1.1.3", "\"python\": \">=3.10,<3.14\", ")).pythonRequirement());
        // 1.0: como sempre - requisito obrigatorio, nas formas da 1.0
        assertEquals(">=3.10", ExecutionPackage.inspect(pacote("faixa10", "1.0.0", "\"python\": \">=3.10\", ")).pythonRequirement());
        recusado(pacote("dez-sem", "1.0.0", ""), "1.0 sem requisito");
        recusado(pacote("dez-exata", "1.0.0", "\"python\": \"==3.12.0\", "), "versao exata nao existe na 1.0");
        // 1.1: a chave PRESENTE precisa ser um requisito; nulo, vazio, numero ou faixa arbitraria nunca viram "sem restricao"
        recusado(pacote("nulo", "1.1.0", "\"python\": null, "), "nulo");
        recusado(pacote("vazio", "1.1.0", "\"python\": \"\", "), "vazio");
        recusado(pacote("numero", "1.1.0", "\"python\": 3.12, "), "numero");
        recusado(pacote("qualquer", "1.1.0", "\"python\": \">=0\", "), ">=0 nao e um requisito");
        recusado(pacote("estrela", "1.1.0", "\"python\": \"*\", "), "*");
        // versoes de manifesto que este aplicativo nao conhece sao recusadas, nunca executadas ignorando campos
        recusado(pacote("futuro", "1.2.0", ""), "1.2");
        recusado(pacote("dois", "2.0.0", "\"python\": \">=3.10\", "), "2.0");
    }

    private static void recusado(Path dir, String caso) {
        assertEquals(ErrorCode.EXEC_PACKAGE_INVALID, assertThrows(ExecRefused.class, () -> ExecutionPackage.inspect(dir), caso).code(), caso);
    }

    @Test
    void oEmpacotadorSoUsaOManifestoUmPontoUmQuandoPrecisa() {
        java.util.Map<String, String> files = java.util.Map.of("main.py", MAIN);
        String com = new String(Packager.descriptor(new PackagePlan("p", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true, null, null, false), files, "g/1"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(com.contains("\"version\": \"1.0.0\"") && com.contains("\"python\": \">=3.10,<3.14\""), "com requisito da 1.0, nada muda no manifesto\n" + com);
        String sem = new String(Packager.descriptor(new PackagePlan("p", "1.0.0", "main.py", null, List.of(), true, null, null, false), files, "g/1"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(sem.contains("\"version\": \"1.1.0\""), sem);
        assertFalse(sem.contains("\"python\""), "sem requisito: a chave nao e gravada (nem faixa arbitraria, nem versao ficticia)\n" + sem);
        String exata = new String(Packager.descriptor(new PackagePlan("p", "1.0.0", "main.py", "==3.12.0", List.of(), true, null, null, false), files, "g/1"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(exata.contains("\"version\": \"1.1.0\"") && exata.contains("\"python\": \"==3.12.0\""), exata);
        // identidade de conteudo: com e sem requisito sao conteudos diferentes; a de um pacote com requisito nao mudou de formula
        assertFalse(Packager.contentIdentity("main.py", null, List.of(), files).equals(Packager.contentIdentity("main.py", ">=3.10", List.of(), files)));
    }
}
