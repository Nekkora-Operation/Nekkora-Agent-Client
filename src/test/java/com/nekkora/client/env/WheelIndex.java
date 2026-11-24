package com.nekkora.client.env;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Um indice "simple" (PEP 503) LOCAL, so para os testes (fica em src/test): HTTP em 127.0.0.1, com wheels gerados aqui. Nada
 * vai a internet. Cada arquivo e servido com o {@code #sha256=} na pagina do projeto, como o PyPI faz.
 *
 * <ul>
 * <li>{@code nekkora-fixture-a} 1.2.3 e 2.0.0 (sem dependencias);</li>
 * <li>{@code nekkora-fixture-b} 2.0.0, que depende de {@code nekkora-fixture-a==1.2.3} (dependencia indireta no lock);</li>
 * <li>{@code nekkora-fixture-c} 1.0.0 so como sdist (sem wheel: NO_COMPATIBLE_WHEEL);</li>
 * <li>{@code nekkora-fixture-e} 1.0.0, que depende de {@code nekkora-fixture-a==2.0.0} (conflito com a 1.2.3);</li>
 * <li>{@code playwright} 1.0.0 FALSO: responde onde estaria cada navegador; so o "chromium" existe dentro do pacote.</li>
 * </ul>
 */
public final class WheelIndex implements AutoCloseable {

    /** Um arquivo do indice: projeto (normalizado), nome do arquivo, bytes e SHA-256. */
    record Dist(String project, String file, byte[] bytes, String sha256) {
    }

    final HttpServer server;
    final Map<String, List<Dist>> projects = new ConcurrentHashMap<>();
    /** Arquivos baixados (nome), na ordem. */
    final List<String> downloads = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Quando nao nulo, o download de arquivos espera aqui (para o teste de cancelamento). */
    volatile CountDownLatch slow;
    final AtomicInteger pages = new AtomicInteger();

    public WheelIndex() throws IOException {
        add(wheel("nekkora-fixture-a", "1.2.3", Map.of("nekkora_fixture_a/__init__.py", "VALUE = 'a-1.2.3'\n"), List.of()));
        add(wheel("nekkora-fixture-a", "2.0.0", Map.of("nekkora_fixture_a/__init__.py", "VALUE = 'a-2.0.0'\n"), List.of()));
        add(wheel("nekkora-fixture-b", "2.0.0", Map.of("nekkora_fixture_b/__init__.py",
                "import nekkora_fixture_a\nVALUE = 'b-2.0.0+' + nekkora_fixture_a.VALUE\n"), List.of("nekkora-fixture-a==1.2.3")));
        add(wheel("nekkora-fixture-e", "1.0.0", Map.of("nekkora_fixture_e/__init__.py", "VALUE = 'e'\n"), List.of("nekkora-fixture-a==2.0.0")));
        byte[] sdist = "nao e um wheel".getBytes(StandardCharsets.US_ASCII);
        add(new Dist("nekkora-fixture-c", "nekkora_fixture_c-1.0.0.tar.gz", sdist, sha(sdist)));
        Map<String, String> pw = new LinkedHashMap<>();
        pw.put("playwright/__init__.py", "");
        pw.put("playwright/sync_api.py", String.join("\n",
                "import os",
                "class _Browser:",
                "    def __init__(self, name):",
                "        self.executable_path = os.path.join(os.path.dirname(__file__), name + '.exe')",
                "class _Playwright:",
                "    chromium = _Browser('chromium')",
                "    firefox = _Browser('firefox')",
                "    webkit = _Browser('webkit')",
                "class sync_playwright:",
                "    def __enter__(self):",
                "        return _Playwright()",
                "    def __exit__(self, *a):",
                "        return False",
                ""));
        pw.put("playwright/chromium.exe", "nao e um navegador");
        add(wheel("playwright", "1.0.0", pw, List.of()));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 16);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "indice-de-teste");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** O endereco "simple" deste indice. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/simple";
    }

    Dist dist(String file) {
        return projects.values().stream().flatMap(List::stream).filter(d -> d.file().equals(file)).findFirst().orElseThrow();
    }

    private void add(Dist d) {
        projects.computeIfAbsent(d.project(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(d);
    }

    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Um wheel {@code py3-none-any} de verdade: os arquivos, METADATA, WHEEL e RECORD (com os hashes). */
    static Dist wheel(String name, String version, Map<String, String> files, List<String> requires) throws IOException {
        String dist = name.replace('-', '_');
        String info = dist + "-" + version + ".dist-info/";
        Map<String, byte[]> all = new LinkedHashMap<>();
        files.forEach((k, v) -> all.put(k, v.getBytes(StandardCharsets.UTF_8)));
        StringBuilder meta = new StringBuilder("Metadata-Version: 2.1\nName: " + name + "\nVersion: " + version + "\n");
        for (String r : requires) {
            meta.append("Requires-Dist: ").append(r).append('\n');
        }
        all.put(info + "METADATA", meta.toString().getBytes(StandardCharsets.UTF_8));
        all.put(info + "WHEEL", "Wheel-Version: 1.0\nGenerator: nekkora-teste\nRoot-Is-Purelib: true\nTag: py3-none-any\n".getBytes(StandardCharsets.UTF_8));
        StringBuilder record = new StringBuilder();
        for (Map.Entry<String, byte[]> e : all.entrySet()) {
            String h;
            try {
                h = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(e.getValue()));
            } catch (java.security.NoSuchAlgorithmException x) {
                throw new IllegalStateException(x);
            }
            record.append(e.getKey()).append(",sha256=").append(h).append(',').append(e.getValue().length).append('\n');
        }
        record.append(info).append("RECORD,,\n");
        all.put(info + "RECORD", record.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : all.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        byte[] b = out.toByteArray();
        return new Dist(normalize(name), dist + "-" + version + "-py3-none-any.whl", b, sha(b));
    }

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        try {
            if (path.startsWith("/simple/")) {
                pages.incrementAndGet();
                String project = normalize(path.substring("/simple/".length()).replace("/", ""));
                List<Dist> found = projects.get(project);
                if (found == null) {
                    respond(x, 404, "text/plain", "nao ha".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                StringBuilder html = new StringBuilder("<!DOCTYPE html><html><body>\n");
                for (Dist d : found) {
                    html.append("<a href=\"/files/").append(d.file()).append("#sha256=").append(d.sha256()).append("\">").append(d.file()).append("</a><br/>\n");
                }
                respond(x, 200, "text/html", html.append("</body></html>\n").toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/files/")) {
                String file = path.substring("/files/".length());
                CountDownLatch s = slow;
                if (s != null) {
                    s.await(120, TimeUnit.SECONDS);
                }
                for (List<Dist> list : projects.values()) {
                    for (Dist d : list) {
                        if (d.file().equals(file)) {
                            downloads.add(file);
                            // como o PyPI: o arquivo pode ficar no cache do pip
                            x.getResponseHeaders().set("Cache-Control", "max-age=365000000, immutable, public");
                            x.getResponseHeaders().set("ETag", "\"" + d.sha256() + "\"");
                            respond(x, 200, "application/octet-stream", d.bytes());
                            return;
                        }
                    }
                }
            }
            respond(x, 404, "text/plain", new byte[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            x.close();
        } catch (IOException e) {
            x.close();
        }
    }

    private static void respond(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = x.getResponseBody()) {
                out.write(body);
            }
        } else {
            x.close();
        }
    }

    /** Todos os arquivos servidos por projeto (para conferir o lock). */
    List<Dist> all() {
        List<Dist> out = new ArrayList<>();
        projects.values().forEach(out::addAll);
        return out;
    }

    @Override
    public void close() {
        CountDownLatch s = slow;
        if (s != null) {
            s.countDown();
        }
        server.stop(0);
    }
}
