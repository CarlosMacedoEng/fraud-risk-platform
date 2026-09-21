package com.fraudplatform.decision.config;

import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.decision.api.ApiExceptionHandler;
import com.fraudplatform.decision.api.security.ApiKeyAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Roles: SCORING (score + read own tenant decisions), ANALYST (read decisions, explanations, cases),
 * ADMIN (strategy/model configuration). Stateless, no sessions, no CSRF (machine-to-machine API).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, PlatformProperties props, ObjectMapper json) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .addFilterBefore(new ApiKeyAuthenticationFilter(props), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/decisions").hasRole("SCORING")
                        .requestMatchers(HttpMethod.GET, "/v1/decisions/**").hasAnyRole("SCORING", "ANALYST")
                        .requestMatchers("/v1/cases/**").hasAnyRole("ANALYST", "ADMIN")
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/lab/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401);
                            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            res.getWriter().write(json.writeValueAsString(ApiExceptionHandler.problem(ErrorCode.UNAUTHENTICATED, null, null)));
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403);
                            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            res.getWriter().write(json.writeValueAsString(ApiExceptionHandler.problem(ErrorCode.FORBIDDEN, null, null)));
                        }));
        return http.build();
    }
}
