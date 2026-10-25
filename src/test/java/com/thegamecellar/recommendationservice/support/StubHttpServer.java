package com.thegamecellar.recommendationservice.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;

// A programmable HTTP endpoint on a random port, standing in for game-service or library-service.
// Hand-rolled on the JDK server so the suite has no dependency a bare CI runner lacks, and so
// rec-service's own HTTP clients are the ones under test. Requests are served on a thread pool, since
// the dashboard and the worker call the stubs from several threads at once.
public final class StubHttpServer {

    public record RecordedRequest(String method, String path, String query,
                                  Map<String, List<String>> headers, String body) {
        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    public record StubResponse(int status, String body, Map<String, String> headers) {
        public static StubResponse json(int status, String body) {
            return new StubResponse(status, body, Map.of("Content-Type", "application/json"));
        }

        public static StubResponse empty(int status) {
            return new StubResponse(status, "", Map.of());
        }
    }

    private record Route(String method, Predicate<String> path, Function<RecordedRequest, StubResponse> handler) {}

    private final HttpServer server;
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();

    private StubHttpServer(HttpServer server) {
        this.server = server;
    }

    public static StubHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubHttpServer stub = new StubHttpServer(server);
            server.createContext("/", exchange -> stub.handle(exchange));
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "stub-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start stub server", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // Later registrations win, so a test can override what the base class set up.
    public void on(String method, String pathPrefix, Function<RecordedRequest, StubResponse> handler) {
        routes.add(0, new Route(method, path -> path.startsWith(pathPrefix), handler));
    }

    public void on(String method, String pathPrefix, StubResponse response) {
        on(method, pathPrefix, request -> response);
    }

    public void onExact(String method, String path, Function<RecordedRequest, StubResponse> handler) {
        routes.add(0, new Route(method, path::equals, handler));
    }

    public void onExact(String method, String path, StubResponse response) {
        onExact(method, path, request -> response);
    }

    public void reset() {
        routes.clear();
        recorded.clear();
    }

    public List<RecordedRequest> recorded(String method, String pathPrefix) {
        return recorded.stream()
                .filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix))
                .toList();
    }

    public List<RecordedRequest> recorded() {
        return new ArrayList<>(recorded);
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RecordedRequest request = new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders(),
                body);
        recorded.add(request);

        StubResponse response = routes.stream()
                .filter(route -> route.method().equals(request.method()) && route.path().test(request.path()))
                .findFirst()
                .map(route -> route.handler().apply(request))
                .orElse(StubResponse.json(404, "{\"error\":\"no stub for " + request.method() + " " + request.path() + "\"}"));

        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
