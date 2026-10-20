package solutions.shapeit.wethrive.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.BootstrapRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.RegisterRequest;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.entity.AppUser;

@Testcontainers
@SpringBootTest(properties = {
        "wethrive.security.bootstrap-token=owner-setup-test-token",
        "wethrive.security.registration-mode=INVITE_ONLY",
        "wethrive.security.expose-account-tokens=true",
        "wethrive.notifications.mail-provider=SMTP",
        "wethrive.reminders.scheduler-enabled=false"
})
class ApplicationOwnerInitializationIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withDatabaseName("wethrive_owner").withUsername("wethrive").withPassword("test-password");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired AuthService auth;
    @Autowired ApplicationInvitationService invitations;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE TABLE app_users CASCADE");
        jdbc.execute("INSERT INTO application_initialization(id) VALUES (1)");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void simultaneousSetupCreatesExactlyOneOwnerAndCannotBeReopened() throws Exception {
        assertThat(auth.setupStatus().initialized()).isFalse();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> initializeAfter(start, "first@example.test"));
            var second = executor.submit(() -> initializeAfter(start, "second@example.test"));
            start.countDown();
            assertThat(java.util.List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(users.count()).isOne();
        var owner = users.findAll().getFirst();
        assertThat(owner.getApplicationRole()).isEqualTo("SUPER_ADMIN");
        assertThat(auth.setupStatus().initialized()).isTrue();
        assertThat(auth.setupStatus().bootstrapAvailable()).isFalse();
        assertThatThrownBy(() -> auth.deleteAccount(owner.getId(), "StrongOwnerPassword123"))
                .isInstanceOf(ApiException.class).hasMessageContaining("owner cannot delete");
        owner.setEnabled(false); users.saveAndFlush(owner);
        assertThatThrownBy(() -> auth.bootstrap(request("third@example.test", "owner-setup-test-token"), "test"))
                .isInstanceOf(ApiException.class).hasMessageContaining("already been completed");
    }

    @Test void setupRequiresTheSecretAndRegistrationWaitsForTheOwner() {
        assertThatThrownBy(() -> auth.bootstrap(request("owner@example.test", "wrong-token"), "test"))
                .isInstanceOf(ApiException.class).hasMessageContaining("credentials are invalid");
        assertThatThrownBy(() -> auth.register(new RegisterRequest("member@example.test", "StrongMemberPassword123", "Member", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("owner must complete setup");
        assertThat(users.count()).isZero();
    }

    @Test void setupStatusIsPublicButBootstrapStillRequiresCsrfAndAdministrationRequiresASession() throws Exception {
        mvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(false))
                .andExpect(jsonPath("$.bootstrapAvailable").value(true));
        mvc.perform(post("/api/v1/auth/bootstrap").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/invitations")).andExpect(status().isUnauthorized());
    }

    @Test void upgradeRequiresAnExistingOwnersPasswordBeforePromotingTheAccount() {
        AppUser existing = new AppUser();
        existing.setEmail("existing@example.test"); existing.setNormalizedEmail("existing@example.test");
        existing.setDisplayName("Existing User"); existing.setEmailVerified(true);
        existing.setPasswordHash(passwords.encode("StrongOwnerPassword123"));
        users.saveAndFlush(existing);
        assertThatThrownBy(() -> auth.bootstrap(new BootstrapRequest(existing.getEmail(), "IncorrectPassword123", "Owner",
                "owner-setup-test-token", UUID.randomUUID(), "Test"), "test"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Email or password is incorrect");
        assertThat(auth.setupStatus().initialized()).isFalse();
        assertThat(users.findById(existing.getId()).orElseThrow().getApplicationRole()).isEqualTo("USER");
        var owner = auth.bootstrap(request(existing.getEmail(), "owner-setup-test-token"), "test").response().user();
        assertThat(owner.id()).isEqualTo(existing.getId());
        assertThat(owner.applicationRole()).isEqualTo("SUPER_ADMIN");
        assertThat(users.count()).isOne();
    }

    @Test void applicationInvitationIsEmailBoundSingleUseRevocableAndDoesNotGrantAdminOrHouseholdAccess() {
        var owner = auth.bootstrap(request("owner@example.test", "owner-setup-test-token"), "test").response().user();
        var invitation = invitations.create(owner.id(), "member@example.test");
        assertThat(invitation.token()).isNotBlank();
        assertThatThrownBy(() -> auth.register(new RegisterRequest("other@example.test", "StrongMemberPassword123", "Other", null, invitation.token())))
                .isInstanceOf(ApiException.class).hasMessageContaining("valid invitation is required");
        var registration = auth.register(new RegisterRequest("member@example.test", "StrongMemberPassword123", "Member", null, invitation.token()));
        assertThat(registration.verificationToken()).isNotBlank();
        var member = users.findByNormalizedEmailAndDeletedAtIsNull("member@example.test").orElseThrow();
        assertThat(member.getApplicationRole()).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_memberships WHERE user_id = ?", Long.class, member.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spaces WHERE owner_user_id = ? AND type = 'HOUSEHOLD'", Long.class, member.getId())).isZero();
        assertThat(invitations.isUsableForEmail(invitation.token(), member.getEmail())).isFalse();
        assertThatThrownBy(() -> invitations.list(member.getId())).isInstanceOf(ApiException.class).hasMessageContaining("owner access");
        var revoked = invitations.create(owner.id(), "revoked@example.test");
        invitations.revoke(owner.id(), revoked.id());
        assertThat(invitations.isUsableForEmail(revoked.token(), "revoked@example.test")).isFalse();
        var expired = invitations.create(owner.id(), "expired@example.test");
        jdbc.update("UPDATE application_invitations SET expires_at = now() - interval '1 second' WHERE id = ?", expired.id());
        assertThat(invitations.isUsableForEmail(expired.token(), "expired@example.test")).isFalse();
    }

    private boolean initializeAfter(CountDownLatch start, String email) throws InterruptedException {
        start.await();
        try { auth.bootstrap(request(email, "owner-setup-test-token"), "test"); return true; }
        catch (ApiException expected) { return false; }
    }
    private BootstrapRequest request(String email, String token) {
        return new BootstrapRequest(email, "StrongOwnerPassword123", "Application Owner", token, UUID.randomUUID(), "Test browser");
    }
}
