package solutions.shapeit.wethrive.device.service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineGrantResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineGrantVerificationResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineVerificationKeyResponse;
import solutions.shapeit.wethrive.device.dto.DeviceDtos.OfflineSpaceAuthorization;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OfflineGrantService {
    private static final String ALGORITHM = "ES256";
    private static final String TYPE = "WT-OFFLINE-GRANT";
    private static final String CURVE = "P-256";
    private static final int GRANT_VERSION = 2;

    private final ApplicationProperties properties;
    private final SpaceMembershipRepository memberships;
    private final DeviceRepository devices;
    private final SpaceAccessService access;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public OfflineGrantService(ApplicationProperties properties, SpaceMembershipRepository memberships,
                               DeviceRepository devices, SpaceAccessService access, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.memberships = memberships;
        this.devices = devices;
        this.access = access;
        this.mapper = mapper;
        this.clock = clock;
        KeyPair pair = loadOrGenerate(properties.security().offlineSigningPrivateKey(),
                properties.security().offlineVerificationPublicKey());
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        verifyKeyPair();
    }

    public OfflineGrantResponse issue(UUID userId, UUID deviceId) {
        Instant issuedAt = Instant.now(clock);
        int days = Math.min(90, Math.max(1, properties.offline().grantDays()));
        Instant expiresAt = issuedAt.plus(days, ChronoUnit.DAYS);
        Authorization authorization = authorization(userId);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ALGORITHM);
        header.put("typ", TYPE);
        header.put("kid", keyId());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("userId", userId.toString());
        claims.put("deviceId", deviceId.toString());
        claims.put("issuedAt", issuedAt.toString());
        claims.put("expiresAt", expiresAt.toString());
        claims.put("permissionVersion", authorization.permissionVersion());
        claims.put("authorizationFingerprint", authorization.fingerprint());
        claims.put("authorizedSpaceIds", authorization.spaceIds().stream().map(UUID::toString).toList());
        claims.put("spaceAuthorizations", authorization.entries());
        claims.put("grantVersion", GRANT_VERSION);

        String encodedHeader = encodeJson(header);
        String encodedClaims = encodeJson(claims);
        String signingInput = encodedHeader + "." + encodedClaims;
        String grant = signingInput + "." + encode(sign(signingInput.getBytes(StandardCharsets.US_ASCII)));
        return new OfflineGrantResponse(grant, ALGORITHM, keyId(), userId, deviceId, issuedAt, expiresAt,
                authorization.permissionVersion(), authorization.fingerprint(), authorization.spaceIds(),
                authorization.entries(), GRANT_VERSION);
    }

    public OfflineVerificationKeyResponse verificationKey() {
        return new OfflineVerificationKeyResponse(ALGORITHM, CURVE, "spki", keyId(),
                encode(publicKey.getEncoded()), GRANT_VERSION);
    }

    public OfflineGrantVerificationResponse verify(UUID actorId, UUID expectedDeviceId, String grant) {
        try {
            String[] parts = grant.split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) throw invalid();
            JsonNode header = mapper.readTree(decode(parts[0]));
            if (!ALGORITHM.equals(text(header, "alg")) || !TYPE.equals(text(header, "typ"))
                    || !keyId().equals(text(header, "kid"))) throw invalid();
            byte[] signature = decode(parts[2]);
            if (signature.length != 64 || !verifySignature((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), signature)) {
                throw invalid();
            }
            JsonNode claims = mapper.readTree(decode(parts[1]));
            UUID userId = UUID.fromString(text(claims, "userId"));
            UUID deviceId = UUID.fromString(text(claims, "deviceId"));
            Instant issuedAt = Instant.parse(text(claims, "issuedAt"));
            Instant expiresAt = Instant.parse(text(claims, "expiresAt"));
            long permissionVersion = claims.path("permissionVersion").asLong(-1);
            String authorizationFingerprint = text(claims, "authorizationFingerprint");
            int grantVersion = claims.path("grantVersion").asInt(-1);
            List<UUID> parsedSpaces = new java.util.ArrayList<>();
            claims.path("authorizedSpaceIds").forEach(value -> parsedSpaces.add(UUID.fromString(value.asString())));
            List<UUID> claimedSpaces = parsedSpaces.stream().sorted().toList();

            Instant now = Instant.now(clock);
            if (!actorId.equals(userId) || !expectedDeviceId.equals(deviceId) || grantVersion != GRANT_VERSION
                    || issuedAt.isAfter(now.plusSeconds(300)) || !expiresAt.isAfter(now)) throw invalid();
            var device = devices.findByIdAndUserIdAndRevokedAtIsNull(deviceId, userId).orElseThrow(this::invalid);
            if (!device.isOfflineAccessEnabled() || device.getOfflineGrantExpiresAt() == null
                    || !device.getOfflineGrantExpiresAt().isAfter(now)) throw invalid();
            Authorization current = authorization(userId);
            if (permissionVersion != current.permissionVersion()
                    || !authorizationFingerprint.equals(current.fingerprint())
                    || !claimedSpaces.equals(current.spaceIds())) throw invalid();
            return new OfflineGrantVerificationResponse(true, ALGORITHM, keyId(), userId, deviceId, issuedAt,
                    expiresAt, permissionVersion, authorizationFingerprint, claimedSpaces, current.entries(), grantVersion);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private Authorization authorization(UUID userId) {
        List<SpaceMembership> authorized = memberships
                .findAllByUserIdAndStatusAndDeletedAtIsNull(userId, MembershipStatus.ACTIVE).stream()
                .sorted(java.util.Comparator.comparing(member -> member.getSpaceId().toString())).toList();
        List<UUID> spaces = authorized.stream().map(SpaceMembership::getSpaceId).sorted().toList();
        long permissionVersion = authorized.stream().mapToLong(member -> member.getVersion() + 1L).sum();
        List<OfflineSpaceAuthorization> entries = authorized.stream().map(member -> new OfflineSpaceAuthorization(
                member.getSpaceId(), member.getRole(), member.getVersion(), access.capabilities(member.getRole()))).toList();
        String canonical = entries.stream().map(entry -> entry.spaceId() + "|" + entry.role() + "|"
                + entry.membershipVersion() + "|" + String.join(",", entry.capabilities()))
                .collect(java.util.stream.Collectors.joining(";"));
        return new Authorization(spaces, permissionVersion, sha256(canonical), entries);
    }

    public long authorizationVersion(UUID userId) { return authorization(userId).permissionVersion(); }
    public String authorizationFingerprint(UUID userId) { return authorization(userId).fingerprint(); }

    private KeyPair loadOrGenerate(String encodedPrivate, String encodedPublic) {
        try {
            boolean noPrivate = encodedPrivate == null || encodedPrivate.isBlank();
            boolean noPublic = encodedPublic == null || encodedPublic.isBlank();
            if (noPrivate != noPublic) throw new IllegalStateException("Both offline signing and verification keys must be configured together");
            if (noPrivate) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                return generator.generateKeyPair();
            }
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey loadedPrivate = factory.generatePrivate(new PKCS8EncodedKeySpec(decodeKey(encodedPrivate)));
            PublicKey loadedPublic = factory.generatePublic(new X509EncodedKeySpec(decodeKey(encodedPublic)));
            return new KeyPair(loadedPublic, loadedPrivate);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Offline ES256 keys are invalid; use PKCS#8 private and SPKI public DER", ex);
        }
    }

    private byte[] decodeKey(String value) {
        String cleaned = value.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "").replaceAll("\\s", "");
        try { return Base64.getUrlDecoder().decode(cleaned); }
        catch (IllegalArgumentException ex) { return Base64.getDecoder().decode(cleaned); }
    }

    private void verifyKeyPair() {
        byte[] probe = "wethrive-offline-key-check".getBytes(StandardCharsets.US_ASCII);
        if (!verifySignature(probe, sign(probe))) throw new IllegalStateException("Offline signing private key does not match the verification public key");
    }

    private byte[] sign(byte[] input) {
        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(privateKey);
            signer.update(input);
            return signer.sign();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign an offline grant", ex);
        }
    }

    private boolean verifySignature(byte[] input, byte[] signatureBytes) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(publicKey);
            verifier.update(input);
            return verifier.verify(signatureBytes);
        } catch (Exception ex) {
            return false;
        }
    }

    private String encodeJson(Object value) {
        try { return encode(mapper.writeValueAsBytes(value)); }
        catch (Exception ex) { throw new IllegalStateException("Unable to serialize an offline grant", ex); }
    }
    private String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) throw invalid();
        return value.asString();
    }
    private String keyId() {
        String value = properties.security().offlineKeyId();
        return value == null || value.isBlank() ? "offline-v1" : value.trim();
    }
    private ApiException invalid() { return ApiException.badRequest("The offline grant is invalid, expired, revoked, or no longer authorized"); }
    private String sha256(String value) {
        try { return encode(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
    private record Authorization(List<UUID> spaceIds, long permissionVersion, String fingerprint,
                                 List<OfflineSpaceAuthorization> entries) {}
}
