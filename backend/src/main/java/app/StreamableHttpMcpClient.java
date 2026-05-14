package app;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class StreamableHttpMcpClient implements McpClient {
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final ObjectMapper mapper;
	private final HttpClient client;
	private final URI endpoint;
	private final Supplier<Map<String, String>> headers;
	private final Consumer<String> logSink;
	private final Consumer<String> unauthorizedHandler;
	private final Consumer<String> authHeadersSink;
	private final Consumer<String> sessionIdSink;
	private final AtomicInteger nextId = new AtomicInteger(1);

	public StreamableHttpMcpClient(
		ObjectMapper mapper,
		String url,
		Supplier<Map<String, String>> headers,
		Consumer<String> logSink,
		Consumer<String> unauthorizedHandler,
		Consumer<String> authHeadersSink,
		Consumer<String> sessionIdSink) {
		this.mapper = mapper;
		this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		this.endpoint = URI.create(url);
		this.headers = headers;
		this.logSink = logSink;
		this.unauthorizedHandler = unauthorizedHandler;
		this.authHeadersSink = authHeadersSink;
		this.sessionIdSink = sessionIdSink;
	}

	@Override
	public CompletableFuture<JsonNode> initialize(JsonNode configuration) {
		ObjectNode params = mapper.createObjectNode();
		params.put("protocolVersion", "2024-11-05");
		ObjectNode capabilities = params.putObject("capabilities");
		if (configuration != null && !configuration.isNull()) {
			ObjectNode experimental = capabilities.putObject("experimental");
			experimental.set("configuration", configuration);
		}
		ObjectNode clientInfo = params.putObject("clientInfo");
		clientInfo.put("name", "mcp-tester");
		clientInfo.put("version", "0.1.0");
		return request("initialize", params)
			.thenApply(result -> {
				notify("initialized", null);
				return result;
			});
	}

	@Override
	public CompletableFuture<JsonNode> listTools() {
		return request("tools/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> listResources() {
		return request("resources/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> listPrompts() {
		return request("prompts/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> callTool(String name, JsonNode arguments, JsonNode meta) {
		ObjectNode params = mapper.createObjectNode();
		params.put("name", name);
		if (arguments != null) {
			params.set("arguments", arguments);
		}
		if (meta != null && !meta.isNull()) {
			params.set("_meta", meta);
		}
		return requestRaw("tools/call", params);
	}

	@Override
	public CompletableFuture<JsonNode> readResource(String uri) {
		ObjectNode params = mapper.createObjectNode();
		params.put("uri", uri);
		return request("resources/read", params);
	}

	@Override
	public CompletableFuture<JsonNode> getPrompt(String name, JsonNode arguments) {
		ObjectNode params = mapper.createObjectNode();
		params.put("name", name);
		if (arguments != null) {
			params.set("arguments", arguments);
		}
		return request("prompts/get", params);
	}

	private CompletableFuture<JsonNode> request(String method, JsonNode params) {
		int id = nextId.getAndIncrement();
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("id", id);
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		return send(payload, id, method)
			.thenApply(
				message -> {
					if (message.has("result")) {
						return message.get("result");
					}
					if (message.has("error")) {
						throw new IllegalStateException(message.get("error").toString());
					}
					return message;
				}
			);
	}

	private CompletableFuture<JsonNode> requestRaw(String method, JsonNode params) {
		int id = nextId.getAndIncrement();
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("id", id);
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		return send(payload, id, method);
	}

	private void notify(String method, JsonNode params) {
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		send(payload, null, method);
	}

	private CompletableFuture<JsonNode> send(ObjectNode payload, Integer id, String method) {
		try {
			String json = mapper.writeValueAsString(payload);
			logSink.accept(">> " + json);
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(endpoint)
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.header("Content-Type", "application/json")
				.header("Accept", "application/x-ndjson, application/json, text/event-stream");
			logRequestLine(method, endpoint);
			Map<String, String> activeHeaders = headers == null ? null : headers.get();
			if (activeHeaders != null && !activeHeaders.isEmpty()) {
				logRequestHeaders(method, endpoint, activeHeaders);
				for (Map.Entry<String, String> entry : activeHeaders.entrySet()) {
					builder.header(entry.getKey(), entry.getValue());
				}
			}
			else {
				logSink.accept(">> HTTP headers" + formatMethodSuffix(method) + ": (none)");
			}
		return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
			.thenCompose(response -> parseResponse(response, id, method));
		}
		catch (Exception e) {
			CompletableFuture<JsonNode> failed = new CompletableFuture<>();
			failed.completeExceptionally(e);
			return failed;
		}
	}

	private CompletableFuture<JsonNode> parseResponse(HttpResponse<InputStream> response, Integer id, String method) {
		return CompletableFuture.supplyAsync(
			() -> {
				String contentType = response.headers().firstValue("Content-Type").orElse("");
				logResponseHeaders(method, response);
				captureSessionId(response);
				try {
					if (response.statusCode() >= 400) {
						if (response.statusCode() == 401) {
							logUnauthorized(response);
							handleUnauthorized(response);
						}
						String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
						logSink.accept("<< HTTP body" + formatMethodSuffix(method) + " " + body);
						String message = body == null || body.isBlank()
							? "HTTP " + response.statusCode() + " from server"
							: "HTTP " + response.statusCode() + " from server: " + body;
						throw new IllegalStateException(message);
					}
					if (contentType.contains("ndjson") || contentType.contains("jsonlines")) {
						return parseNdjson(response.body(), id);
					}
					if (contentType.contains("text/event-stream")) {
						return parseSse(response.body(), id);
					}
					String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8).trim();
					if (body.isEmpty()) {
						logSink.accept("<< HTTP body" + formatMethodSuffix(method) + ": (empty)");
						return mapper.createObjectNode();
					}
					logSink.accept("<< HTTP body" + formatMethodSuffix(method) + " " + body);
					return mapper.readTree(body);
				}
				catch (Exception e) {
					if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().startsWith("HTTP ")) {
						throw (IllegalStateException) e;
					}
					throw new IllegalStateException("Failed to parse response", e);
				}
			}
		);
	}

	private JsonNode parseNdjson(InputStream input, Integer id) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String payload = line.trim();
				if (payload.isEmpty()) {
					continue;
				}
				logSink.accept("<< " + payload);
				JsonNode node = mapper.readTree(payload);
				if (id == null) {
					return node;
				}
				if (node.has("id") && node.get("id").asInt() == id) {
					return node;
				}
			}
		}
		throw new IllegalStateException("Stream ended without response");
	}

	private JsonNode parseSse(InputStream input, Integer id) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String line;
			StringBuilder dataBuffer = new StringBuilder();
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					if (dataBuffer.length() == 0) {
						continue;
					}
					String payload = dataBuffer.toString().trim();
					dataBuffer.setLength(0);
					if (payload.isEmpty()) {
						continue;
					}
					logSink.accept("<< " + payload);
					JsonNode node = mapper.readTree(payload);
					if (id == null) {
						return node;
					}
					if (node.has("id") && node.get("id").asInt() == id) {
						return node;
					}
					continue;
				}
				if (trimmed.startsWith("data:")) {
					String data = trimmed.substring(5).trim();
					if (!data.isEmpty()) {
						dataBuffer.append(data).append("\n");
					}
				}
			}
		}
		throw new IllegalStateException("Stream ended without response");
	}

	private void handleUnauthorized(HttpResponse<?> response) {
		if (unauthorizedHandler == null) {
			return;
		}
		String header = response.headers().firstValue("WWW-Authenticate").orElse(null);
		unauthorizedHandler.accept(header);
	}

	private void logUnauthorized(HttpResponse<?> response) {
		StringBuilder builder = new StringBuilder();
		builder.append("<< HTTP 401 headers:\n");
		response.headers().map().forEach(
			(key, values) -> {
				builder.append(key).append("=");
				if (values != null && !values.isEmpty()) {
					builder.append(String.join(";", values));
				}
				builder.append("\n");
			}
		);
		String dump = builder.toString().trim();
		logSink.accept(dump);
		if (authHeadersSink != null) {
			authHeadersSink.accept(dump.replaceFirst("^<< HTTP 401 headers:\\n", ""));
		}
	}

	private void logRequestHeaders(String method, URI url, Map<String, String> activeHeaders) {
		if (activeHeaders == null || activeHeaders.isEmpty()) {
			return;
		}
		StringBuilder builder = new StringBuilder();
		builder.append(">> HTTP headers");
		if (method != null && !method.isBlank()) {
			builder.append(" (").append(method).append(")");
		}
		if (url != null) {
			builder.append(" ").append(url);
		}
		builder.append("\n");
		activeHeaders.forEach(
			(key, value) -> {
				String output = value;
				if ("authorization".equalsIgnoreCase(key) && value != null && !value.isBlank()) {
					int space = value.indexOf(' ');
					if (space > 0) {
						String scheme = value.substring(0, space).trim();
						output = scheme.isBlank() ? "****" : scheme + " ****";
					}
					else {
						output = "****";
					}
				}
				builder.append(key).append("=").append(output == null ? "" : output).append("\n");
			}
		);
		logSink.accept(builder.toString().trim());
	}

	private void logRequestLine(String method, URI url) {
		StringBuilder builder = new StringBuilder();
		builder.append(">> HTTP request");
		if (method != null && !method.isBlank()) {
			builder.append(" (").append(method).append(")");
		}
		if (url != null) {
			builder.append(" ").append(url);
		}
		logSink.accept(builder.toString());
	}

	private void captureSessionId(HttpResponse<?> response) {
		if (sessionIdSink == null || response == null) {
			return;
		}
		response.headers().firstValue("mcp-session-id").ifPresent(sessionIdSink);
	}

	private void logResponseHeaders(String method, HttpResponse<?> response) {
		if (response == null) {
			return;
		}
		StringBuilder builder = new StringBuilder();
		builder.append("<< HTTP response");
		if (method != null && !method.isBlank()) {
			builder.append(" (").append(method).append(")");
		}
		builder.append(" ").append(response.statusCode());
		try {
			URI uri = response.request() != null ? response.request().uri() : null;
			if (uri != null) {
				builder.append(" ").append(uri);
			}
		}
		catch (Exception ignored) {
		}
		builder.append("\n");
		response.headers().map().forEach(
			(key, values) -> {
				builder.append(key).append("=");
				if (values != null && !values.isEmpty()) {
					builder.append(String.join(";", values));
				}
				builder.append("\n");
			}
		);
		logSink.accept(builder.toString().trim());
	}

	private static String formatMethodSuffix(String method) {
		if (method == null || method.isBlank()) {
			return "";
		}
		return " (" + method + ")";
	}
}
