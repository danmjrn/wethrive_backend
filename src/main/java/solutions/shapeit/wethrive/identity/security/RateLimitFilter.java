package solutions.shapeit.wethrive.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
    private record Window(long minute, int count) {}
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final List<String> trustedProxyCidrs;
    private final int authLimit;
    private final int apiLimit;

    public RateLimitFilter(Clock clock, ApplicationProperties properties) {
        this.clock = clock;
        this.trustedProxyCidrs = properties.security().trustedProxyCidrs() == null
                ? List.of() : List.copyOf(properties.security().trustedProxyCidrs());
        this.authLimit = positive("AUTH_RATE_LIMIT_PER_MINUTE", properties.security().authRateLimitPerMinute());
        this.apiLimit = positive("API_RATE_LIMIT_PER_MINUTE", properties.security().apiRateLimitPerMinute());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        boolean sensitive = request.getRequestURI().matches(".*/auth/(login|register|refresh|forgot-password|reset-password|verify-email|bootstrap).*")
                || request.getRequestURI().matches(".*/invitations/[^/]+/(accept|decline).*");
        int limit = sensitive ? authLimit : apiLimit;
        long minute = clock.instant().getEpochSecond() / 60;
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().minute < minute - 2);
        }
        String candidateKey = clientAddress(request) + (sensitive ? ":auth" : ":api");
        if (windows.size() >= 20_000 && !windows.containsKey(candidateKey)) {
            candidateKey = "overflow-" + Math.floorMod(candidateKey.hashCode(), 256) + (sensitive ? ":auth" : ":api");
        }
        String key = candidateKey;
        Window window = windows.compute(key, (ignored, current) -> current == null || current.minute != minute
                ? new Window(minute, 1) : new Window(minute, current.count + 1));
        if (window.count > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"rate_limited\",\"message\":\"Too many requests; try again shortly\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    String clientAddress(HttpServletRequest request) {
        String remote = canonical(request.getRemoteAddr());
        if (remote == null || !trusted(remote)) return remote == null ? "unknown" : remote;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remote;
        String candidate = remote;
        String[] hops = forwarded.split(",");
        for (int index = hops.length - 1; index >= 0 && trusted(candidate); index--) {
            String parsed = canonical(hops[index].trim());
            if (parsed == null) return remote;
            candidate = parsed;
        }
        return candidate;
    }

    private boolean trusted(String address) {
        for (String cidr : trustedProxyCidrs) if (contains(cidr, address)) return true;
        return false;
    }

    private boolean contains(String cidr, String address) {
        try {
            String[] parts = cidr.trim().split("/", 2);
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] candidate = InetAddress.getByName(address).getAddress();
            if (network.length != candidate.length) return false;
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
            if (prefix < 0 || prefix > network.length * 8) return false;
            for (int i = 0; i < network.length; i++) {
                int bits = Math.min(8, Math.max(0, prefix - i * 8));
                int mask = bits == 0 ? 0 : (0xff << (8 - bits)) & 0xff;
                if ((network[i] & mask) != (candidate[i] & mask)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String canonical(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || !value.matches("[0-9A-Fa-f:.]+")) return null;
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (Exception ignored) { return null; }
    }

    private int positive(String name, int value) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
