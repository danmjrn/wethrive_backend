package solutions.shapeit.wethrive.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.web.ApiException;

@Service
public class AccountNotificationService {
    private static final Logger log = LoggerFactory.getLogger(AccountNotificationService.class);
    private final ObjectProvider<JavaMailSender> mailSender;
    private final ApplicationProperties properties;

    public AccountNotificationService(ObjectProvider<JavaMailSender> mailSender, ApplicationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void sendVerification(String email, String token) {
        send(email, "Verify your " + properties.branding().name() + " account",
                "Verify your account: " + actionUrl("verificationToken", token), "verification", true);
    }

    public void sendPasswordReset(String email, String token) {
        send(email, "Reset your " + properties.branding().name() + " password",
                "Reset your password: " + actionUrl("resetToken", token), "password_reset", false);
    }

    public void sendInvitation(String email, String token) {
        send(email, "You have been invited to " + properties.branding().name(),
                "Accept your invitation: " + actionUrl("invitationToken", token), "invitation", true);
    }

    private String actionUrl(String action, String token) {
        String base = properties.branding().baseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        // Fragments are never sent to an HTTP server or reverse-proxy access log.
        return base + "/#" + action + "=" + token;
    }

    private void send(String email, String subject, String text, String type, boolean required) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("account_email_not_sent reason=mail_not_configured type={}", type);
            if (required && !properties.security().exposeAccountTokens()) throw unavailable();
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            if (properties.notifications().mailFrom() != null && !properties.notifications().mailFrom().isBlank()) {
                message.setFrom(properties.notifications().mailFrom().trim());
            }
            message.setSubject(subject);
            message.setText(text);
            sender.send(message);
        } catch (RuntimeException ex) {
            log.warn("account_email_not_sent reason=delivery_failure type={}", type);
            if (required && !properties.security().exposeAccountTokens()) throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "email_delivery_unavailable",
                "Email delivery is temporarily unavailable; no account or invitation change was committed");
    }
}
