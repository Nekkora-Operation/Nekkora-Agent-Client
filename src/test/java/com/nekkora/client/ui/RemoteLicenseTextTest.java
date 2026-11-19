package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.nekkora.client.remote.MachineChannel.Problem;

/**
 * PLATFORM-LICENCIAMENTO-001 - as frases do aplicativo para a recusa da Platform por licenca e para o limite de maquinas da
 * organizacao. Licenca e capacidade nunca sao ditas como a mesma coisa, e em todos os casos fica dito que nada rodou aqui.
 */
class RemoteLicenseTextTest {

    @Test
    void recusaPorLicencaDizOMotivoQueNadaRodouEQuemResolve() {
        String required = RemoteText.declined("LICENSE_REQUIRED");
        assertTrue(required.contains("a automação está sem licença"), required);
        assertTrue(required.contains("Nada foi executado aqui."), required);
        assertTrue(required.contains("Quem administra a organização vincula uma licença"), required);

        String expired = RemoteText.declined("LICENSE_EXPIRED");
        assertTrue(expired.contains("a licença da automação venceu"), expired);
        assertTrue(expired.contains("Nada foi executado aqui."), expired);
        assertTrue(expired.contains("suporte da Nekkora"), expired);
        assertNotEquals(required, expired);
    }

    @Test
    void outroMotivoOuMotivoQueOAplicativoNaoConheceNaoFalaEmLicenca() {
        for (String reason : new String[] {"AUTHORIZATION_REVOKED", "MACHINE_NOT_ENABLED", "MOTIVO_NOVO", ""}) {
            String text = RemoteText.declined(reason);
            assertFalse(text.toLowerCase().contains("licen"), reason + ": " + text);
            assertTrue(text.contains("Nada foi executado aqui."), text);
            assertTrue(text.contains("O motivo aparece na Platform"), text);
        }
    }

    @Test
    void limiteDeMaquinasEDitoComoCapacidadeENaoComoPostoOcupado() {
        String limit = RemoteText.problem(Problem.MACHINE_LIMIT);
        assertTrue(limit.contains("limite de máquinas cadastradas"), limit);
        assertTrue(limit.contains("limite de capacidade da organização"), limit);
        assertFalse(limit.toLowerCase().contains("licen"), "limite de capacidade nao e dito como licenca: " + limit);
        assertTrue(limit.contains("Nada foi alterado neste computador."), limit);
        assertNotEquals(RemoteText.problem(Problem.SEAT_TAKEN), limit);
        assertEquals("O posto de trabalho deste código já tem uma máquina. Peça um código novo a quem administra as máquinas.",
                RemoteText.problem(Problem.SEAT_TAKEN));
    }
}
