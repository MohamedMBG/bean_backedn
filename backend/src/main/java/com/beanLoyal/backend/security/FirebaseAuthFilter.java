package com.beanLoyal.backend.security;

import com.beanLoyal.backend.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class FirebaseAuthFilter extends OncePerRequestFilter {

    // SLF4J logger. Never log raw token — only error codes / uid (after verify).
    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthFilter.class);

    // Constant for the Bearer prefix. Length 7 = "Bearer ".
    private static final String BEARER_PREFIX = "Bearer ";

    // Firebase Admin SDK entry point. Used to call verifyIdToken(...).
    // Bean is created in FirebaseAdminConfig.
    private final FirebaseAuth firebaseAuth;

    // Jackson mapper to serialize ApiError to JSON when we write a 401 response body.
    private final ObjectMapper objectMapper;

    // Constructor injection — Spring auto-wires both beans.
    public FirebaseAuthFilter(FirebaseAuth firebaseAuth, ObjectMapper objectMapper) {
        this.firebaseAuth = firebaseAuth;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Pull the Authorization header. May be null if client did not send one.
        String header = request.getHeader("Authorization");

        // No header OR wrong prefix → no credentials presented.
        // Continue chain unauthenticated; SecurityConfig.anyRequest().authenticated() will reject with 401.
        // This keeps "no token" and "rejected token" handling consistent through Spring's default 401 path.
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Strip the "Bearer " prefix to isolate the raw JWT.
        String token = header.substring(BEARER_PREFIX.length()).trim();

        // Empty after trim = malformed header → reject immediately.
        if (token.isEmpty()) {
            writeUnauthorized(response, "AUTH_INVALID_TOKEN", "Empty bearer token");
            return;
        }

        try {
            // Core verification: checks signature, expiry, issuer, audience against Firebase project.
            // Second arg defaults to false (no revocation check) — adds latency if true, defer until needed.
            FirebaseToken decoded = firebaseAuth.verifyIdToken(token);

            // Build the trusted identity snapshot from the decoded token.
            // uid is the ONLY trustworthy source for user identity — never read uid from request body.
            CurrentUser principal = new CurrentUser(
                    decoded.getUid(),
                    decoded.getEmail(),
                    decoded.getClaims()
            );

            // Map Firebase custom claim `role` → Spring Security authority `ROLE_<UPPER>`.
            // Set role per user via Firebase Admin: setCustomUserClaims(uid, Map.of("role", "cashier"))
            // then have the client force-refresh its ID token so the new claim ships on the next request.
            // Endpoints guard with @PreAuthorize("hasRole('CASHIER')") / hasRole('ADMIN').
            // MVP supports a single role per user. If multi-role is needed later, switch to a `roles` array claim.
            List<GrantedAuthority> authorities = extractAuthorities(decoded.getClaims());

            // Wrap principal in Spring's Authentication object.
            // Credentials = null (we already verified; no password to keep around).
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);

            // Store into the request-scoped SecurityContext so downstream:
            //   - @AuthenticationPrincipal CurrentUser resolves
            //   - .authenticated() check passes
            //   - @PreAuthorize can read authorities
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Continue chain — request proceeds to controller authenticated.
            filterChain.doFilter(request, response);

        } catch (FirebaseAuthException e) {
            // Token failed verification: expired, bad signature, wrong project, revoked, etc.
            // Log the AuthErrorCode only — token itself MUST NOT enter logs.
            log.warn("Firebase token verification failed: code={}", e.getAuthErrorCode());
            writeUnauthorized(response, "AUTH_INVALID_TOKEN", "Token invalid or expired");

        } catch (IllegalArgumentException e) {
            // verifyIdToken throws IAE when token is structurally malformed (not a JWT at all).
            log.warn("Malformed bearer token");
            writeUnauthorized(response, "AUTH_MALFORMED_TOKEN", "Malformed token");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip the filter for public endpoints so we don't burn a Firebase API call
        // on every uptime probe. SecurityConfig already permits these paths.
        String path = request.getRequestURI();
        return path.equals("/health")
                || path.startsWith("/actuator/");
    }

    // Writes a 401 response with our standard ApiError JSON shape.
    // Centralized so every auth failure looks identical to the client.
    private void writeUnauthorized(HttpServletResponse response,
                                   String code,
                                   String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ApiError body = ApiError.of(code, message);
        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * Build Spring Security authorities from Firebase custom claims.
     * Reads the single claim {@code role} (string) and maps it to authority {@code ROLE_<UPPER>}.
     * Unknown / missing role → empty authorities (plain authenticated user, no elevated access).
     * Non-string role value → ignored defensively (custom claims are caller-influenced via admin tooling).
     */
    private List<GrantedAuthority> extractAuthorities(java.util.Map<String, Object> claims) {
        if (claims == null) return List.of();
        Object role = claims.get("role");
        if (!(role instanceof String s) || s.isBlank()) return List.of();
        return List.of(new SimpleGrantedAuthority("ROLE_" + s.trim().toUpperCase()));
    }
}
