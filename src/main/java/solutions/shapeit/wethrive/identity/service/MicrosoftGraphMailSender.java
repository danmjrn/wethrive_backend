package solutions.shapeit.wethrive.identity.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import tools.jackson.databind.ObjectMapper;

/** App-only Microsoft Graph mail; neither credentials nor provider response bodies are logged. */
public final class MicrosoftGraphMailSender {
    private final ApplicationProperties.Graph properties;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private CachedToken cachedToken;

    public MicrosoftGraphMailSender(ApplicationProperties.Graph properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper(), Clock.systemUTC());
    }

    MicrosoftGraphMailSender(ApplicationProperties.Graph properties, HttpClient client, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void send(String recipient, String subject, String text) {
        validateConfiguration();
        String token = accessToken();
        String body = mapper.writeValueAsString(Map.of(
                "message", Map.of("subject", subject,
                        "body", Map.of("contentType", "Text", "content", text),
                        "toRecipients", List.of(Map.of("emailAddress", Map.of("address", recipient)))),
                "saveToSentItems", true));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://graph.microsoft.com/v1.0/users/"
                        + encode(properties.sender()) + "/sendMail"))
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = execute(request, "send");
        if (response.statusCode() == 401) invalidate(token);
        // 202 confirms acceptance, not final delivery. Never automatically retry a send:
        // a network timeout might occur after Exchange has accepted it.
        if (response.statusCode() != 202) throw failed("send", response.statusCode());
    }

    private synchronized String accessToken() {
        if (cachedToken != null && clock.instant().isBefore(cachedToken.refreshAt())) return cachedToken.value();
        String form = "client_id=" + encode(properties.clientId())
                + "&scope=" + encode("https://graph.microsoft.com/.default")
                + "&client_secret=" + encode(properties.clientSecret())
                + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/"
                        + properties.tenantId() + "/oauth2/v2.0/token"))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = execute(request, "authentication");
        if (response.statusCode() != 200) throw failed("authentication", response.statusCode());
        try {
            var json = mapper.readTree(response.body());
            String token = json.path("access_token").asString("");
            long expiresIn = json.path("expires_in").asLong(0);
            if (token.isBlank() || token.length() > 32_768 || expiresIn < 1 || expiresIn > 86_400
                    || !"Bearer".equalsIgnoreCase(json.path("token_type").asString(""))) {
                throw new IllegalStateException("Invalid token response");
            }
            long skew = Math.min(60, Math.max(1, expiresIn / 10));
            cachedToken = new CachedToken(token, clock.instant().plusSeconds(expiresIn - skew));
            return token;
        } catch (RuntimeException ex) {
            // A malformed response can contain sensitive material. Do not retain its cause.
            throw new IllegalStateException("Microsoft Graph mail authentication returned an invalid response");
        }
    }

    private synchronized void invalidate(String rejectedToken) {
        if (cachedToken != null && cachedToken.value().equals(rejectedToken)) cachedToken = null;
    }

    private HttpResponse<String> execute(HttpRequest request, String operation) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Microsoft Graph mail " + operation + " was interrupted");
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Microsoft Graph mail " + operation + " was unavailable");
        }
    }

    private void validateConfiguration() {
        try {
            UUID.fromString(properties.tenantId());
            UUID.fromString(properties.clientId());
            if (properties.clientSecret() == null || properties.clientSecret().isBlank()
                    || properties.sender() == null || !properties.sender().matches("[^\\s<>@/]+@[^\\s<>@/]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Microsoft Graph mail requires tenant ID, client ID, client secret, and sender mailbox");
        }
    }

    private IllegalStateException failed(String operation, int status) {
        return new IllegalStateException("Microsoft Graph mail " + operation + " failed (HTTP " + status + ")");
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record CachedToken(String value, Instant refreshAt) {
        @Override public String toString() { return "CachedToken[redacted]"; }
    }
}
