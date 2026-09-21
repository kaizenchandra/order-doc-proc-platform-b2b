package com.synechisveltiosi.platform.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class PushSecurity {
    @Bean
    JwtDecoder pushJwtDecoder(@Value("${app.push.issuer}") String issuer,
                              @Value("${app.push.jwk-set-uri}") String jwks,
                              @Value("${app.push.audience}") String audience,
                              @Value("${app.push.service-account-email}") String email) {
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank() || email.isBlank())
            throw new IllegalArgumentException("Push authentication configuration is required");
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                if (jwt.getExpiresAt() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
                        || !jwt.getAudience().contains(audience) || !email.equals(jwt.getClaimAsString("email"))
                        || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")))
                    throw new IllegalArgumentException();
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException invalid) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain pushSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/pubsub/events").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
