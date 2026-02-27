package app;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class StdioMcpClient implements McpClient {
	private final ObjectMapper mapper;
	private final JsonRpcConnection connection;

	public StdioMcpClient(
		ObjectMapper mapper,
		InputStream input,
		OutputStream output,
		java.util.function.Consumer<String> logSink,
		JsonRpcConnection.Framing framing) {
		this.mapper = mapper;
		this.connection = new JsonRpcConnection(mapper, input, output, logSink, framing);
		this.connection.start();
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
		return connection.request("initialize", params)
			.thenApply(result -> {
				connection.notify("initialized", null);
				return result;
			});
	}

	@Override
	public CompletableFuture<JsonNode> listTools() {
		return connection.request("tools/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> listResources() {
		return connection.request("resources/list", null);
	}

	@Override
	public CompletableFuture<JsonNode> listPrompts() {
		return connection.request("prompts/list", null);
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
		return connection.requestRaw("tools/call", params);
	}

	@Override
	public CompletableFuture<JsonNode> readResource(String uri) {
		ObjectNode params = mapper.createObjectNode();
		params.put("uri", uri);
		return connection.request("resources/read", params);
	}

	@Override
	public CompletableFuture<JsonNode> getPrompt(String name, JsonNode arguments) {
		ObjectNode params = mapper.createObjectNode();
		params.put("name", name);
		if (arguments != null) {
			params.set("arguments", arguments);
		}
		return connection.request("prompts/get", params);
	}
}
