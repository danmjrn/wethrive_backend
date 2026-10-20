package solutions.shapeit.wethrive.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import solutions.shapeit.wethrive.TestProperties;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;

class AccountNotificationServiceTest {
    @Test
    void actionSecretsUseFragmentsAndConfiguredFromAddress() {
        JavaMailSender sender = mock(JavaMailSender.class);
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        AccountNotificationService service = new AccountNotificationService(provider, TestProperties.create());
        service.sendVerification("person@example.test", "verify-secret");
        service.sendPasswordReset("person@example.test", "reset-secret");
        service.sendInvitation("person@example.test", "invite-secret");
        service.sendApplicationInvitation("person@example.test", "application-invite-secret");
        ArgumentCaptor<SimpleMailMessage> messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, org.mockito.Mockito.times(4)).send(messages.capture());
        assertThat(messages.getAllValues()).extracting(SimpleMailMessage::getText)
                .containsExactly(
                        "Verify your account: http://localhost:3000/#verificationToken=verify-secret",
                        "Reset your password: http://localhost:3000/#resetToken=reset-secret",
                        "Accept your invitation: http://localhost:3000/#invitationToken=invite-secret",
                        "Create your account: http://localhost:3000/#applicationInvitationToken=application-invite-secret");
        assertThat(messages.getAllValues()).allSatisfy(message -> assertThat(message.getFrom()).isEqualTo("test@example.com"));
    }

    @Test
    void selectsGraphWithoutConsultingTheSmtpProvider() {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        var base = TestProperties.create();
        var notifications = base.notifications();
        var graphProperties = new ApplicationProperties(base.branding(), base.security(), base.offline(),
                new ApplicationProperties.Notifications(notifications.webPushEnabled(), notifications.vapidPublicKey(),
                        notifications.vapidPrivateKey(), notifications.vapidSubject(), notifications.mailFrom(), "GRAPH", null),
                base.reminders());
        MicrosoftGraphMailSender graph = mock(MicrosoftGraphMailSender.class);
        AccountNotificationService service = new AccountNotificationService(provider, graphProperties, graph);

        service.sendApplicationInvitation("person@example.test", "application-invite-secret");

        verify(graph).send("person@example.test", "You have been invited to WeThrive",
                "Create your account: http://localhost:3000/#applicationInvitationToken=application-invite-secret");
        verifyNoInteractions(provider);
    }
}
