package app;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServerConfig {
	public String id;
	public String name;
	public String description;
	public String command;
	public String cwd;
	public String framing;
	public String transport;
	public String httpUrl;
	public String httpMessageUrl;
	public Map<String, String> httpHeaders = new HashMap<>();
	public boolean supportsTools;
	public boolean supportsResources;
	public boolean supportsPrompts;
	public boolean supportsDynamicConfig;
	public boolean allowPolicy;
	public JsonNode configSchema;
	public JsonNode configuration;
	public Map<String, String> env = new HashMap<>();
	public Map<String, List<SavedInput>> savedInputs = new HashMap<>();

	public static class SavedInput {
		public String id;
		public String name;
		public String comment;
		public String json;
		public JsonNode meta;
		public JsonNode policy;
		public Instant updatedAt;
	}
}
