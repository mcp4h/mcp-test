package app;
import java.util.Locale;

public final class ServerNames {
	private ServerNames() {
	}

	public static String toId(String name) {
		if (name == null) {
			return "";
		}
		String slug = name.trim().toLowerCase(Locale.ROOT);
		slug = slug.replaceAll("[^a-z0-9]+", "-");
		slug = slug.replaceAll("^-+", "");
		slug = slug.replaceAll("-+$", "");
		return slug;
	}
}
