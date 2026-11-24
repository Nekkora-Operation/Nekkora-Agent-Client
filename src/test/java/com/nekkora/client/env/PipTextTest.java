package com.nekkora.client.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** AMBIENTE-PYTHON-001 - a leitura do que o pip escreveu: a fase da falha e a remocao de segredos do registro. Sem processo. */
class PipTextTest {

    @Test
    void aFaseDaFalhaDaResolucaoVemDoQueOPipDisseComARedeAntesDoNaoAchou() {
        assertEquals(new PipText.Classified("NO_COMPATIBLE_WHEEL", "pandas==2.2.3"), PipText.resolution(null,
                "ERROR: Could not find a version that satisfies the requirement pandas==2.2.3 (from versions: none)\n"
                        + "ERROR: No matching distribution found for pandas==2.2.3\n"));
        assertEquals("NOT_FOUND", PipText.resolution(null, "Could not fetch URL https://pypi.org/simple/x/: 404 Client Error: Not Found for url - skipping\n"
                + "ERROR: No matching distribution found for x==1\n").phase());
        // sem rede o pip TAMBEM diz "No matching distribution": a rede decide primeiro
        assertEquals("INDEX_UNREACHABLE", PipText.resolution(null, "WARNING: Retrying (Retry(total=4)) after connection broken by "
                + "'NewConnectionError(...: Failed to establish a new connection: [WinError 10061]')': /simple/x/\n"
                + "ERROR: No matching distribution found for x==1\n").phase());
        assertEquals("RESOLUTION_CONFLICT", PipText.resolution(null, "ERROR: Cannot install a==1 and b==2 because these package versions have conflicting "
                + "dependencies.\nERROR: ResolutionImpossible: for help visit ...\n").phase());
        assertEquals("OTHER", PipText.resolution(null, "ERROR: algo inesperado\n").phase());
    }

    @Test
    void aFaseDaFalhaDaInstalacao() {
        assertEquals(new PipText.Classified("HASH_MISMATCH", "requests==2.32.3"), PipText.install(null,
                "ERROR: THESE PACKAGES DO NOT MATCH THE HASHES FROM THE REQUIREMENTS FILE.\n    requests==2.32.3 from https://files/requests.whl:\n"
                        + "        Expected sha256 000\n             Got        abc\n"));
        assertEquals("NO_SPACE", PipText.install(null, "ERROR: Could not install packages due to an OSError: [Errno 28] No space left on device\n").phase());
        assertEquals("DOWNLOAD", PipText.install(null, "WARNING: Retrying ... Max retries exceeded with url\n").phase());
        assertEquals("NO_COMPATIBLE_WHEEL", PipText.install(null, "ERROR: No matching distribution found for x==1\n").phase());
        assertEquals("INSTALL", PipText.install(null, "ERROR: falhou\n").phase());
    }

    @Test
    void oRegistroSaiSemSegredosEComNoMaximo64KiB() {
        String raw = "Looking in indexes: https://usuario:senha-secreta@indice.exemplo/simple\n"
                + "Authorization: Bearer abcdefghijklmnop\n"
                + "proxy-authorization: Basic dXN1YXJpbzpzZW5oYQ==\n"
                + "token=abc123segredo api_key: zzzsegredo\n"
                + "https://indice.exemplo/arquivo.whl?token=xyzsegredo&x=1\n"
                + "credencial nka1.org.cred.segredo\n";
        String clean = PipText.log(raw);
        for (String secret : new String[] {"senha-secreta", "abcdefghijklmnop", "dXN1YXJpbzpzZW5oYQ==", "abc123segredo", "zzzsegredo", "xyzsegredo",
                "nka1.org.cred.segredo", "usuario:"}) {
            assertFalse(clean.contains(secret), secret + " em: " + clean);
        }
        assertTrue(clean.contains("https://[removido]@indice.exemplo/simple"), clean);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            big.append("linha ").append(i).append(" é longa o suficiente\n");
        }
        String cut = PipText.log(big.toString());
        assertTrue(cut.getBytes(StandardCharsets.UTF_8).length <= PipText.MAX_LOG);
        assertTrue(cut.startsWith("linha 0 ") && cut.contains("registro cortado") && cut.strip().endsWith("linha 19999 é longa o suficiente"));
        String summary = PipText.summary("Resumo.", "x".repeat(2000) + "\nERROR: " + "y".repeat(1000));
        assertTrue(summary.length() <= 500, String.valueOf(summary.length()));
    }
}
