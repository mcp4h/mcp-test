package app;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class JsonRpcConnection {
	public enum Framing {
		NDJSON,
		CONTENT_LENGTH
	}

	private final ObjectMapper mapper;
	private final InputStream input;
	private final OutputStream output;
	private final Consumer<String> logSink;
	private final Framing framing;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final Map<Integer, CompletableFuture<JsonNode>> pendingRaw = new ConcurrentHashMap<>();

	public JsonRpcConnection(
		ObjectMapper mapper,
		InputStream input,
		OutputStream output,
		Consumer<String> logSink,
		Framing framing) {
		this.mapper = mapper;
		this.input = input;
		this.output = output;
		this.logSink = logSink;
		this.framing = framing;
	}

	public void start() {
		Thread reader = new Thread(this::readLoop, "mcp-jsonrpc-reader");
		reader.setDaemon(true);
		reader.start();
	}

	public CompletableFuture<JsonNode> request(String method, JsonNode params) {
		int id = nextId.getAndIncrement();
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("id", id);
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		CompletableFuture<JsonNode> future = new CompletableFuture<>();
		pending.put(id, future);
		send(payload);
		return future;
	}

	public CompletableFuture<JsonNode> requestRaw(String method, JsonNode params) {
		int id = nextId.getAndIncrement();
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("id", id);
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		CompletableFuture<JsonNode> future = new CompletableFuture<>();
		pendingRaw.put(id, future);
		send(payload);
		return future;
	}

	public void notify(String method, JsonNode params) {
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		send(payload);
	}

	private void send(ObjectNode payload) {
		try {
			String json = mapper.writeValueAsString(payload);
			byte[] body = json.getBytes(StandardCharsets.UTF_8);
			logSink.accept(">> " + json);
			synchronized (output) {
				if (framing == Framing.NDJSON) {
					output.write(body);
					output.write('\n');
					output.flush();
				}
				else {
					String header = "Content-Length: " + body.length + "\r\n\r\n";
					output.write(header.getBytes(StandardCharsets.US_ASCII));
					output.write(body);
					output.flush();
				}
			}
		}
		catch (IOException e) {
			logSink.accept("!! failed to send request: " + e.getMessage());
		}
	}

	private void readLoop() {
		try {
			if (framing == Framing.NDJSON) {
				readNdjson();
			}
			else {
				readContentLength();
			}
		}
		catch (Exception e) {
			logSink.accept("!! reader error: " + e.getMessage());
			pending.values()
				.forEach(f -> f.completeExceptionally(e));
			pendingRaw.values()
				.forEach(f -> f.completeExceptionally(e));
			pending.clear();
			pendingRaw.clear();
		}
	}

	private void readNdjson() throws IOException {
		while (true) {
			String line = readLine(input);
			if (line == null) {
				break;
			}
			String cleaned = stripAnsi(line).trim();
			if (cleaned.isBlank()) {
				continue;
			}
			if (!looksLikeJson(cleaned)) {
				logSink.accept(".. " + cleaned);
				continue;
			}
			logSink.accept("<< " + cleaned);
			try {
				JsonNode message = mapper.readTree(cleaned);
				handleMessage(message);
			}
			catch (Exception e) {
				logSink.accept("!! failed to parse json: " + e.getMessage());
			}
		}
	}

	private void readContentLength() throws IOException {
		while (true) {
			String line = readLine(input);
			if (line == null) {
				break;
			}
			if (line.isBlank()) {
				continue;
			}
			int contentLength = 0;
			while (line != null && !line.isBlank()) {
				if (line.toLowerCase().startsWith("content-length:")) {
					String[] parts = line.split(":", 2);
					if (parts.length == 2) {
						contentLength = Integer.parseInt(parts[1].trim());
					}
				}
				line = readLine(input);
			}
			if (contentLength <= 0) {
				continue;
			}
			byte[] body = input.readNBytes(contentLength);
			String json = new String(body, StandardCharsets.UTF_8);
			String cleaned = stripAnsi(json).trim();
			if (!looksLikeJson(cleaned)) {
				logSink.accept(".. " + cleaned);
				continue;
			}
			logSink.accept("<< " + cleaned);
			try {
				JsonNode message = mapper.readTree(cleaned);
				handleMessage(message);
			}
			catch (Exception e) {
				logSink.accept("!! failed to parse json: " + e.getMessage());
			}
		}
	}

	private void handleMessage(JsonNode message) {
		if (message.has("id") && message.has("result")) {
			int id = message.get("id").asInt();
			CompletableFuture<JsonNode> raw = pendingRaw.remove(id);
			if (raw != null) {
				raw.complete(message);
				return;
			}
			CompletableFuture<JsonNode> future = pending.remove(id);
			if (future != null) {
				future.complete(message.get("result"));
			}
		}
		else if (message.has("id") && message.has("error")) {
			int id = message.get("id").asInt();
			CompletableFuture<JsonNode> raw = pendingRaw.remove(id);
			if (raw != null) {
				raw.complete(message);
				return;
			}
			CompletableFuture<JsonNode> future = pending.remove(id);
			if (future != null) {
				future.completeExceptionally(new IllegalStateException(message.get("error").toString()));
			}
		}
	}

	private static String readLine(InputStream input) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		while (true) {
			int ch = input.read();
			if (ch == -1) {
				return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.US_ASCII);
			}
			if (ch == '\n') {
				break;
			}
			if (ch != '\r') {
				buffer.write(ch);
			}
		}
		return buffer.toString(StandardCharsets.US_ASCII);
	}

	private static boolean looksLikeJson(String value) {
		if (value == null) {
			return false;
		}
		String trimmed = value.trim();
		return trimmed.startsWith("{") || trimmed.startsWith("[");
	}

	private static String stripAnsi(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		return value.replaceAll("\\u001B\\[[;?0-9]*[A-Za-z]", "");
	}
}
