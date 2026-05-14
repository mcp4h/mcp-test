package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class OAuthService {
    private static final Logger LOGGER = Logger.getLogger(OAuthService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TOKEN_SKEW = Duration.ofSeconds(30);
    private final ServerRepository repository;
    private final ObjectMapper mapper;
    private final HttpClient client;
	private final Map<String, OAuthState> authStates = new ConcurrentHashMap<>();
	private final Map<String, OAuthToken> tokens = new ConcurrentHashMap<>();
	private final Map<String, AuthChallenge> challenges = new ConcurrentHashMap<>();
	private final Map<String, String> lastAuthHeaders = new ConcurrentHashMap<>();

    @Inject
    public OAuthService(ServerRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

	public void handleUnauthorized(String serverId, String wwwAuthenticate, String fallbackResource) {
		ServerConfig config = repository.get(serverId).orElse(null);
		if (config == null) {
			return;
		}
		AuthChallenge challenge = null;
		if (wwwAuthenticate != null && !wwwAuthenticate.isBlank()) {
			challenge = AuthChallenge.parse(wwwAuthenticate);
			if (challenge != null && (challenge.resource == null || challenge.resource.isBlank())
				&& fallbackResource != null && !fallbackResource.isBlank()) {
				challenge.resource = fallbackResource;
			}
			if (challenge != null) {
				challenges.put(serverId, challenge);
			}
		}
		config.openidRequired = true;
		if (challenge != null && challenge.resource != null && !challenge.resource.isBlank()) {
			config.openidResource = challenge.resource;
		}
		else if ((config.openidResource == null || config.openidResource.isBlank())
			&& fallbackResource != null && !fallbackResource.isBlank()) {
			config.openidResource = fallbackResource;
		}
		ensureDiscovery(config);
		repository.save(config);
	}

	public void recordAuthHeaders(String serverId, String headers) {
		if (serverId == null) {
			return;
		}
		if (headers == null) {
			lastAuthHeaders.remove(serverId);
		}
		else {
			lastAuthHeaders.put(serverId, headers);
		}
	}

    public AuthStatus status(String serverId) {
        ServerConfig config = repository.get(serverId).orElse(null);
        AuthStatus status = new AuthStatus();
        if (config == null) {
            status.error = "Unknown server";
            return status;
        }
		status.openidRequired = config.openidRequired;
		status.resource = config.openidResource;
		status.issuer = config.openidIssuer;
		status.authorizationEndpoint = config.oauthAuthorizationUrl;
		status.tokenEndpoint = config.oauthTokenUrl;
		status.scopes = config.oauthScopes;
		status.hasClient = config.oauthClientId != null && !config.oauthClientId.isBlank();
		OAuthToken token = tokens.get(serverId);
		status.hasToken = token != null && token.accessToken != null && !token.accessToken.isBlank();
		JsonNode metadata = config.openidMetadata;
        String registrationEndpoint = metadata != null ? textValue(metadata.get("registration_endpoint")) : null;
        status.needsClientConfig = status.openidRequired && !status.hasClient && (registrationEndpoint == null || registrationEndpoint.isBlank());
		AuthChallenge challenge = challenges.get(serverId);
		if (challenge != null) {
			status.challengeResource = challenge.resource;
			status.challengeScope = challenge.scope;
			status.challengeError = challenge.error;
			status.challengeErrorDescription = challenge.errorDescription;
		}
		status.lastAuthHeaders = lastAuthHeaders.get(serverId);
		return status;
	}

    public LoginResponse beginLogin(String serverId) {
        ServerConfig config = repository.get(serverId).orElse(null);
        LoginResponse response = new LoginResponse();
        if (config == null) {
            response.error = "Unknown server";
            return response;
        }
        if (!config.openidRequired) {
            response.error = "OpenID Connect not enabled for this server";
            return response;
        }
        ensureDiscovery(config);
        JsonNode metadata = config.openidMetadata;
        if (metadata == null || metadata.isNull()) {
            response.error = "OpenID metadata unavailable";
            return response;
        }
		String authorizationEndpoint = textValue(metadata.get("authorization_endpoint"));
		String tokenEndpoint = textValue(metadata.get("token_endpoint"));
		if (authorizationEndpoint == null) {
			authorizationEndpoint = config.oauthAuthorizationUrl;
		}
		if (tokenEndpoint == null) {
			tokenEndpoint = config.oauthTokenUrl;
		}
		if (authorizationEndpoint == null || tokenEndpoint == null) {
			response.error = "Authorization server endpoints unavailable";
			return response;
		}
        if (config.oauthClientId == null || config.oauthClientId.isBlank()) {
            boolean registered = registerClientIfPossible(config, metadata);
            if (!registered) {
                response.needsClientConfig = true;
                response.error = "Client registration required";
                repository.save(config);
                return response;
            }
        }
        String state = UUID.randomUUID().toString();
        String verifier = generateCodeVerifier();
        String challenge = sha256Base64Url(verifier);
        OAuthState authState = new OAuthState();
        authState.state = state;
        authState.codeVerifier = verifier;
        authState.createdAt = Instant.now();
        AuthChallenge lastChallenge = challenges.get(serverId);
        if (lastChallenge != null) {
            authState.scope = lastChallenge.scope;
        }
        authStates.put(serverId, authState);
        String redirectUri = callbackUri(serverId);
		StringBuilder scope = new StringBuilder();
		boolean hasConfiguredScopes = config.oauthScopes != null && !config.oauthScopes.isBlank();
		if (hasConfiguredScopes) {
			scope.append(config.oauthScopes.trim());
		}
		else if (config.openidMetadata != null && !config.openidMetadata.isNull()) {
			scope.append("openid");
		}
		if (authState.scope != null && !authState.scope.isBlank()) {
			if (scope.length() > 0) {
				scope.append(" ");
			}
			scope.append(authState.scope.trim());
		}
		boolean supportsOfflineAccess = !hasConfiguredScopes
			&& config.openidMetadata != null
			&& hasScope(config.openidMetadata, "offline_access");
		if (supportsOfflineAccess && !scope.toString().contains("offline_access")) {
			if (scope.length() > 0) {
				scope.append(" ");
			}
			scope.append("offline_access");
		}
		String url = authorizationEndpoint
			+ "?response_type=code"
			+ "&client_id=" + urlEncode(config.oauthClientId)
			+ "&redirect_uri=" + urlEncode(redirectUri)
			+ "&state=" + urlEncode(state)
			+ "&code_challenge=" + urlEncode(challenge)
			+ "&code_challenge_method=S256";
		if (scope.length() > 0) {
			url += "&scope=" + urlEncode(scope.toString());
		}
        if (config.openidResource != null && !config.openidResource.isBlank()) {
            url += "&resource=" + urlEncode(config.openidResource);
        }
        response.url = url;
        repository.save(config);
        return response;
    }

    public CallbackResult handleCallback(String serverId, String code, String state) {
        CallbackResult result = new CallbackResult();
        ServerConfig config = repository.get(serverId).orElse(null);
        if (config == null) {
            result.error = "Unknown server";
            return result;
        }
        OAuthState stored = authStates.get(serverId);
        if (stored == null || stored.state == null || !stored.state.equals(state)) {
            result.error = "Invalid OAuth state";
            return result;
        }
        ensureDiscovery(config);
        JsonNode metadata = config.openidMetadata;
        if (metadata == null || metadata.isNull()) {
            result.error = "OpenID metadata unavailable";
            return result;
        }
		String tokenEndpoint = textValue(metadata.get("token_endpoint"));
		if (tokenEndpoint == null) {
			tokenEndpoint = config.oauthTokenUrl;
		}
		if (tokenEndpoint == null) {
			result.error = "Token endpoint unavailable";
			return result;
		}
        String redirectUri = callbackUri(serverId);
        StringBuilder body = new StringBuilder();
        body.append("grant_type=authorization_code");
        body.append("&code=").append(urlEncode(code));
        body.append("&redirect_uri=").append(urlEncode(redirectUri));
        body.append("&client_id=").append(urlEncode(config.oauthClientId));
        body.append("&code_verifier=").append(urlEncode(stored.codeVerifier));
        if (config.oauthClientSecret != null && !config.oauthClientSecret.isBlank()) {
            body.append("&client_secret=").append(urlEncode(config.oauthClientSecret));
        }
        JsonNode tokenJson = postForm(tokenEndpoint, body.toString());
        if (tokenJson == null || tokenJson.isNull()) {
            result.error = "Token exchange failed";
            return result;
        }
        OAuthToken token = new OAuthToken();
        token.accessToken = textValue(tokenJson.get("access_token"));
        token.refreshToken = textValue(tokenJson.get("refresh_token"));
        token.tokenType = normalizeTokenType(textValue(tokenJson.get("token_type")));
        long expiresIn = tokenJson.has("expires_in") ? tokenJson.get("expires_in").asLong(0) : 0;
        if (expiresIn > 0) {
            token.expiresAt = Instant.now().plusSeconds(expiresIn);
        }
        tokens.put(serverId, token);
        authStates.remove(serverId);
        result.success = true;
        return result;
    }

    public void logout(String serverId) {
        tokens.remove(serverId);
        authStates.remove(serverId);
    }

    public Optional<String> getAuthorizationHeader(String serverId) {
        OAuthToken token = tokens.get(serverId);
        if (token == null || token.accessToken == null || token.accessToken.isBlank()) {
            return Optional.empty();
        }
        if (token.expiresAt != null && token.expiresAt.isBefore(Instant.now().plus(TOKEN_SKEW))) {
            boolean refreshed = refreshToken(serverId, token);
            if (!refreshed) {
                return Optional.empty();
            }
        }
        String type = normalizeTokenType(token.tokenType);
        return Optional.of(type + " " + token.accessToken);
    }

    private boolean refreshToken(String serverId, OAuthToken token) {
        ServerConfig config = repository.get(serverId).orElse(null);
        if (config == null || token.refreshToken == null || token.refreshToken.isBlank()) {
            return false;
        }
        ensureDiscovery(config);
        JsonNode metadata = config.openidMetadata;
        if (metadata == null || metadata.isNull()) {
            return false;
        }
		String tokenEndpoint = textValue(metadata.get("token_endpoint"));
		if (tokenEndpoint == null) {
			tokenEndpoint = config.oauthTokenUrl;
		}
		if (tokenEndpoint == null) {
			return false;
		}
        StringBuilder body = new StringBuilder();
        body.append("grant_type=refresh_token");
        body.append("&refresh_token=").append(urlEncode(token.refreshToken));
        body.append("&client_id=").append(urlEncode(config.oauthClientId));
        if (config.oauthClientSecret != null && !config.oauthClientSecret.isBlank()) {
            body.append("&client_secret=").append(urlEncode(config.oauthClientSecret));
        }
        JsonNode tokenJson = postForm(tokenEndpoint, body.toString());
        if (tokenJson == null || tokenJson.isNull()) {
            return false;
        }
        String accessToken = textValue(tokenJson.get("access_token"));
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        token.accessToken = accessToken;
        String refreshToken = textValue(tokenJson.get("refresh_token"));
        if (refreshToken != null && !refreshToken.isBlank()) {
            token.refreshToken = refreshToken;
        }
        token.tokenType = normalizeTokenType(textValue(tokenJson.get("token_type")));
        long expiresIn = tokenJson.has("expires_in") ? tokenJson.get("expires_in").asLong(0) : 0;
        if (expiresIn > 0) {
            token.expiresAt = Instant.now().plusSeconds(expiresIn);
        }
        tokens.put(serverId, token);
        return true;
    }

    private boolean registerClientIfPossible(ServerConfig config, JsonNode metadata) {
        String registrationEndpoint = textValue(metadata.get("registration_endpoint"));
        if (registrationEndpoint == null || registrationEndpoint.isBlank()) {
            return false;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("client_name", "mcp-test");
        body.putArray("redirect_uris").add(callbackUri(config.id));
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");
        body.put("token_endpoint_auth_method", "client_secret_post");
        JsonNode response = postJson(registrationEndpoint, body);
        if (response == null || response.isNull()) {
            return false;
        }
        String clientId = textValue(response.get("client_id"));
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        config.oauthClientId = clientId;
        config.oauthClientSecret = textValue(response.get("client_secret"));
        config.oauthClientMetadata = response;
        return true;
    }

	private void ensureDiscovery(ServerConfig config) {
        if (config == null) {
            return;
        }
        if (config.openidMetadata != null && !config.openidMetadata.isNull()) {
            return;
        }
		String resource = config.openidResource;
		if (resource == null || resource.isBlank()) {
			return;
		}
        String base = toOrigin(resource);
        if (base == null) {
            return;
        }
        JsonNode metadata = getJson(base + "/.well-known/openid-configuration");
        if (metadata == null || metadata.isNull()) {
            metadata = getJson(base + "/.well-known/oauth-authorization-server");
        }
		if (metadata != null && !metadata.isNull()) {
			config.openidMetadata = metadata;
			config.openidIssuer = textValue(metadata.get("issuer"));
			String authorizationEndpoint = textValue(metadata.get("authorization_endpoint"));
			if (authorizationEndpoint != null) {
				config.oauthAuthorizationUrl = authorizationEndpoint;
			}
			String tokenEndpoint = textValue(metadata.get("token_endpoint"));
			if (tokenEndpoint != null) {
				config.oauthTokenUrl = tokenEndpoint;
			}
		}
	}

    private JsonNode getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .header("Accept", "application/json")
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return null;
            }
            return mapper.readTree(response.body());
        }
        catch (Exception e) {
            LOGGER.debugf("Failed to fetch metadata %s: %s", url, e.getMessage());
            return null;
        }
    }

    private JsonNode postJson(String url, JsonNode body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return null;
            }
            return mapper.readTree(response.body());
        }
        catch (Exception e) {
            LOGGER.debugf("Failed POST %s: %s", url, e.getMessage());
            return null;
        }
    }

    private JsonNode postForm(String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return null;
            }
            return mapper.readTree(response.body());
        }
        catch (Exception e) {
            LOGGER.debugf("Failed form POST %s: %s", url, e.getMessage());
            return null;
        }
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

	private static boolean hasScope(JsonNode metadata, String scope) {
		if (metadata == null || metadata.isNull() || scope == null || scope.isBlank()) {
			return false;
		}
		JsonNode supported = metadata.get("scopes_supported");
		if (supported == null || !supported.isArray()) {
			return false;
		}
		for (JsonNode entry : supported) {
			if (scope.equals(entry.asText())) {
				return true;
			}
		}
		return false;
	}

    private static String callbackUri(String serverId) {
        return "http://localhost:8080/servers/" + serverId + "/auth/callback";
    }

    private static String toOrigin(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getAuthority();
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        UUID uuid = UUID.randomUUID();
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (hi >>> (56 - (i * 8)));
            bytes[i + 8] = (byte) (lo >>> (56 - (i * 8)));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Base64Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to hash code verifier", e);
        }
    }

    private static String normalizeTokenType(String tokenType) {
        if (tokenType == null || tokenType.isBlank()) {
            return "Bearer";
        }
        if ("bearer".equalsIgnoreCase(tokenType)) {
            return "Bearer";
        }
        return tokenType.trim();
    }

	public static class AuthStatus {
		public boolean openidRequired;
		public boolean hasClient;
		public boolean hasToken;
		public boolean needsClientConfig;
		public String resource;
		public String issuer;
		public String authorizationEndpoint;
		public String tokenEndpoint;
		public String scopes;
		public String challengeResource;
		public String challengeScope;
		public String challengeError;
		public String challengeErrorDescription;
		public String lastAuthHeaders;
		public String error;
	}

    public static class LoginResponse {
        public String url;
        public boolean needsClientConfig;
        public String error;
    }

    public static class CallbackResult {
        public boolean success;
        public String error;
    }

    private static class OAuthState {
        String state;
        String codeVerifier;
        Instant createdAt;
        String scope;
    }

    private static class OAuthToken {
        String accessToken;
        String refreshToken;
        String tokenType;
        Instant expiresAt;
    }

    private static class AuthChallenge {
        String resource;
        String scope;
        String error;
        String errorDescription;

        static AuthChallenge parse(String header) {
            if (header == null) {
                return null;
            }
            String trimmed = header.trim();
            if (!trimmed.toLowerCase().startsWith("bearer")) {
                return null;
            }
            String params = trimmed.substring(6).trim();
            AuthChallenge challenge = new AuthChallenge();
            int index = 0;
            while (index < params.length()) {
                while (index < params.length() && (params.charAt(index) == ',' || Character.isWhitespace(params.charAt(index)))) {
                    index++;
                }
                int eq = params.indexOf('=', index);
                if (eq < 0) {
                    break;
                }
                String key = params.substring(index, eq).trim();
                index = eq + 1;
                String value;
                if (index < params.length() && params.charAt(index) == '"') {
                    index++;
                    int end = index;
                    StringBuilder sb = new StringBuilder();
                    while (end < params.length()) {
                        char ch = params.charAt(end);
                        if (ch == '"') {
                            break;
                        }
                        if (ch == '\\' && end + 1 < params.length()) {
                            end++;
                            sb.append(params.charAt(end));
                        }
                        else {
                            sb.append(ch);
                        }
                        end++;
                    }
                    value = sb.toString();
                    index = end + 1;
                }
                else {
                    int end = params.indexOf(',', index);
                    if (end < 0) {
                        end = params.length();
                    }
                    value = params.substring(index, end).trim();
                    index = end;
                }
                if ("resource".equalsIgnoreCase(key)) {
                    challenge.resource = value;
                }
                else if ("scope".equalsIgnoreCase(key)) {
                    challenge.scope = value;
                }
                else if ("error".equalsIgnoreCase(key)) {
                    challenge.error = value;
                }
                else if ("error_description".equalsIgnoreCase(key)) {
                    challenge.errorDescription = value;
                }
            }
            return challenge;
        }
    }
}
