package com.beanLoyal.backend.rewards;

import com.beanLoyal.backend.common.*;
import com.beanLoyal.backend.config.*;
import com.beanLoyal.backend.security.*;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RewardsController.class)
@Import({SecurityConfig.class, FirebaseAuthFilter.class, WebMvcConfig.class})
class PendingRewardControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FirebaseAuth auth;
    @MockitoBean BirthdayRewardService birthday;
    @MockitoBean RewardRedemptionService redemption;
    @MockitoBean RedeemCodeService codes;
    @MockitoBean IdempotencyService idempotency;
    @MockitoBean RateLimitService limits;

    @Test
    void anonymousCallerCannotReadQr() throws Exception {
        mvc.perform(get("/api/v1/rewards/redeem/pending")).andExpect(status().isUnauthorized());
        verifyNoInteractions(codes);
    }

    @Test
    void ignoresSuppliedUidAndUsesAuthenticatedOwner() throws Exception {
        when(codes.pendingReward("alice")).thenReturn(new RedeemCodeService.PendingRewardResponse(null));
        var identity = new UsernamePasswordAuthenticationToken(
                new CurrentUser("alice", "alice@example.com", Map.of()), null, List.of());
        mvc.perform(get("/api/v1/rewards/redeem/pending?uid=bob").with(authentication(identity)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.pending").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(codes).pendingReward("alice");
        verifyNoMoreInteractions(codes);
    }
}
