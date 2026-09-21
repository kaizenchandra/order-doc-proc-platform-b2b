package com.synechisveltiosi.platform.order.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Bounds metadata before JSON parsing or business effects, including chunked and trailing bytes. */
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES = 64 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) && !"PATCH".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            reject(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            reject(response);
            return;
        }
        // These controllers use blocking Servlet MVC. Never register this filter before authorization.
        var stream = new ByteArrayInputStream(body);
        var input = new ServletInputStream() {
            @Override public int read() { return stream.read(); }
            @Override public int read(byte[] bytes, int offset, int length) { return stream.read(bytes, offset, length); }
            @Override public boolean isFinished() { return stream.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                throw new IllegalStateException("Asynchronous request-body reads are not supported");
            }
        };
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() { return input; }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        ProblemResponses.write(response, 413, "Payload Too Large", "Request metadata exceeds 64 KiB");
    }
}
