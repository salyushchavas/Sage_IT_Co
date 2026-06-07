package com.spire.backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AgreementErmAuthFilter agreementErmAuthFilter;
    private final AgreementGateFilter agreementGateFilter;

    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/brand").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // Phase 1B participant enrollment is public.
                        .requestMatchers(HttpMethod.POST, "/api/participants/enroll").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/courses", "/api/courses/**").permitAll()
                        .requestMatchers("/api/webhooks/**").permitAll()
                        .requestMatchers("/api/certificates/verify/**").permitAll()
                        .requestMatchers("/api/certificates/download/**").permitAll()
                        .requestMatchers("/api/verify/**").permitAll()
                        // Public read of the active Terms of Service text.
                        .requestMatchers(HttpMethod.GET, "/api/agreement/terms").permitAll()
                        .requestMatchers("/api/admin/**")
                                .hasAnyRole("ADMIN", "OPERATIONS_ADMIN", "SYSTEM_ADMIN")
                        // Agreement-ERM console (hidden, hardcoded
                        // single-operator login). Login is public;
                        // every other endpoint requires the JWT
                        // issued by /login (purpose=agreement_erm),
                        // validated by AgreementErmAuthFilter.
                        .requestMatchers(HttpMethod.POST, "/api/agreement-erm/login").permitAll()
                        .requestMatchers("/api/agreement-erm/applications/**")
                                .hasRole("AGREEMENT_ERM")
                        // Identity endpoint + super-admin console. Both
                        // require a valid agreement-console token
                        // (ROLE_AGREEMENT_ERM, granted to SUPER_ADMIN +
                        // ERM alike); AgreementAdminController narrows the
                        // /admin surface to the super-admin and 403s ERMs.
                        .requestMatchers("/api/agreement-erm/me")
                                .hasRole("AGREEMENT_ERM")
                        .requestMatchers("/api/agreements/admin/**")
                                .hasRole("AGREEMENT_ERM")
                        // Consultant-side surface is fully public.
                        // The UUID applicationId acts as the credential;
                        // ConsultantRateLimiter caps abuse and every
                        // request lands in the audit log.
                        // Consultant-side surface (portal phase): all
                        // routes permitAll at the security layer; the
                        // controller enforces its own portal-OTP /
                        // email-scoped token + rate limiter.
                        .requestMatchers("/api/consultant/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(agreementErmAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Agreement gate runs after JWT auth so it can read the
                // resolved principal. It exempts /api/auth/* and
                // /api/agreement/* internally so the user can always
                // reach the flow that lets them satisfy it.
                .addFilterAfter(agreementGateFilter, JwtAuthFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
