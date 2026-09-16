package com.synechisveltiosi.platform.order.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = CorrelationFilter.class.getName();
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Correlation-ID");
        UUID id;
        try {
            id = supplied == null ? UUID.randomUUID() : UUID.fromString(supplied);
            if (supplied != null && !id.toString().equalsIgnoreCase(supplied)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            ProblemResponses.write(response, 400, "Bad Request", "X-Correlation-ID must be a UUID");
            return;
        }
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Correlation-ID", id.toString());
        response.setHeader("Cache-Control", "no-store");
        try (var ignored = com.synechisveltiosi.platform.commonobservability.TraceContext.open(request.getHeader("traceparent"))) {
            chain.doFilter(request, response);
        }
    }
}
