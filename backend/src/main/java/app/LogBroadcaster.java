package app;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LogBroadcaster {
	private static final int MAX_BUFFER_SIZE = 200;
	private final List<MultiEmitter<? super String>> emitters = new CopyOnWriteArrayList<>();
	private final Deque<String> buffer = new ArrayDeque<>();

	public Multi<String> stream() {
		return Multi.createFrom()
			.emitter(
				emitter -> {
					for (String message : snapshot()) {
						emitter.emit(message);
					}
					emitters.add(emitter);
					emitter.onTermination(() -> emitters.remove(emitter));
				}
			);
	}

	public void publish(String message) {
		addToBuffer(message);
		for (MultiEmitter<? super String> emitter : emitters) {
			emitter.emit(message);
		}
	}

	private void addToBuffer(String message) {
		synchronized (buffer) {
			buffer.addLast(message);
			while (buffer.size() > MAX_BUFFER_SIZE) {
				buffer.removeFirst();
			}
		}
	}

	private List<String> snapshot() {
		synchronized (buffer) {
			return List.copyOf(buffer);
		}
	}
}
