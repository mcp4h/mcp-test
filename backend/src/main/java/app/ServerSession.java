package app;
import com.fasterxml.jackson.databind.JsonNode;

public class ServerSession {
	public final ServerConfig config;
	public final Process process;
	public final McpClient client;
	public final LogBroadcaster logStream;
	public volatile boolean connected;
	public volatile JsonNode capabilities;
	public volatile JsonNode rawInitialize;
	public volatile JsonNode tools;
	public volatile JsonNode resources;
	public volatile JsonNode prompts;

	public ServerSession(ServerConfig config, Process process, McpClient client, LogBroadcaster logStream) {
		this.config = config;
		this.process = process;
		this.client = client;
		this.logStream = logStream;
		this.connected = false;
	}

	public boolean isRunning() {
		if (process == null) {
			return connected;
		}
		return process.isAlive() && connected;
	}
}
