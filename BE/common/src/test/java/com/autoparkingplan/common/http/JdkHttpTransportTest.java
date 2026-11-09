package com.autoparkingplan.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** (자바판 추가) 원천이 보내는 응답의 크기·시간 상한 — 거대한 본문·느린 본문이 메모리와 요청 스레드를 붙잡지 못한다. */
class JdkHttpTransportTest {
    static HttpServer server;
    static ExecutorService pool;
    static int port;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> reply(ex, "hello".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/big", ex -> reply(ex, new byte[300_000])); // Content-Length: 300000
        server.createContext("/chunked", ex -> { // Content-Length 없이 320KB
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 40; i++) {
                    os.write(chunk);
                }
            } catch (IOException ignored) {
                // 클라이언트가 먼저 끊는다
            }
        });
        server.createContext("/slow", ex -> { // 헤더는 바로, 본문은 천천히
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write("a".getBytes(StandardCharsets.UTF_8));
                os.flush();
                Thread.sleep(3000);
                os.write("b".getBytes(StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException ignored) {
                // 클라이언트가 먼저 끊거나 서버를 내린다
            }
        });
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    static void reply(HttpExchange ex, byte[] body) throws IOException {
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        } catch (IOException ignored) {
            // 클라이언트가 먼저 끊는다
        }
    }

    static HttpRequest get(String path) {
        return HttpRequest.get(URI.create("http://127.0.0.1:" + port + path));
    }

    final JdkHttpTransport transport = new JdkHttpTransport(Duration.ofSeconds(2), 100_000);

    @Test
    void readsNormalBody() throws IOException {
        HttpResponse r = transport.send(get("/ok"), Duration.ofSeconds(2));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text()).isEqualTo("hello");
    }

    @Test
    void rejectsBodyLargerThanLimit() {
        assertThatThrownBy(() -> transport.send(get("/big"), Duration.ofSeconds(2))).isInstanceOf(IOException.class).hasMessageContaining("너무 큽니다");
        assertThatThrownBy(() -> transport.send(get("/chunked"), Duration.ofSeconds(2))).isInstanceOf(IOException.class)
                .hasMessageContaining("너무 큽니다");
    }

    @Test
    void slowBodyHitsTheOverallDeadline() {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> transport.send(get("/slow"), Duration.ofMillis(400))).isInstanceOf(HttpTimeoutException.class);
        assertThat((System.nanoTime() - t0) / 1e9).isLessThan(2.5);
    }
}
