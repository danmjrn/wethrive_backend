package solutions.shapeit.wethrive.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces a hard limit for JSON API bodies, including chunked requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApiRequestSizeFilter extends OncePerRequestFilter {
    private final long maximumBytes;

    public ApiRequestSizeFilter(@Value("${wethrive.security.max-api-request-bytes:8388608}") long maximumBytes) {
        if (maximumBytes < 1) throw new IllegalArgumentException("MAX_API_REQUEST_BYTES must be positive");
        this.maximumBytes = maximumBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/") || !mayHaveBody(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > maximumBytes) {
            reject(response);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request, maximumBytes), response);
        } catch (RequestBodyTooLargeException ex) {
            if (!response.isCommitted()) reject(response);
        }
    }

    private boolean mayHaveBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("{\"code\":\"payload_too_large\",\"message\":\"The request body is too large\"}");
    }

    public static final class RequestBodyTooLargeException extends IOException {
        public RequestBodyTooLargeException() { super("API request body exceeds the configured limit"); }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maximumBytes;
        private LimitedRequest(HttpServletRequest request, long maximumBytes) {
            super(request);
            this.maximumBytes = maximumBytes;
        }
        @Override public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(), maximumBytes);
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maximumBytes;
        private long bytesRead;
        private LimitedInputStream(ServletInputStream delegate, long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }
        @Override public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) counted(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) counted(count);
            return count;
        }
        private void counted(int count) throws RequestBodyTooLargeException {
            bytesRead += count;
            if (bytesRead > maximumBytes) throw new RequestBodyTooLargeException();
        }
        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }
}
