package com.nekkora.client.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nekkora.client.auth.Json;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c - os vetores COMUNS da leitura estatica de requirements.txt
 * ({@code requirements-vectors.json}, copia identica no Nekkora-Platform-API, onde o mesmo arquivo prende o
 * {@code RequirementsText}). A Platform passa a ler dependencias declaradas com as regras deste aplicativo: este teste e o
 * que impede as duas leituras de divergirem.
 */
class RequirementsVectorsTest {

    @Test
    @SuppressWarnings("unchecked")
    void asSituacoesDeCadaLinhaSaoAsDosVetoresComuns() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream("/requirements-vectors.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Object> vectors = (List<Object>) Json.object(json).get("vectors");
        assertTrue(vectors.size() >= 10);
        for (Object o : vectors) {
            Map<String, Object> v = (Map<String, Object>) o;
            String name = (String) v.get("name");
            RequirementsFile.Parsed p = RequirementsFile.parse((String) v.get("text"));
            List<String> got = new ArrayList<>();
            for (RequirementsFile.Line l : p.lines()) {
                got.add(l.number() + " " + l.kind() + " " + l.dependency());
            }
            List<String> want = new ArrayList<>();
            for (Object lo : (List<Object>) v.get("lines")) {
                Map<String, Object> l = (Map<String, Object>) lo;
                want.add(((Double) l.get("number")).intValue() + " " + l.get("kind") + " " + l.get("dependency"));
            }
            assertEquals(want, got, name);
            assertEquals(v.get("dependencies"), p.dependencies(), name);
        }
    }
}
