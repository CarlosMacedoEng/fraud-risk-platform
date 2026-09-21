package com.fraudplatform.fileadapter;

import com.fraudplatform.fileadapter.config.FileAdapterProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FileAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileAdapterApplication.class, args);
    }

    /** Ops API protection: {@code X-Api-Key} compared (hashed, constant time) with the configured ops key. */
    @Bean
    OncePerRequestFilter opsKeyFilter(FileAdapterProperties props) {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().startsWith("/v1/");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String key = req.getHeader("X-Api-Key");
                boolean ok = false;
                if (key != null) {
                    try {
                        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
                        ok = MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                                props.opsKeySha256().toLowerCase().getBytes(StandardCharsets.US_ASCII));
                    } catch (Exception ignored) {
                        ok = false;
                    }
                }
                if (!ok) {
                    res.setStatus(401);
                    res.setContentType("application/problem+json");
                    res.getWriter().write("{\"status\":401,\"code\":\"UNAUTHENTICATED\",\"title\":\"Missing or invalid API key\"}");
                    return;
                }
                chain.doFilter(req, res);
            }
        };
    }
}
