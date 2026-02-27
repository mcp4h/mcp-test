package app;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface McpClient {
  CompletableFuture<JsonNode> initialize(JsonNode configuration);

  CompletableFuture<JsonNode> listTools();

  CompletableFuture<JsonNode> listResources();

  CompletableFuture<JsonNode> listPrompts();

  CompletableFuture<JsonNode> callTool(String name, JsonNode arguments, JsonNode meta);

  CompletableFuture<JsonNode> readResource(String uri);

  CompletableFuture<JsonNode> getPrompt(String name, JsonNode arguments);
}
