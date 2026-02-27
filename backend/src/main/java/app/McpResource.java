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
		ensureConfigSchema(config);
		return config;
	}

	@POST
	@Path("/{serverName}")
	public ServerConfig updateServer(@PathParam("serverName") String serverName, UpdateServerRequest request) {
		ServerConfig config = repository.get(serverName).orElseThrow();
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
			config.configuration = request.configuration;
		}
		if (request.allowPolicy != null) {
			config.allowPolicy = request.allowPolicy;
		}
		if (request.supportsDynamicConfig == null && request.configuration != null) {
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
			session.rawInitialize = session.client
				.initialize(session.config.configuration)
				.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			session.capabilities = session.rawInitialize;
			session.connected = true;
			updateConfigSchemaFromInitialize(session.config, session.rawInitialize);
			session.tools = safeList(session.client::listTools, supported -> session.config.supportsTools = supported);
			session.resources = safeList(session.client::listResources, supported -> session.config.supportsResources = supported);
			session.prompts = safeList(session.client::listPrompts, supported -> session.config.supportsPrompts = supported);
			repository.save(session.config);
			return statusFor(session);
		}
		catch (Exception e) {
			sessions.stop(serverName);
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
		session.tools = safeList(session.client::listTools, supported -> session.config.supportsTools = supported);
		session.resources = safeList(session.client::listResources, supported -> session.config.supportsResources = supported);
		session.prompts = safeList(session.client::listPrompts, supported -> session.config.supportsPrompts = supported);
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
		return session.client
			.callTool(request.toolName, args, request.meta)
			.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
	}

	@POST
	@Path("/{serverName}/resource")
	public JsonNode readResource(@PathParam("serverName") String serverName, ResourceRequest request) throws Exception {
		ServerSession session = requireSession(serverName);
		return session.client
			.readResource(request.uri)
			.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
	}

	@POST
	@Path("/{serverName}/prompt")
	public JsonNode getPrompt(@PathParam("serverName") String serverName, PromptRequest request) throws Exception {
		ServerSession session = requireSession(serverName);
		JsonNode args = parseJson(request.json);
		return session.client
			.getPrompt(request.name, args)
			.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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
		Supplier<java.util.concurrent.CompletableFuture<JsonNode>> call,
		java.util.function.Consumer<Boolean> markSupported) throws Exception {
		try {
			JsonNode result = call.get().get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			markSupported.accept(true);
			return result;
		}
		catch (Exception e) {
			markSupported.accept(false);
			return null;
		}
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
		if (config == null || !config.supportsDynamicConfig || initialize == null) {
			return;
		}
		JsonNode schema = initialize.get("configSchema");
		if (schema != null && !schema.isNull()) {
			config.configSchema = schema;
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
