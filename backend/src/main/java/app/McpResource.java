package app;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

@Path("/servers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpResource {
	private static final Logger LOGGER = Logger.getLogger(McpResource.class);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	@Inject ServerRepository repository;
	@Inject ServerSessions sessions;
	@Inject ObjectMapper mapper;
	@Inject OAuthService oauth;

	@POST
	public ServerConfig createServer(CreateServerRequest request) {
		ServerConfig config = repository.create(
			request.name,
			request.description,
			request.command,
			request.cwd,
			request.framing,
			request.transport,
			request.httpUrl,
			request.httpMessageUrl,
			request.httpHeaders,
			request.env,
			request.supportsDynamicConfig,
			request.allowPolicy,
			request.configuration
		);
		config.openidRequired = request.openidRequired;
		config.openidResource = request.openidResource;
		config.oauthClientId = request.oauthClientId;
		config.oauthClientSecret = request.oauthClientSecret;
		config.oauthAuthorizationUrl = request.oauthAuthorizationUrl;
		config.oauthTokenUrl = request.oauthTokenUrl;
		config.oauthScopes = request.oauthScopes;
		ensureConfigSchema(config);
		return config;
	}

	@POST
	@Path("/{serverName}")
	public ServerConfig updateServer(@PathParam("serverName") String serverName, UpdateServerRequest request) {
		ServerConfig config = repository.get(serverName).orElseThrow();
		boolean hasConfiguration = request.configuration != null && !request.configuration.isNull();
		String newName = request.name != null && !request.name.isBlank() ? request.name.trim() : config.name;
		String newId = ServerNames.toId(newName);
		if (newId.isBlank()) {
			throw new IllegalArgumentException("Invalid server name");
		}
		if (!newId.equals(serverName) && sessions.isRunning(serverName)) {
			throw new IllegalStateException("Stop the server before renaming");
		}
		config.name = newName;
		if (request.description != null) {
			config.description = request.description;
		}
		if (request.command != null) {
			config.command = request.command;
		}
		if (request.cwd != null) {
			config.cwd = request.cwd;
		}
		if (request.framing != null) {
			config.framing = request.framing;
		}
		if (request.transport != null) {
			config.transport = request.transport;
		}
		if (request.httpUrl != null) {
			config.httpUrl = request.httpUrl;
		}
		if (request.httpMessageUrl != null) {
			config.httpMessageUrl = request.httpMessageUrl;
		}
		if (request.httpHeaders != null) {
			config.httpHeaders = request.httpHeaders;
		}
		if (request.env != null) {
			config.env = request.env;
		}
		if (request.supportsDynamicConfig != null) {
			config.supportsDynamicConfig = request.supportsDynamicConfig;
		}
		if (request.allowPolicy != null) {
			config.allowPolicy = request.allowPolicy;
		}
		if (request.openidRequired != null) {
			config.openidRequired = request.openidRequired;
		}
		if (request.openidResource != null) {
			config.openidResource = request.openidResource;
		}
		if (request.oauthClientId != null) {
			config.oauthClientId = request.oauthClientId;
		}
		if (request.oauthClientSecret != null) {
			config.oauthClientSecret = request.oauthClientSecret;
		}
		if (request.oauthAuthorizationUrl != null) {
			config.oauthAuthorizationUrl = request.oauthAuthorizationUrl;
		}
		if (request.oauthTokenUrl != null) {
			config.oauthTokenUrl = request.oauthTokenUrl;
		}
		if (request.oauthScopes != null) {
			config.oauthScopes = request.oauthScopes;
		}
		if (hasConfiguration) {
			config.configuration = request.configuration;
		}
		if (!newId.equals(serverName)) {
			ServerConfig renamed = repository.rename(serverName, newId, config);
			ensureConfigSchema(renamed);
			return renamed;
		}
		ServerConfig saved = repository.save(config);
		ensureConfigSchema(saved);
		return saved;
	}

	@GET
	public List<ServerConfig> listServers() {
		return repository.list();
	}

	@POST
	@Path("/{serverName}/start")
	public ServerStatus startServer(@PathParam("serverName") String serverName) throws Exception {
		ServerSession session = sessions.start(serverName);
		try {
			session.logStream.publish(Instant.now().toString() + " initialize start");
			logRpcRequest(session, "initialize", buildInitializeParams(session.config.configuration));
			session.rawInitialize = attemptInitialize(session, false);
			session.capabilities = session.rawInitialize;
			session.connected = true;
			try {
				session.logStream.publish(Instant.now().toString() + " initialize response " + mapper.writeValueAsString(session.rawInitialize));
			}
			catch (Exception ignored) {
				session.logStream.publish(Instant.now().toString() + " initialize response (unprintable)");
			}
			session.logStream.publish(Instant.now().toString() + " initialize success");
			updateConfigSchemaFromInitialize(session.config, session.rawInitialize);
			session.tools = safeList(session, "tools/list", session.client::listTools, supported -> session.config.supportsTools = supported);
			session.resources = safeList(session, "resources/list", session.client::listResources, supported -> session.config.supportsResources = supported);
			session.prompts = safeList(session, "prompts/list", session.client::listPrompts, supported -> session.config.supportsPrompts = supported);
			repository.save(session.config);
			return statusFor(session);
		}
		catch (Exception e) {
			session.logStream.publish(Instant.now().toString() + " initialize failed: " + e.getMessage());
			sessions.stop(serverName);
			throw e;
		}
	}

	private JsonNode attemptInitialize(ServerSession session, boolean retried) throws Exception {
		try {
			return session.client
				.initialize(session.config.configuration)
				.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (Exception e) {
			logRpcError(session, "initialize", e);
			if (!retried && session.mcpSessionId != null && !session.mcpSessionId.isBlank()) {
				session.logStream.publish(Instant.now().toString() + " initialize retry with mcp-session-id");
				return attemptInitialize(session, true);
			}
			throw e;
		}
	}

	@POST
	@Path("/{serverName}/stop")
	public Response stopServer(@PathParam("serverName") String serverName) {
		sessions.stop(serverName);
		return Response.noContent().build();
	}

	@GET
	@Path("/{serverName}/status")
	public ServerStatus status(@PathParam("serverName") String serverName) {
		ServerSession session = sessions.get(serverName);
		if (session == null || !session.isRunning()) {
			return new ServerStatus(false, null, null, null);
		}
		return statusFor(session);
	}

	@GET
	@Path("/{serverName}/facets")
	public FacetsResponse facets(@PathParam("serverName") String serverName) throws Exception {
		ServerSession session = requireSession(serverName);
		session.tools = safeList(session, "tools/list", session.client::listTools, supported -> session.config.supportsTools = supported);
		session.resources = safeList(session, "resources/list", session.client::listResources, supported -> session.config.supportsResources = supported);
		session.prompts = safeList(session, "prompts/list", session.client::listPrompts, supported -> session.config.supportsPrompts = supported);
		repository.save(session.config);
		FacetsResponse response = new FacetsResponse();
		response.tools = session.tools;
		response.resources = session.resources;
		response.prompts = session.prompts;
		response.applications = extractApplications(session.resources);
		return response;
	}

	@POST
	@Path("/{serverName}/invoke")
	public JsonNode invoke(@PathParam("serverName") String serverName, InvokeRequest request) throws Exception {
		ServerSession session = requireSession(serverName);
		JsonNode args = parseJson(request.json);
		logRpcRequest(session, "tools/call", buildToolCallParams(request.toolName, args, request.meta));
		JsonNode result = session.client
			.callTool(request.toolName, args, request.meta)
			.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		logRpcResponse(session, "tools/call", result);
		return result;
	}

	@POST
	@Path("/{serverName}/resource")
	public JsonNode readResource(@PathParam("serverName") String serverName, ResourceRequest request) throws Exception {
		ServerSession session = requireSession(serverName);
		logRpcRequest(session, "resources/read", buildResourceParams(request.uri));
		JsonNode result = session.client
			.readResource(request.uri)
			.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		logRpcResponse(session, "resources/read", result);
		return result;
	}

	@POST
	@Path("/{serverName}/prompt")
	public JsonNode getPrompt(@PathParam("serverName") String serverName, PromptRequest request) throws Exception {
		ServerSession session = requireSession(serverName);
		JsonNode args = parseJson(request.json);
		logRpcRequest(session, "prompts/get", buildPromptParams(request.name, args));
		JsonNode result = session.client
			.getPrompt(request.name, args)
			.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		logRpcResponse(session, "prompts/get", result);
		return result;
	}

	@GET
	@Path("/{serverName}/auth/status")
	public OAuthService.AuthStatus authStatus(@PathParam("serverName") String serverName) {
		return oauth.status(serverName);
	}

	@POST
	@Path("/{serverName}/auth/login")
	public OAuthService.LoginResponse authLogin(@PathParam("serverName") String serverName) {
		return oauth.beginLogin(serverName);
	}

	@POST
	@Path("/{serverName}/auth/logout")
	public Response authLogout(@PathParam("serverName") String serverName) {
		oauth.logout(serverName);
		return Response.noContent().build();
	}

	@GET
	@Path("/{serverName}/auth/callback")
	@Produces(MediaType.TEXT_HTML)
	public Response authCallback(
		@PathParam("serverName") String serverName,
		@jakarta.ws.rs.QueryParam("code") String code,
		@jakarta.ws.rs.QueryParam("state") String state) {
		OAuthService.CallbackResult result = oauth.handleCallback(serverName, code, state);
		String html;
		if (result.success) {
			html = "<html><body><script>window.close();</script>Authorization complete.</body></html>";
		}
		else {
			html = "<html><body>Authorization failed: " + result.error + "</body></html>";
		}
		return Response.ok(html).build();
	}

	@GET
	@Path("/{serverName}/events")
	@Produces(MediaType.SERVER_SENT_EVENTS)
	@RestStreamElementType(MediaType.TEXT_PLAIN)
	public Multi<String> events(@PathParam("serverName") String serverName) {
		return sessions.logStreamFor(serverName).stream();
	}

	@POST
	@Path("/{serverName}/tools/{toolName}/saved")
	public ServerConfig addSavedInput(
			@PathParam("serverName") String serverName,
			@PathParam("toolName") String toolName,
			SavedInputRequest request) {
		ServerConfig updated = repository.addSavedInput(serverName, toolName, request.name, request.comment, request.json, request.meta, request.policy);
		ServerSession session = sessions.get(serverName);
		if (session != null) {
			session.config.savedInputs = updated.savedInputs;
		}
		return updated;
	}

	@GET
	@Path("/{serverName}/tools/{toolName}/saved")
	public List<ServerConfig.SavedInput> listSavedInputs(
			@PathParam("serverName") String serverName,
			@PathParam("toolName") String toolName) {
		return repository.listSavedInputs(serverName, toolName);
	}

	@DELETE
	@Path("/{serverName}/tools/{toolName}/saved/{savedId}")
	public ServerConfig deleteSavedInput(
			@PathParam("serverName") String serverName,
			@PathParam("toolName") String toolName,
			@PathParam("savedId") String savedId) {
		ServerConfig updated = repository.deleteSavedInput(serverName, toolName, savedId);
		ServerSession session = sessions.get(serverName);
		if (session != null) {
			session.config.savedInputs = updated.savedInputs;
		}
		return updated;
	}

	@PUT
	@Path("/{serverName}/tools/{toolName}/saved/{savedId}")
	public ServerConfig updateSavedInput(
			@PathParam("serverName") String serverName,
			@PathParam("toolName") String toolName,
			@PathParam("savedId") String savedId,
			SavedInputRequest request) {
		ServerConfig updated = repository.updateSavedInput(serverName, toolName, savedId, request.name, request.comment, request.json, request.meta, request.policy);
		ServerSession session = sessions.get(serverName);
		if (session != null) {
			session.config.savedInputs = updated.savedInputs;
		}
		return updated;
	}

	private ServerSession requireSession(String serverName) {
		ServerSession session = sessions.get(serverName);
		if (session == null || !session.isRunning()) {
			throw new IllegalStateException("Server not running");
		}
		return session;
	}

	private JsonNode parseJson(String json) throws Exception {
		if (json == null || json.isBlank()) {
			return mapper.createObjectNode();
		}
		try {
			return mapper.readTree(json);
		}
		catch (Exception e) {
			LOGGER.errorf(e, "Failed to parse JSON input: %s", json);
			throw e;
		}
	}

	private ServerStatus statusFor(ServerSession session) {
		ServerStatus status = new ServerStatus(true, session.config.command, session.capabilities, session.rawInitialize);
		return status;
	}

	private JsonNode safeList(
		ServerSession session,
		String method,
		Supplier<java.util.concurrent.CompletableFuture<JsonNode>> call,
		java.util.function.Consumer<Boolean> markSupported) throws Exception {
		try {
			logRpcRequest(session, method, null);
			JsonNode result = call.get().get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			markSupported.accept(true);
			logRpcResponse(session, method, result);
			return result;
		}
		catch (Exception e) {
			markSupported.accept(false);
			logRpcError(session, method, e);
			return null;
		}
	}

	private ObjectNode buildInitializeParams(JsonNode configuration) {
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
		return params;
	}

	private ObjectNode buildToolCallParams(String name, JsonNode args, JsonNode meta) {
		ObjectNode params = mapper.createObjectNode();
		params.put("name", name);
		if (args != null) {
			params.set("arguments", args);
		}
		if (meta != null && !meta.isNull()) {
			params.set("_meta", meta);
		}
		return params;
	}

	private ObjectNode buildResourceParams(String uri) {
		ObjectNode params = mapper.createObjectNode();
		params.put("uri", uri);
		return params;
	}

	private ObjectNode buildPromptParams(String name, JsonNode args) {
		ObjectNode params = mapper.createObjectNode();
		params.put("name", name);
		if (args != null) {
			params.set("arguments", args);
		}
		return params;
	}

	private void logRpcRequest(ServerSession session, String method, JsonNode params) {
		if (session == null || session.logStream == null) {
			return;
		}
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("method", method);
		if (params != null) {
			payload.set("params", params);
		}
		try {
			session.logStream.publish(Instant.now().toString() + " rpc >> " + mapper.writeValueAsString(payload));
		}
		catch (Exception ignored) {
			session.logStream.publish(Instant.now().toString() + " rpc >> " + method);
		}
	}

	private void logRpcResponse(ServerSession session, String method, JsonNode result) {
		if (session == null || session.logStream == null) {
			return;
		}
		ObjectNode payload = mapper.createObjectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("method", method);
		payload.set("result", result == null ? mapper.nullNode() : result);
		try {
			session.logStream.publish(Instant.now().toString() + " rpc << " + mapper.writeValueAsString(payload));
		}
		catch (Exception ignored) {
			session.logStream.publish(Instant.now().toString() + " rpc << " + method);
		}
	}

	private void logRpcError(ServerSession session, String method, Exception error) {
		if (session == null || session.logStream == null) {
			return;
		}
		String message = error == null ? "unknown" : error.getMessage();
		session.logStream.publish(Instant.now().toString() + " rpc !! " + method + " " + message);
	}

	private ArrayNode extractApplications(JsonNode resources) {
		ArrayNode applications = mapper.createArrayNode();
		if (resources == null || resources.isNull()) {
			return applications;
		}
		JsonNode listNode = resources;
		if (resources.has("resources")) {
			listNode = resources.get("resources");
		}
		if (listNode == null || !listNode.isArray()) {
			return applications;
		}
		for (JsonNode entry : listNode) {
			if (!isApplicationResource(entry)) {
				continue;
			}
			String uri = textValue(entry.get("uri"));
			if (uri == null || uri.isBlank()) {
				continue;
			}
			ObjectNode app = mapper.createObjectNode();
			String name = textValue(entry.get("name"));
			if (name != null && !name.isBlank()) {
				app.put("name", name);
			}
			app.put("uri", uri);
			applications.add(app);
		}
		return applications;
	}

	private boolean isApplicationResource(JsonNode entry) {
		if (entry == null || entry.isNull()) {
			return false;
		}
		String mimeType = textValue(entry.get("mimeType"));
		if (mimeType == null || !mimeType.toLowerCase().startsWith("text/html")) {
			return false;
		}
		JsonNode annotations = entry.get("annotations");
		if (annotations == null || !annotations.isObject()) {
			return false;
		}
		String type = textValue(annotations.get("type"));
		return "application".equalsIgnoreCase(type);
	}

	private String textValue(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isTextual()) {
			return null;
		}
		return node.asText();
	}

	private void ensureConfigSchema(ServerConfig config) {
		if (config == null || !config.supportsDynamicConfig) {
			return;
		}
		if (config.configSchema != null && !config.configSchema.isNull()) {
			return;
		}
		ServerSession existing = sessions.get(config.id);
		if (existing != null && existing.rawInitialize != null) {
			updateConfigSchemaFromInitialize(config, existing.rawInitialize);
			repository.save(config);
			return;
		}
		if (sessions.isRunning(config.id)) {
			return;
		}
		ServerSession session = sessions.start(config.id);
		try {
			JsonNode initialize = session.client
				.initialize(config.configuration)
				.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			session.rawInitialize = initialize;
			session.connected = true;
			updateConfigSchemaFromInitialize(config, initialize);
			repository.save(config);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to fetch config schema", e);
		}
		finally {
			sessions.stop(config.id);
		}
	}

	private void updateConfigSchemaFromInitialize(ServerConfig config, JsonNode initialize) {
		if (config == null || initialize == null) {
			return;
		}
		JsonNode schema = initialize.get("configSchema");
		if (schema != null && !schema.isNull()) {
			config.configSchema = schema;
			config.supportsDynamicConfig = true;
		}
		else {
			config.configSchema = null;
			config.supportsDynamicConfig = false;
		}
		JsonNode policyNode = initialize.path("capabilities").path("experimental").path("policy");
		if (policyNode.isBoolean() && policyNode.asBoolean()) {
			config.allowPolicy = true;
		}
		else {
			config.allowPolicy = false;
		}
	}

	public static class CreateServerRequest {
		public String name;
		public String description;
		public String command;
		public String cwd;
		public String framing;
		public String transport;
		public String httpUrl;
		public String httpMessageUrl;
		public Map<String, String> httpHeaders;
		public Map<String, String> env;
		public boolean supportsDynamicConfig;
		public boolean allowPolicy;
		public boolean openidRequired;
		public String openidResource;
		public String oauthClientId;
		public String oauthClientSecret;
		public String oauthAuthorizationUrl;
		public String oauthTokenUrl;
		public String oauthScopes;
		public JsonNode configuration;
	}

	public static class UpdateServerRequest {
		public String name;
		public String description;
		public String command;
		public String cwd;
		public String framing;
		public String transport;
		public String httpUrl;
		public String httpMessageUrl;
		public Map<String, String> httpHeaders;
		public Map<String, String> env;
		public Boolean supportsDynamicConfig;
		public Boolean allowPolicy;
		public Boolean openidRequired;
		public String openidResource;
		public String oauthClientId;
		public String oauthClientSecret;
		public String oauthAuthorizationUrl;
		public String oauthTokenUrl;
		public String oauthScopes;
		public JsonNode configuration;
	}

	public static class InvokeRequest {
		public String toolName;
		public String json;
		public JsonNode meta;
	}

	public static class ResourceRequest {
		public String uri;
	}

	public static class PromptRequest {
		public String name;
		public String json;
	}

	public static class SavedInputRequest {
		public String name;
		public String comment;
		public String json;
		public JsonNode meta;
		public JsonNode policy;
	}

	public static class FacetsResponse {
		public JsonNode tools;
		public JsonNode resources;
		public JsonNode prompts;
		public JsonNode applications;
	}

	public static class ServerStatus {
		public boolean running;
		public String command;
		public JsonNode capabilities;
		public JsonNode initialize;

		public ServerStatus() {
		}

		public ServerStatus(boolean running, String command, JsonNode capabilities, JsonNode initialize) {
			this.running = running;
			this.command = command;
			this.capabilities = capabilities;
			this.initialize = initialize;
		}
	}
}
