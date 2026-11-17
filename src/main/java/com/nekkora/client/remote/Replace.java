package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A troca de um arquivo de estado desta camada pelo arquivo novo ja gravado ao lado dele.
 * <p>
 * No Windows a troca e NEGADA enquanto o destino esta aberto por outra leitura - de outro processo (antivirus, indexador,
 * backup) ou de outra thread deste aplicativo (a tela le o diario da entrega ao se redesenhar). A negacao e passageira: a
 * troca e tentada de novo algumas vezes, com espera crescente ({@link #RETRY_MILLIS}, ~0,6 s no total). O arquivo novo ja
 * esta completo, entao repetir a troca nao muda o que e gravado. Esgotadas as tentativas, a ultima falha e lancada: nada e
 * dado por gravado, e quem chamou decide o que fazer com o que sabe.
 * <p>
 * Mesma regra de {@code pack.AtomicWriter} (F-AGT-01), repetida aqui porque esta camada nao conhece as outras.
 */
final class Replace {

    /** Esperas entre as tentativas da troca, em milissegundos (7 tentativas). */
    static final List<Long> RETRY_MILLIS = List.of(10L, 20L, 40L, 80L, 160L, 320L);

    private Replace() {
    }

    static void into(Path fresh, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(fresh, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(fresh, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException e) {
                // so a negacao por arquivo em uso e passageira; o resto (sem espaco, caminho invalido...) falha ja
                boolean busy = e instanceof AccessDeniedException || e.getClass() == FileSystemException.class;
                if (!busy || attempt >= RETRY_MILLIS.size()) {
                    throw e;
                }
                try {
                    Thread.sleep(RETRY_MILLIS.get(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
