package app;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.exec.CommandLine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ServerSessions {
	private final ServerRepository repository;
	private final ObjectMapper mapper;
	private final Map<String, ServerSession> sessions = new ConcurrentHashMap<>();
	private final Map<String, LogBroadcaster> logStreams = new ConcurrentHashMap<>();

	@Inject
	public ServerSessions(ServerRepository repository, ObjectMapper mapper) {
		this.repository = repository;
		this.mapper = mapper;
	}

	public ServerSession start(String serverId) {
		return sessions.computeIfAbsent(
			serverId,
			id -> {
				ServerConfig config = repository.get(id).orElseThrow();
				if ("sse".equalsIgnoreCase(config.transport) || "streamable".equalsIgnoreCase(config.transport)) {
					LogBroadcaster logStream = logStreamFor(id);
					McpClient client = createHttpClient(config, logStream);
					ServerSession session = new ServerSession(config, null, client, logStream);
					logStream.publish(ts() + " created http session " + config.httpUrl);
					return session;
				}
				if (config.transport != null
						&& !config.transport.isBlank()
						&& !"stdio".equalsIgnoreCase(config.transport)) {
					throw new IllegalStateException("Unsupported transport: " + config.transport);
				}
				CommandLine commandLine = CommandLine.parse(config.command);
				java.util.List<String> command = new java.util.ArrayList<>();
				command.add(commandLine.getExecutable());
				if (commandLine.getArguments() != null) {
					for (String arg : commandLine.getArguments()) {
						command.add(arg);
					}
				}
				ProcessBuilder builder = new ProcessBuilder(command);
				if (config.cwd != null && !config.cwd.isBlank()) {
					builder.directory(java.nio.file.Path.of(config.cwd).toFile());
				}
				if (config.env != null && !config.env.isEmpty()) {
					builder.environment().putAll(config.env);
				}
				try {
					Process process = builder.start();
					LogBroadcaster logStream = logStreamFor(id);
					JsonRpcConnection.Framing framing = toFraming(config.framing);
					McpClient client = new StdioMcpClient(mapper, process.getInputStream(), process.getOutputStream(), msg -> logStream.publish(ts() + " " + msg), framing);
					ServerSession session = new ServerSession(config, process, client, logStream);
					startStderrReader(process, logStream);
					startExitWatcher(process, logStream, id);
					logStream.publish(ts() + " started server " + config.command);
					return session;
				}
				catch (IOException e) {
					throw new IllegalStateException("Failed to start server", e);
				}
			}
		);
	}

	public ServerSession get(String serverId) {
		return sessions.get(serverId);
	}

	public boolean isRunning(String serverId) {
		ServerSession session = sessions.get(serverId);
		return session != null && session.isRunning();
	}

	public void stop(String serverId) {
		ServerSession session = sessions.remove(serverId);
		if (session != null) {
			session.logStream.publish(ts() + " stopping server");
			if (session.process != null) {
				session.process.destroy();
			}
		}
	}

	public LogBroadcaster logStreamFor(String serverId) {
		return logStreams.computeIfAbsent(serverId, id -> new LogBroadcaster());
	}

	private void startStderrReader(Process process, LogBroadcaster logStream) {
		Thread stderr = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          logStream.publish(ts() + " [stderr] " + line);
        }
      } catch (IOException ignored) {
      }
    }, "mcp-stderr-reader");
		stderr.setDaemon(true);
		stderr.start();
	}

	private void startExitWatcher(Process process, LogBroadcaster logStream, String serverId) {
		Thread watcher = new Thread(() -> {
      try {
        int code = process.waitFor();
        logStream.publish(ts() + " process exited with code " + code);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      } finally {
        sessions.remove(serverId);
      }
    }, "mcp-exit-watcher");
		watcher.setDaemon(true);
		watcher.start();
	}

	private static String ts() {
		return Instant.now().toString();
	}

	private McpClient createHttpClient(ServerConfig config, LogBroadcaster logStream) {
		if ("sse".equalsIgnoreCase(config.transport)) {
			return new SseMcpClient(mapper, config.httpUrl, config.httpMessageUrl, config.httpHeaders,
          msg -> logStream.publish(ts() + " " + msg));
		}
		return new StreamableHttpMcpClient(mapper, config.httpUrl, config.httpHeaders,
        msg -> logStream.publish(ts() + " " + msg));
	}

	private static JsonRpcConnection.Framing toFraming(String framing) {
		if (framing == null) {
			return JsonRpcConnection.Framing.NDJSON;
		}
		String value = framing.trim().toLowerCase();
		if ("content-length".equals(value)) {
			return JsonRpcConnection.Framing.CONTENT_LENGTH;
		}
		return JsonRpcConnection.Framing.NDJSON;
	}
}
