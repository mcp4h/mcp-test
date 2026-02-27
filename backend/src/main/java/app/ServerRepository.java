package app;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ServerRepository {
	private static final Logger LOGGER = Logger.getLogger(ServerRepository.class);
	private final Path dataDir;
	private final ObjectMapper mapper;
	private final Map<String, ServerConfig> cache = new ConcurrentHashMap<>();
	private volatile boolean cacheLoaded = false;

	@Inject
	public ServerRepository(@ConfigProperty(name = "mcp.data-dir") String dataDir, ObjectMapper mapper) {
		this.dataDir = Path.of(dataDir).resolve("servers");
		this.mapper = mapper;
	}

	public List<ServerConfig> list() {
		loadAllIfNeeded();
		List<ServerConfig> result = new ArrayList<>(cache.values());
		result.sort(Comparator.comparing(config -> config.id == null ? "" : config.id));
		return result;
	}

	public Optional<ServerConfig> get(String id) {
		loadAllIfNeeded();
		ServerConfig cached = cache.get(id);
		return Optional.ofNullable(cached);
	}

	public boolean exists(String id) {
		loadAllIfNeeded();
		return cache.containsKey(id);
	}

	public ServerConfig create(
		String name,
		String description,
		String command,
		String cwd,
		String framing,
		String transport,
		String httpUrl,
		String httpMessageUrl,
		java.util.Map<String, String> httpHeaders,
		java.util.Map<String, String> env,
		boolean supportsDynamicConfig,
		boolean allowPolicy,
		com.fasterxml.jackson.databind.JsonNode configuration) {
		loadAllIfNeeded();
		String id = ServerNames.toId(name);
		if (id.isBlank()) {
			throw new IllegalArgumentException("Invalid server name");
		}
		Path path = dataDir.resolve(id + ".json");
		if (cache.containsKey(id) || Files.exists(path)) {
			throw new IllegalArgumentException("Server name already exists");
		}
		ServerConfig config = new ServerConfig();
		config.id = id;
		config.name = name.trim();
		config.description = description;
		config.command = command;
		config.cwd = cwd;
		config.framing = framing == null || framing.isBlank() ? "ndjson" : framing;
		config.transport = transport == null || transport.isBlank() ? "stdio" : transport;
		config.httpUrl = httpUrl;
		config.httpMessageUrl = httpMessageUrl;
		config.supportsDynamicConfig = supportsDynamicConfig;
		config.allowPolicy = allowPolicy;
		config.configuration = configuration;
		if (httpHeaders != null) {
			config.httpHeaders.putAll(httpHeaders);
		}
		if (env != null) {
			config.env.putAll(env);
		}
		write(path, config);
		cache.put(config.id, config);
		return config;
	}

	public ServerConfig save(ServerConfig config) {
		if (config == null || config.id == null) {
			throw new IllegalArgumentException("Missing server id");
		}
		ensureDir();
		write(dataDir.resolve(config.id + ".json"), config);
		cache.put(config.id, config);
		return config;
	}

	public ServerConfig rename(String oldId, String newId, ServerConfig config) {
		loadAllIfNeeded();
		ensureDir();
		Path source = dataDir.resolve(oldId + ".json");
		Path target = dataDir.resolve(newId + ".json");
		if (Files.exists(target)) {
			throw new IllegalArgumentException("Server name already exists");
		}
		config.id = newId;
		write(target, config);
		try {
			Files.deleteIfExists(source);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to remove old server config", e);
		}
		cache.remove(oldId);
		cache.put(newId, config);
		return config;
	}

	public ServerConfig addSavedInput(
		String serverId,
		String toolName,
		String name,
		String comment,
		String json,
		com.fasterxml.jackson.databind.JsonNode meta,
		com.fasterxml.jackson.databind.JsonNode policy) {
		ServerConfig config = require(serverId);
		ServerConfig.SavedInput input = new ServerConfig.SavedInput();
		input.id = UUID.randomUUID().toString();
		input.name = name;
		input.comment = comment;
		input.json = json;
		input.meta = meta;
		input.policy = policy;
		input.updatedAt = Instant.now();
		config.savedInputs
			.computeIfAbsent(toolName, k -> new ArrayList<>())
			.add(input);
		return save(config);
	}

	public List<ServerConfig.SavedInput> listSavedInputs(String serverId, String toolName) {
		ServerConfig config = require(serverId);
		return config.savedInputs.getOrDefault(toolName, List.of());
	}

	public ServerConfig deleteSavedInput(String serverId, String toolName, String savedId) {
		ServerConfig config = require(serverId);
		var list = config.savedInputs.get(toolName);
		if (list != null) {
			list.removeIf(item -> savedId.equals(item.id));
			if (list.isEmpty()) {
				config.savedInputs.remove(toolName);
			}
		}
		return save(config);
	}

	public ServerConfig updateSavedInput(
		String serverId,
		String toolName,
		String savedId,
		String name,
		String comment,
		String json,
		com.fasterxml.jackson.databind.JsonNode meta,
		com.fasterxml.jackson.databind.JsonNode policy) {
		ServerConfig config = require(serverId);
		var list = config.savedInputs.get(toolName);
		if (list == null) {
			LOGGER.warnf(
				"Saved inputs not found for tool. server=%s tool=%s savedId=%s dataDir=%s",
				serverId,
				toolName,
				savedId,
				dataDir.toAbsolutePath()
			);
			throw new IllegalArgumentException("Saved inputs not found for tool: " + toolName);
		}
		LOGGER.infof(
			"Update saved input. server=%s tool=%s savedId=%s jsonLength=%s dataDir=%s",
			serverId,
			toolName,
			savedId,
			json == null ? "null" : String.valueOf(json.length()),
			dataDir.toAbsolutePath()
		);
		boolean updated = false;
		for (var item : list) {
			if (savedId.equals(item.id)) {
				if (name != null) {
					item.name = name;
				}
				if (comment != null) {
					item.comment = comment;
				}
				if (json != null) {
					item.json = json;
				}
			if (meta != null) {
				item.meta = meta;
			}
			if (policy != null) {
				item.policy = policy;
			}
			item.updatedAt = Instant.now();
				updated = true;
				break;
			}
		}
		if (!updated) {
			LOGGER.warnf(
				"Saved input id not found. server=%s tool=%s savedId=%s listSize=%s",
				serverId,
				toolName,
				savedId,
				list.size()
			);
			throw new IllegalArgumentException("Saved input id not found: " + savedId);
		}
		return save(config);
	}

	private Optional<ServerConfig> read(Path path) {
		try {
			ServerConfig config = mapper.readValue(path.toFile(), ServerConfig.class);
			if (config.framing == null || config.framing.isBlank()) {
				config.framing = "ndjson";
			}
			if (config.transport == null || config.transport.isBlank()) {
				config.transport = "stdio";
			}
			if (config.httpHeaders == null) {
				config.httpHeaders = new java.util.HashMap<>();
			}
			if (config.env == null) {
				config.env = new java.util.HashMap<>();
			}
			if (config.savedInputs == null) {
				config.savedInputs = new java.util.HashMap<>();
			}
			return Optional.of(config);
		}
		catch (IOException e) {
			return Optional.empty();
		}
	}

	private void loadAllIfNeeded() {
		if (cacheLoaded) {
			return;
		}
		synchronized (this) {
			if (cacheLoaded) {
				return;
			}
			ensureDir();
			try (var paths = Files.list(dataDir)) {
				paths.filter(p -> p.getFileName().toString().endsWith(".json"))
					.sorted(Comparator.naturalOrder())
					.forEach(p -> read(p)
					.ifPresent(config -> cache.put(config.id, config)));
			}
			catch (IOException ignored) {
			}
			cacheLoaded = true;
		}
	}

	private ServerConfig require(String id) {
		loadAllIfNeeded();
		ServerConfig config = cache.get(id);
		if (config == null) {
			throw new IllegalArgumentException("Server not found: " + id);
		}
		return config;
	}

	private void write(Path path, ServerConfig config) {
		ensureDir();
		try {
			mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to write server config", e);
		}
	}

	private void ensureDir() {
		try {
			Files.createDirectories(dataDir);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to create data directory", e);
		}
	}
}
