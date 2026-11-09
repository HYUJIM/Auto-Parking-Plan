package com.autoparkingplan.ai.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 정해 둔 응답을 차례로 돌려주고 받은 요청을 기록하는 작은 HTTP 서버 (실제 API 를 부르지 않는다). */
final class LocalServer implements AutoCloseable {
    record Canned(int status, Map<String, String> headers, String body, String contentType) {
    }

    record Received(String method, String pathAndQuery, Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final Deque<Canned> queue = new ArrayDeque<>();
    private final List<Received> received = new ArrayList<>();

    LocalServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    synchronized void enqueue(Canned c) {
        queue.add(c);
    }

    synchronized List<Received> received() {
        return List.copyOf(received);
    }

    synchronized void reset() {
        queue.clear();
        received.clear();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        Canned c;
        synchronized (this) {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(), headers, body));
            c = queue.poll();
        }
        if (c == null) {
            c = new Canned(500, Map.of("x-should-retry", "false"), "{\"error\": \"no canned response\"}", "application/json");
        }
        byte[] data = c.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("content-type", c.contentType());
        c.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(c.status(), data.length == 0 ? -1 : data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
