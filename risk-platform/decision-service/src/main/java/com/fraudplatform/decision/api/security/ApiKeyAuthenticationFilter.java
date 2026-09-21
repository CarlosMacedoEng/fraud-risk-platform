package com.fraudplatform.decision.api.security;

import com.fraudplatform.commons.correlation.Correlation;
import com.fraudplatform.decision.config.PlatformProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Local-build authentication: {@code X-Api-Key} is hashed (SHA-256) and compared in constant time with
 * configured hashes. Production assumption (documented, not implemented): OAuth2 client credentials or
 * mTLS terminated at the API gateway, with the gateway passing a verified client identity.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final Map<String, PlatformProperties.ApiClient> clientsByHash;

    public ApiKeyAuthenticationFilter(PlatformProperties props) {
        this.clientsByHash = props.clients() == null ? Map.of() : props.clients().stream()
                .collect(Collectors.toMap(c -> c.keySha256().toLowerCase(), Function.identity()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key != null && !key.isBlank()) {
            PlatformProperties.ApiClient client = lookup(key);
            if (client != null) {
                Set<String> roles = Set.copyOf(client.roles());
                var principal = new ApiClientPrincipal(client.clientId(), client.tenantId(), roles);
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put(Correlation.MDC_TENANT_ID, client.tenantId());
                MDC.put(Correlation.MDC_CLIENT_ID, client.clientId());
            }
        }
        chain.doFilter(request, response);
    }

    private PlatformProperties.ApiClient lookup(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(digest);
            for (var e : clientsByHash.entrySet()) {
                if (MessageDigest.isEqual(e.getKey().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
                    return e.getValue();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
