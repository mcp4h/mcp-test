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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

public class SseMcpClient implements McpClient {
	private static final Logger LOGGER = Logger.getLogger(SseMcpClient.class);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final ObjectMapper mapper;
	private final HttpClient client;
	private final URI sseEndpoint;
	private final Map<String, String> headers;
	private final Consumer<String> logSink;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final AtomicBoolean streamStarted = new AtomicBoolean(false);
	private final CompletableFuture<URI> messageEndpoint = new CompletableFuture<>();
	private final URI fallbackEndpoint;

	public SseMcpClient(
		ObjectMapper mapper,
		String sseUrl,
		String messageUrl,
		Map<String, String> headers,
		Consumer<String> logSink) {
		this.mapper = mapper;
		this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		this.sseEndpoint = URI.create(sseUrl);
		this.headers = headers;
		this.logSink = logSink;
		this.fallbackEndpoint = deriveMessageEndpoint(sseUrl, messageUrl);
		if (messageUrl != null && !messageUrl.isBlank()) {
			messageEndpoint.complete(URI.create(messageUrl));
			log("configured message endpoint " + messageUrl);
		}
		else if (fallbackEndpoint != null) {
			log("fallback message endpoint " + fallbackEndpoint);
		}
	}

	@Override
	public CompletableFuture<JsonNode> initialize() {
		ObjectNode params = mapper.createObjectNode();
		params.put("protocolVersion", "2024-11-05");
		params.putObject("capabilities");
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
	public CompletableFuture<JsonNode> callTool(String name, JsonNode arguments, Map<String, String> meta) {
		ObjectNode params = mapper.createObjectNode();
		params.put("name", name);
		if (arguments != null) {
			params.set("arguments", arguments);
		}
		if (meta != null && !meta.isEmpty()) {
			params.set("_meta", mapper.valueToTree(meta));
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
		return send(payload)
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
		return send(payload);
	}

	private void notify(String method, JsonNode params) {
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		send(payload);
	}

	private CompletableFuture<JsonNode> send(ObjectNode payload) {
		ensureStream();
		Integer id = payload.has("id") ? payload.get("id").asInt() : null;
		CompletableFuture<JsonNode> response = new CompletableFuture<>();
		if (id != null) {
			pending.put(id, response);
		}
		try {
			return resolveEndpoint()
				.thenCompose(endpoint -> postMessage(endpoint, payload, id, response));
		}
		catch (Exception e) {
			if (id != null) {
				pending.remove(id);
			}
			response.completeExceptionally(e);
			return response;
		}
	}

	private CompletableFuture<JsonNode> postMessage(URI endpoint, ObjectNode payload, Integer id, CompletableFuture<JsonNode> response) {
		try {
			String json = mapper.writeValueAsString(payload);
			log(">> " + json);
			log("post message " + endpoint);
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(endpoint)
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.header("Content-Type", "application/json");
			if (headers != null) {
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					builder.header(entry.getKey(), entry.getValue());
				}
			}
			return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
				.handle(
					(httpResponse, error) -> {
						if (error != null) {
							if (id != null) {
								pending.remove(id);
							}
							response.completeExceptionally(error);
							return response;
						}
						if (httpResponse.statusCode() >= 400) {
							if (id != null) {
								pending.remove(id);
							}
							log("<< HTTP " + httpResponse.statusCode() + " from " + endpoint);
							response.completeExceptionally(new IllegalStateException("HTTP " + httpResponse.statusCode() + " from server"));
						}
						return response;
					}
				)
				.thenCompose(future -> future);
		}
		catch (Exception e) {
			if (id != null) {
				pending.remove(id);
			}
			response.completeExceptionally(e);
			return response;
		}
	}

	private void ensureStream() {
		if (!streamStarted.compareAndSet(false, true)) {
			return;
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(sseEndpoint)
			.GET()
			.header("Accept", "text/event-stream");
		if (headers != null) {
			for (Map.Entry<String, String> entry : headers.entrySet()) {
				builder.header(entry.getKey(), entry.getValue());
			}
		}
		client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
			.thenAccept(
				response -> {
					if (response.statusCode() >= 400) {
						try (InputStream body = response.body()) {
							String payload = new String(body.readAllBytes(), StandardCharsets.UTF_8);
							log("<< HTTP " + response.statusCode() + " " + payload);
						}
						catch (Exception ignored) {
						}
						failAll(new IllegalStateException("HTTP " + response.statusCode() + " from SSE server"));
						return;
					}
					log("sse connected " + sseEndpoint + " status=" + response.statusCode());
					parseSseStream(response.body());
				}
			)
			.exceptionally(error -> {
				failAll(error);
				return null;
			});
	}

	private void parseSseStream(InputStream input) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("data:")) {
					data.append(line.substring(5).stripLeading());
					data.append('\n');
				}
				else if (line.isBlank()) {
					String payload = data.toString().trim();
					data.setLength(0);
					if (!payload.isEmpty()) {
						handleEvent(payload);
					}
				}
			}
		}
		catch (Exception e) {
			failAll(e);
		}
		failAll(new IllegalStateException("SSE stream ended"));
	}

	private void handleEvent(String payload) throws Exception {
		log("<< " + payload);
		String trimmed = payload.trim();
		if (looksLikeUrl(trimmed)) {
			setMessageEndpoint(trimmed);
			return;
		}
		JsonNode node = mapper.readTree(payload);
		if (node.has("endpoint") && !messageEndpoint.isDone()) {
			String endpoint = node.get("endpoint").asText(null);
			setMessageEndpoint(endpoint);
			return;
		}
		if (node.has("id")) {
			int id = node.get("id").asInt();
			CompletableFuture<JsonNode> future = pending.remove(id);
			if (future != null) {
				future.complete(node);
			}
		}
	}

	private void failAll(Throwable error) {
		RuntimeException failure = error instanceof RuntimeException ? (RuntimeException) error : new IllegalStateException("SSE failure", error);
		pending.forEach((id, future) -> future.completeExceptionally(failure));
		pending.clear();
	}

	private CompletableFuture<URI> resolveEndpoint() {
		if (messageEndpoint.isDone() || fallbackEndpoint == null) {
			return messageEndpoint;
		}
		return messageEndpoint.orTimeout(1, TimeUnit.SECONDS)
			.exceptionally(
				error -> {
					log("sse endpoint not received, using fallback " + fallbackEndpoint);
					return fallbackEndpoint;
				}
			);
	}

	private void setMessageEndpoint(String endpoint) {
		if (endpoint == null || endpoint.isBlank() || messageEndpoint.isDone()) {
			return;
		}
		messageEndpoint.complete(URI.create(endpoint));
		log("sse endpoint " + endpoint);
	}

	private boolean looksLikeUrl(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		String lower = value.toLowerCase();
		return lower.startsWith("http://") || lower.startsWith("https://");
	}

	private void log(String message) {
		logSink.accept(message);
		LOGGER.info(message);
	}

	private static URI deriveMessageEndpoint(String sseUrl, String messageUrl) {
		if (messageUrl != null && !messageUrl.isBlank()) {
			return URI.create(messageUrl);
		}
		if (sseUrl == null || sseUrl.isBlank()) {
			return null;
		}
		URI uri = URI.create(sseUrl);
		String path = uri.getPath();
		if (path == null || !path.contains("/sse")) {
			return null;
		}
		String updatedPath = path.replaceFirst("/sse/?$", "/message");
		if (updatedPath.equals(path)) {
			return null;
		}
		String base = uri.getScheme() + "://" + uri.getAuthority() + updatedPath;
		String query = uri.getQuery();
		if (query != null && !query.isBlank()) {
			base = base + "?" + query;
		}
		return URI.create(base);
	}
}
