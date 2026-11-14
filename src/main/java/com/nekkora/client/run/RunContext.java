package com.nekkora.client.run;

import com.nekkora.client.pack.ExecutionPackage;
import com.nekkora.client.pack.LocalPackages;
import com.nekkora.client.pack.PackageContext;
import java.util.Objects;
import java.util.UUID;

/**
 * A quem uma execucao pertence, capturado NO INICIO e imutavel: organizacao, ambiente, impressao digital do pacote, nome
 * local do pacote naquele momento e, se houver, a automacao da Platform (e a versao dela) a que a pessoa associou o
 * pacote. O registro da execucao carrega este valor do primeiro ao ultimo estado; nada o troca depois (trocar de
 * organizacao, renomear ou remover o pacote, desfazer a associacao: o registro continua como foi criado).
 *
 * @param organizationId organizacao em que a pessoa estava
 * @param workspaceId ambiente em que a pessoa estava
 * @param fingerprint impressao digital do pacote executado ({@code sha256:...})
 * @param localName nome do pacote mostrado a pessoa no inicio (o local, ou o declarado)
 * @param automationId automacao da Platform associada, ou {@code null}
 * @param automationVersionId versao da automacao, ou {@code null} (so com {@code automationId})
 */
public record RunContext(UUID organizationId, UUID workspaceId, String fingerprint, String localName, UUID automationId, UUID automationVersionId) {

    public RunContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (!ExecutionPackage.validFingerprint(fingerprint)) throw new IllegalArgumentException("impressao digital fora do formato");
        if (localName == null || localName.isBlank() || localName.length() > LocalPackages.LOCAL_NAME_MAX || localName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("nome local fora do formato");
        }
        if (automationVersionId != null && automationId == null) throw new IllegalArgumentException("versao sem automacao");
    }

    /** O contexto de uma execucao do item guardado, como ele esta AGORA (nome de exibicao e associacao atuais). */
    public static RunContext of(LocalPackages.Item item, UUID automationVersionId) {
        return new RunContext(item.context().organizationId(), item.context().workspaceId(), item.fingerprint(), item.displayName(), item.linkedAutomationId(),
                item.linkedAutomationId() == null ? null : automationVersionId);
    }

    /** Organizacao e ambiente. */
    public PackageContext where() {
        return new PackageContext(organizationId, workspaceId);
    }

    /** A execucao pertence a esta organizacao e a este ambiente? */
    public boolean in(PackageContext context) {
        return context != null && organizationId.equals(context.organizationId()) && workspaceId.equals(context.workspaceId());
    }
}
