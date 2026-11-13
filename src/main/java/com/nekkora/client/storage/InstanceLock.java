package com.nekkora.client.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Instancia unica do Client: trava exclusiva num arquivo da pasta de dados DO CLIENT ({@code client.lock}). Independente
 * da trava do Nekkora Agent: os dois aplicativos podem estar abertos ao mesmo tempo.
 */
public final class InstanceLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** A trava, ou vazio se outra instancia do Client ja a segura. */
    public static Optional<InstanceLock> acquire(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new InstanceLock(channel, lock));
        } catch (OverlappingFileLockException e) {
            channel.close();
            return Optional.empty();
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException e) {
            // o sistema libera a trava ao fechar o canal ou encerrar o processo
        }
        try {
            channel.close();
        } catch (IOException e) {
            // idem
        }
    }
}
