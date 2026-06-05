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
    private final ConsultantJwtAuthFilter consultantJwtAuthFilter;
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
                        // Consultant Agreement (hidden internal feature):
                        // OTP request + verify are public so the consultant
                        // (who has no User row) can authenticate. The
                        // remaining /api/consultant/** endpoints require
                        // ROLE_CONSULTANT, set by ConsultantJwtAuthFilter
                        // when the short-lived purpose=consultant token
                        // handed out by verify-otp is presented.
                        .requestMatchers(
                                "/api/consultant/applications/*/request-otp",
                                "/api/consultant/applications/*/verify-otp"
                        ).permitAll()
                        .requestMatchers("/api/consultant/**").hasRole("CONSULTANT")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(consultantJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
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
