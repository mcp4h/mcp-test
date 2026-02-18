package app;
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
	public Map<String, String> env = new HashMap<>();
	public Map<String, List<SavedInput>> savedInputs = new HashMap<>();

	public static class SavedInput {
		public String id;
		public String name;
		public String comment;
		public String json;
		public Map<String, String> meta;
		public Instant updatedAt;
	}
}
