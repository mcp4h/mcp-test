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
	private final Supplier<Map<String, String>> headers;
	private final Consumer<String> logSink;
	private final Consumer<String> unauthorizedHandler;
	private final Consumer<String> authHeadersSink;
	private final Consumer<String> sessionIdSink;
	private volatile String lastSessionId;
	private final CompletableFuture<Void> sseReady = new CompletableFuture<>();
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final AtomicBoolean streamStarted = new AtomicBoolean(false);
	private final CompletableFuture<URI> messageEndpoint = new CompletableFuture<>();
	private final URI fallbackEndpoint;

	public SseMcpClient(
		ObjectMapper mapper,
		String sseUrl,
		String messageUrl,
		Supplier<Map<String, String>> headers,
		Consumer<String> logSink,
		Consumer<String> unauthorizedHandler,
		Consumer<String> authHeadersSink,
		Consumer<String> sessionIdSink) {
		this.mapper = mapper;
		this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		this.sseEndpoint = URI.create(sseUrl);
		this.headers = headers;
		this.logSink = logSink;
		this.unauthorizedHandler = unauthorizedHandler;
		this.authHeadersSink = authHeadersSink;
		this.sessionIdSink = sessionIdSink;
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
				if (waitForSessionId()) {
					notify("initialized", null);
				}
				else {
					log("initialized notification skipped (no mcp-session-id)");
				}
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
		String method = payload.has("method") ? payload.get("method").asText("") : "";
		Integer id = payload.has("id") ? payload.get("id").asInt() : null;
		CompletableFuture<JsonNode> response = new CompletableFuture<>();
		if (id != null) {
			pending.put(id, response);
		}
		try {
			ensureStream();
			return maybeWaitForSseReady(payload)
				.thenCompose(ignored -> resolveEndpoint())
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
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream");
			String method = payload.has("method") ? payload.get("method").asText("") : "";
			logRequestLine(method, endpoint);
			Map<String, String> activeHeaders = headers == null ? null : headers.get();
			if (activeHeaders != null && !activeHeaders.isEmpty()) {
				logRequestHeaders(method, endpoint, activeHeaders);
				for (Map.Entry<String, String> entry : activeHeaders.entrySet()) {
					builder.header(entry.getKey(), entry.getValue());
				}
			}
			else {
				log(">> HTTP headers" + formatMethodSuffix(method) + ": (none)");
			}
			return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
				.handle(
					(httpResponse, error) -> {
						if (error != null) {
							if (id != null) {
								pending.remove(id);
							}
							response.completeExceptionally(error);
							return response;
						}
						logResponseHeaders(method, httpResponse);
						if (httpResponse.statusCode() >= 400) {
							if (id != null) {
								pending.remove(id);
							}
							if (httpResponse.statusCode() == 401) {
								logUnauthorized(httpResponse);
								handleUnauthorized(httpResponse);
							}
						String responseBody = null;
						try (InputStream body = httpResponse.body()) {
								responseBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
								log("<< HTTP body" + formatMethodSuffix(method) + " " + responseBody);
							}
							catch (Exception ignored) {
								log("<< HTTP body" + formatMethodSuffix(method) + ": (unreadable)");
							}
							String message = responseBody == null || responseBody.isBlank()
								? "HTTP " + httpResponse.statusCode() + " from server"
								: "HTTP " + httpResponse.statusCode() + " from server: " + responseBody;
							response.completeExceptionally(new IllegalStateException(message));
						}
						captureSessionId(httpResponse);
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
			.header("Accept", "application/json, text/event-stream");
		logRequestLine("sse", sseEndpoint);
		Map<String, String> activeHeaders = headers == null ? null : headers.get();
		if (activeHeaders != null && !activeHeaders.isEmpty()) {
			logRequestHeaders("sse", sseEndpoint, activeHeaders);
			for (Map.Entry<String, String> entry : activeHeaders.entrySet()) {
				builder.header(entry.getKey(), entry.getValue());
			}
		}
		else {
			log(">> HTTP headers" + formatMethodSuffix("sse") + ": (none)");
		}
		log("sse connecting " + sseEndpoint);
		client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
			.thenAccept(
				response -> {
					logResponseHeaders("sse", response);
					String contentType = response.headers().firstValue("Content-Type").orElse("");
					if (response.statusCode() >= 400) {
						if (response.statusCode() == 401) {
							logUnauthorized(response);
							handleUnauthorized(response);
						}
						try (InputStream body = response.body()) {
							String payload = new String(body.readAllBytes(), StandardCharsets.UTF_8);
							log("<< HTTP body" + formatMethodSuffix("sse") + " " + payload);
						}
						catch (Exception ignored) {
						}
						failAll(new IllegalStateException("HTTP " + response.statusCode() + " from SSE server"));
						sseReady.completeExceptionally(new IllegalStateException("HTTP " + response.statusCode() + " from SSE server"));
						return;
					}
					if (!contentType.toLowerCase().contains("text/event-stream")) {
						try (InputStream body = response.body()) {
							String payload = new String(body.readAllBytes(), StandardCharsets.UTF_8);
							log("<< HTTP body" + formatMethodSuffix("sse") + " " + payload);
							IllegalStateException error = new IllegalStateException("Unexpected SSE response Content-Type: " + contentType);
							failAll(error);
							sseReady.completeExceptionally(error);
						}
						catch (Exception e) {
							failAll(e);
							sseReady.completeExceptionally(e);
						}
						return;
					}
					captureSessionId(response);
					sseReady.complete(null);
					log("sse connected " + sseEndpoint + " status=" + response.statusCode());
					parseSseStream(response.body());
				}
			)
			.exceptionally(error -> {
				failAll(error);
				sseReady.completeExceptionally(error);
				return null;
			});
	}

	private void parseSseStream(InputStream input) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				log("<< [sse] " + line);
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
				else if (line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) {
					continue;
				}
				else if (line.startsWith(":")) {
					log("<< [sse:comment] " + line.substring(1).stripLeading());
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
		if (messageEndpoint.isDone()) {
			return messageEndpoint;
		}
		log("sse waiting for message endpoint");
		return messageEndpoint.orTimeout(15, TimeUnit.SECONDS)
			.exceptionally(
				error -> {
					if (fallbackEndpoint != null) {
						log("sse endpoint not received, using fallback " + fallbackEndpoint);
						return fallbackEndpoint;
					}
					log("sse endpoint not received, cannot post without message endpoint");
					throw new IllegalStateException("SSE message endpoint not received");
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
		log(dump);
		if (authHeadersSink != null) {
			authHeadersSink.accept(dump.replaceFirst("^<< HTTP 401 headers:\\n", ""));
		}
	}

	private void captureSessionId(HttpResponse<?> response) {
		if (sessionIdSink == null || response == null) {
			return;
		}
		response.headers().firstValue("mcp-session-id").ifPresent(
			value -> {
				lastSessionId = value;
				sessionIdSink.accept(value);
			}
		);
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
		log(builder.toString().trim());
	}

	private static String formatMethodSuffix(String method) {
		if (method == null || method.isBlank()) {
			return "";
		}
		return " (" + method + ")";
	}

	private boolean waitForSessionId() {
		if (lastSessionId != null && !lastSessionId.isBlank()) {
			return true;
		}
		long deadline = System.currentTimeMillis() + 1500;
		while (System.currentTimeMillis() < deadline) {
			if (lastSessionId != null && !lastSessionId.isBlank()) {
				return true;
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return lastSessionId != null && !lastSessionId.isBlank();
	}

	private CompletableFuture<Void> maybeWaitForSseReady(ObjectNode payload) {
		if (payload == null) {
			return CompletableFuture.completedFuture(null);
		}
		String method = payload.has("method") ? payload.get("method").asText("") : "";
		if (!"initialize".equals(method)) {
			return CompletableFuture.completedFuture(null);
		}
		return sseReady.orTimeout(2, TimeUnit.SECONDS)
			.exceptionally(error -> null);
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
		log(builder.toString().trim());
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
		log(builder.toString());
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
