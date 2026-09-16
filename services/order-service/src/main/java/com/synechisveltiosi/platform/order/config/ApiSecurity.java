package com.synechisveltiosi.platform.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiSecurity {
    @Bean JwtDecoder jwtDecoder(@Value("${app.security.issuer}") String issuer,
            @Value("${app.security.jwk-set-uri}") String jwks,
            @Value("${app.security.audience}") String audience) {
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank()) throw new IllegalArgumentException("JWT configuration is required");
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                String tenant = jwt.getClaimAsString("tenant_id");
                if (tenant == null || !UUID.fromString(tenant).toString().equalsIgnoreCase(tenant)
                        || jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getExpiresAt() == null
                        || !jwt.getAudience().contains(audience)) throw new IllegalArgumentException();
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException invalid) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required token claims are invalid", null));
            }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }
    @Bean SecurityFilterChain orderSecurityFilterChain(HttpSecurity http) throws Exception {
        // Bearer tokens only; no cookie/session authentication, so CSRF tokens are not used.
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAuthority("SCOPE_orders:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders", "/api/v1/orders/**").hasAuthority("SCOPE_orders:write")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/orders/*/status").hasAuthority("SCOPE_orders:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            com.synechisveltiosi.platform.order.api.ProblemResponses.write(response, 401, "Unauthorized", "A valid bearer token is required");
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            com.synechisveltiosi.platform.order.api.ProblemResponses.write(response, 401, "Unauthorized", "A valid bearer token is required");
                        })
                        .accessDeniedHandler((request, response, failure) ->
                                com.synechisveltiosi.platform.order.api.ProblemResponses.write(response, 403, "Forbidden", "The token does not grant access to this operation")));
        return http.build();
    }
}
