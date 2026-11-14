package com.nekkora.client.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** ZIPs de teste para quem esta fora deste pacote (a interface): codigo de exemplo e pacotes prontos gerados dele. */
public final class PackFixtures {

    private PackFixtures() {
    }

    /** ZIP com o CODIGO de um projeto de exemplo ({@code minimo}, {@code declarado}, {@code dependencias}, {@code modular}). */
    public static Path codeZip(Path dir, String fixture) throws Exception {
        Files.createDirectories(dir);
        return ZipForge.ofFolder(ZipForge.fixture(fixture), "", dir.resolve(fixture + "-codigo-" + System.nanoTime() + ".zip"));
    }

    /** ZIP de um PACOTE pronto (com manifesto), gerado do projeto de exemplo pelo empacotador real. */
    public static Path packageZip(Path dir, String fixture, String name, String version, String entry) throws Exception {
        Path code = codeZip(dir, fixture);
        ProjectImporter importer = new ProjectImporter(dir.resolve("trabalho-" + System.nanoTime()), ZipLimits.DEFAULT);
        ImportedProject p = importer.open(code, SafeZip.Cancel.NEVER);
        try {
            Path out = dir.resolve("saida-" + System.nanoTime());
            Files.createDirectories(out);
            return Packager.build(p, new PackagePlan(name, version, entry, ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false), out, false,
                    SafeZip.Cancel.NEVER).zip();
        } finally {
            importer.close(p);
        }
    }

    /** Um arquivo com extensao .zip que nao e um ZIP. */
    public static Path notAZip(Path dir) throws Exception {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve("nao-e-zip-" + System.nanoTime() + ".zip"), "isto nao e um arquivo zip");
    }
}
