package com.beanLoyal.backend.config;

import com.beanLoyal.backend.common.ApiError;
import com.beanLoyal.backend.security.FirebaseAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Tells Spring this class contributes bean definitions to the application context.
// @EnableMethodSecurity activates @PreAuthorize / @PostAuthorize on controller and service methods.
// Used by cashier and admin endpoints to gate access by role authority derived from Firebase claims.
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    // Hold reference to the Firebase auth filter so we can register it in the chain.
    private final FirebaseAuthFilter firebaseAuthFilter;

    // Used by the AuthenticationEntryPoint lambda to serialize ApiError JSON for 401s
    // that originate from Spring (e.g. "no token at all" — our filter only handles bad tokens).
    private final ObjectMapper objectMapper;

    // Constructor injection: Spring passes both managed beans.
    public SecurityConfig(FirebaseAuthFilter firebaseAuthFilter, ObjectMapper objectMapper) {
        this.firebaseAuthFilter = firebaseAuthFilter;
        this.objectMapper = objectMapper;
    }

    // @Bean tells Spring: call this method, register the returned SecurityFilterChain.
    // Spring Security auto-detects this bean and uses it instead of the default chain.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF protection. CSRF defends browser form/cookie sessions.
                // We use stateless bearer tokens, so CSRF tokens are unneeded and would block JSON clients.
                .csrf(AbstractHttpConfigurer::disable)

                // Session policy = STATELESS: Spring will never create an HttpSession.
                // Every request must authenticate itself via Authorization header — matches REST design.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Define which URLs are public vs protected.
                .authorizeHttpRequests(auth -> auth
                        // Whitelist:
                        //  /health                  — our custom liveness endpoint (HealthController).
                        //  /actuator/health         — Spring Boot Actuator aggregated health.
                        //  /actuator/health/**      — sub-probes: /liveness, /readiness (Render uses these).
                        //  /actuator/info           — build/app metadata; safe to expose.
                        .requestMatchers(
                                "/health",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()

                        // Every other request must have an authenticated principal in SecurityContext.
                        // FirebaseAuthFilter is responsible for placing that principal there.
                        .anyRequest().authenticated()
                )

                // Insert FirebaseAuthFilter before Spring's default username/password filter slot.
                // This ensures our filter runs early enough to populate SecurityContext
                // before Spring's authorization checks evaluate `.authenticated()`.
                .addFilterBefore(firebaseAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // Unified 401 shape for the "no credentials at all" case.
                // FirebaseAuthFilter already writes ApiError JSON when a token is present but invalid.
                // Without this entry point, Spring's default sends an empty 401 body, breaking the
                // "one response shape" contract clients rely on.
                .exceptionHandling(eh -> eh.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(
                            res.getWriter(),
                            ApiError.of("AUTH_REQUIRED", "Authentication required")
                    );
                }));

        // Build the chain and hand it back to Spring to register.
        return http.build();
    }
}
