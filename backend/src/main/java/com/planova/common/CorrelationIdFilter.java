package com.planova.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID so one user action can be followed through log lines,
 * error responses and (later) audit records.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}: runs before the security filter chain, so even 401/403
 * responses carry an ID. A client-supplied ID is accepted only if it matches a strict pattern -
 * otherwise an attacker could inject newlines or huge strings into our logs (log forging).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? incoming : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, id);               // MDC = per-thread logging context
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);            // threads are pooled: always clean up
        }
    }

    /** Current request's correlation ID, or null outside a request (e.g. scheduler threads). */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
