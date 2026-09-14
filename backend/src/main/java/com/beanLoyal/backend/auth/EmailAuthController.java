package com.beanLoyal.backend.auth;

import com.beanLoyal.backend.common.RateLimitPolicy;
import com.beanLoyal.backend.common.RateLimitService;
import com.beanLoyal.backend.common.ClientIpResolver;
import io.github.bucket4j.Bandwidth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class EmailAuthController {
    private static final RateLimitPolicy SEND = new RateLimitPolicy(
            Bandwidth.builder().capacity(20).refillGreedy(20, Duration.ofHours(1)).build(),
            Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofHours(1)).build());
    private static final RateLimitPolicy VERIFY = new RateLimitPolicy(
            Bandwidth.builder().capacity(60).refillGreedy(60, Duration.ofMinutes(1)).build(),
            Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofMinutes(1)).build());
    private final EmailAuthService service;
    private final RateLimitService limits;

    public EmailAuthController(EmailAuthService service, RateLimitService limits) {
        this.service = service;
        this.limits = limits;
    }

    public record RegisterRequest(@NotBlank @Email @Size(max=254) String email,
            @NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String challenge) {}
    public record VerifyRequest(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
            @NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String verifier) {}

    @PostMapping("/register")
    public ResponseEntity<Map<String, Boolean>> register(@Valid @RequestBody RegisterRequest body,
            HttpServletRequest request) throws Exception {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        limits.check(SEND, ClientIpResolver.resolve(request), EmailAuthService.hash(email));
        service.register(email, body.challenge());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("ok", true));
    }

    @PostMapping("/verify")
    public ResponseEntity<EmailAuthService.SignInResponse> verify(@Valid @RequestBody VerifyRequest body,
            HttpServletRequest request) throws Exception {
        limits.check(VERIFY, ClientIpResolver.resolve(request), null);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.verify(body.token(), body.verifier()));
    }

    /** No token is redeemed by GET: email scanners cannot consume the link. */
    @GetMapping(value="/email", produces="text/html")
    public ResponseEntity<String> landing() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-beanloyal-email'; base-uri 'none'; frame-ancestors 'none'")
                .body("""
                    <!doctype html><html lang="en"><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>BeanLoyal email sign-in</title>
                    <h1>Sign in to BeanLoyal</h1>
                    <p>Open this link on the Android phone where you requested the email.</p>
                    <a id="open" hidden>Open BeanLoyal</a><p id="error"></p>
                    <script nonce="beanloyal-email">
                    const token = location.hash.slice(1);
                    history.replaceState(null, '', location.pathname);
                    if (/^[A-Za-z0-9_-]{43}$/.test(token)) {
                      const link = document.getElementById('open');
                      link.href = 'myapp://verify?token=' + encodeURIComponent(token);
                      link.hidden = false;
                    } else {
                      document.getElementById('error').textContent = 'Invalid link. Request a new email in BeanLoyal.';
                    }
                    </script></html>
                    """);
    }
}
