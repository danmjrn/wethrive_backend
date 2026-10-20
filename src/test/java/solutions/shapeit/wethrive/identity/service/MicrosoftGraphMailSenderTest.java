package solutions.shapeit.wethrive.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import tools.jackson.databind.ObjectMapper;

class MicrosoftGraphMailSenderTest {
    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final String CLIENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String SECRET = "synthetic-credential+with&special=characters";
    private static final String TOKEN = "synthetic-access-token";
    private final HttpClient http = mock(HttpClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
    private final ApplicationProperties.Graph properties = new ApplicationProperties.Graph(
            TENANT, CLIENT, SECRET, "wethrive@example.com", Duration.ofSeconds(5), Duration.ofSeconds(15));
    private final MicrosoftGraphMailSender sender = new MicrosoftGraphMailSender(properties, http, mapper, clock);

    @Test
    void usesAppOnlyCredentialsAndSendsTheMailboxMessageAsJson() throws Exception {
        doReturn(token(TOKEN, 3600), response(202, "")).when(http).send(any(), any(HttpResponse.BodyHandler.class));

        sender.send("person@example.com", "Invitation \"to WeThrive\"", "Join: https://app.example.com/#invitationToken=secret");

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        var authentication = requests.getAllValues().get(0);
        assertThat(authentication.uri().toString()).isEqualTo("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token");
        assertThat(body(authentication)).contains("grant_type=client_credentials", "scope=https%3A%2F%2Fgraph.microsoft.com%2F.default",
                "client_secret=synthetic-credential%2Bwith%26special%3Dcharacters");
        var mail = requests.getAllValues().get(1);
        assertThat(mail.uri().toString()).isEqualTo("https://graph.microsoft.com/v1.0/users/wethrive%40example.com/sendMail");
        assertThat(mail.headers().firstValue("Authorization")).contains("Bearer " + TOKEN);
        assertThat(mail.timeout()).contains(Duration.ofSeconds(15));
        var json = mapper.readTree(body(mail));
        assertThat(json.path("saveToSentItems").asBoolean()).isTrue();
        assertThat(json.at("/message/subject").asString()).isEqualTo("Invitation \"to WeThrive\"");
        assertThat(json.at("/message/body/contentType").asString()).isEqualTo("Text");
        assertThat(json.at("/message/body/content").asString()).endsWith("#invitationToken=secret");
        assertThat(json.at("/message/toRecipients/0/emailAddress/address").asString()).isEqualTo("person@example.com");
    }

    @Test
    void reusesTokensThenRefreshesBeforeExpiry() throws Exception {
        doReturn(token(TOKEN, 3600), response(202, ""), response(202, ""), token("new-token", 3600), response(202, ""))
                .when(http).send(any(), any(HttpResponse.BodyHandler.class));
        sender.send("person@example.com", "First", "one");
        clock.advance(Duration.ofMinutes(58));
        sender.send("person@example.com", "Second", "two");
        clock.advance(Duration.ofMinutes(1));
        sender.send("person@example.com", "Third", "three");
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(5)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues()).filteredOn(request -> request.uri().getHost().equals("login.microsoftonline.com")).hasSize(2);
        assertThat(requests.getAllValues().get(4).headers().firstValue("Authorization")).contains("Bearer new-token");
    }

    @Test
    void evictsRejectedTokenAndDoesNotAutomaticallyRetryAMessage() throws Exception {
        doReturn(token(TOKEN, 3600), response(401, "sensitive-provider-response"), token("new-token", 3600), response(202, ""))
                .when(http).send(any(), any(HttpResponse.BodyHandler.class));
        assertThatThrownBy(() -> sender.send("person@example.com", "Test", "secret-body"))
                .hasMessage("Microsoft Graph mail send failed (HTTP 401)").hasNoCause();
        verify(http, times(2)).send(any(), any(HttpResponse.BodyHandler.class));
        sender.send("person@example.com", "Next user action", "body");
        verify(http, times(4)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void sanitizesAuthenticationErrorsAndMalformedResponses() throws Exception {
        doReturn(response(400, "sensitive-provider-response"),
                        response(200, "{\"access_token\":\"sensitive-token\",\"expires_in\":3600}"))
                .when(http).send(any(), any(HttpResponse.BodyHandler.class));
        assertThatThrownBy(() -> sender.send("person@example.com", "Test", "secret-body"))
                .hasMessage("Microsoft Graph mail authentication failed (HTTP 400)").hasNoCause();
        assertThatThrownBy(() -> sender.send("person@example.com", "Test", "secret-body"))
                .hasMessage("Microsoft Graph mail authentication returned an invalid response").hasNoCause();
        verify(http, times(2)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void doesNotRetryAnAmbiguousNetworkFailure() throws Exception {
        doReturn(token(TOKEN, 3600)).doThrow(new IOException("sensitive-provider-response"))
                .when(http).send(any(), any(HttpResponse.BodyHandler.class));
        assertThatThrownBy(() -> sender.send("person@example.com", "Test", "secret-body"))
                .hasMessage("Microsoft Graph mail send was unavailable").hasNoCause();
        verify(http, times(2)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void restoresTheThreadInterruptFlag() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> sender.send("person@example.com", "Test", "body"))
                    .hasMessage("Microsoft Graph mail authentication was interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void rejectsInvalidConfigurationBeforeSendingAnyRequest() {
        var invalid = new ApplicationProperties.Graph("tenant/path", CLIENT, SECRET, "wethrive@example.com", null, null);
        var invalidSender = new MicrosoftGraphMailSender(invalid, http, mapper, clock);
        assertThatThrownBy(() -> invalidSender.send("person@example.com", "Test", "body"))
                .hasMessageContaining("requires tenant ID");
        verifyNoInteractions(http);
    }

    @Test
    void configurationStringDoesNotExposeTheClientSecret() {
        assertThat(properties.toString()).doesNotContain(SECRET).contains("redacted");
    }

    private HttpResponse<String> token(String value, int expiresIn) {
        return response(200, "{\"access_token\":\"" + value + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String value) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(value);
        return response;
    }

    private static String body(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.join();
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        private void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
