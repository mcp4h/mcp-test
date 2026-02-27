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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class StreamableHttpMcpClient implements McpClient {
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final ObjectMapper mapper;
	private final HttpClient client;
	private final URI endpoint;
	private final Map<String, String> headers;
	private final Consumer<String> logSink;
	private final AtomicInteger nextId = new AtomicInteger(1);

	public StreamableHttpMcpClient(ObjectMapper mapper, String url, Map<String, String> headers, Consumer<String> logSink) {
		this.mapper = mapper;
		this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		this.endpoint = URI.create(url);
		this.headers = headers;
		this.logSink = logSink;
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
		return send(payload, id)
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
		return send(payload, id);
	}

	private void notify(String method, JsonNode params) {
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		send(payload, null);
	}

	private CompletableFuture<JsonNode> send(ObjectNode payload, Integer id) {
		try {
			String json = mapper.writeValueAsString(payload);
			logSink.accept(">> " + json);
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(endpoint)
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.header("Content-Type", "application/json")
				.header("Accept", "application/x-ndjson, application/json");
			if (headers != null) {
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					builder.header(entry.getKey(), entry.getValue());
				}
			}
			return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
				.thenCompose(response -> parseResponse(response, id));
		}
		catch (Exception e) {
			CompletableFuture<JsonNode> failed = new CompletableFuture<>();
			failed.completeExceptionally(e);
			return failed;
		}
	}

	private CompletableFuture<JsonNode> parseResponse(HttpResponse<InputStream> response, Integer id) {
		return CompletableFuture.supplyAsync(
			() -> {
				String contentType = response.headers().firstValue("Content-Type").orElse("");
				try {
					if (response.statusCode() >= 400) {
						String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
						logSink.accept("<< HTTP " + response.statusCode() + " " + body);
						throw new IllegalStateException("HTTP " + response.statusCode() + " from server");
					}
					if (contentType.contains("ndjson") || contentType.contains("jsonlines")) {
						return parseNdjson(response.body(), id);
					}
					String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8).trim();
					if (body.isEmpty()) {
						return mapper.createObjectNode();
					}
					logSink.accept("<< " + body);
					return mapper.readTree(body);
				}
				catch (Exception e) {
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
}
