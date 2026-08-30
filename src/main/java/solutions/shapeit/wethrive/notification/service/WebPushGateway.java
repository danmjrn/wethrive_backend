package solutions.shapeit.wethrive.notification.service;

import java.security.Security;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.notification.entity.PushSubscription;

@Component
public class WebPushGateway {
    public record DeliveryResult(int statusCode, boolean success, boolean permanentFailure) {}
    private final ApplicationProperties properties;
    private final SecretEncryptionService encryption;

    public WebPushGateway(ApplicationProperties properties, SecretEncryptionService encryption) {
        this.properties = properties; this.encryption = encryption;
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(new BouncyCastleProvider());
    }

    public DeliveryResult send(PushSubscription subscription, String payload) {
        if (!properties.notifications().webPushEnabled()) return new DeliveryResult(0, false, false);
        try {
            PushService push = new PushService(properties.notifications().vapidPublicKey(),
                    properties.notifications().vapidPrivateKey(), properties.notifications().vapidSubject());
            Notification notification = new Notification(encryption.decrypt(subscription.getEndpointEncrypted()),
                    encryption.decrypt(subscription.getPublicKeyEncrypted()),
                    encryption.decrypt(subscription.getAuthenticationSecretEncrypted()), payload);
            int status = push.send(notification).getStatusLine().getStatusCode();
            return new DeliveryResult(status, status >= 200 && status < 300,
                    status == 404 || status == 410 || (status >= 400 && status < 500 && status != 429));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(0, false, false);
        } catch (Exception ex) {
            return new DeliveryResult(0, false, false);
        }
    }
}
