package com.beanLoyal.backend.auth;

import com.beanLoyal.backend.common.RateLimitService;
import com.beanLoyal.backend.config.SecurityConfig;
import com.beanLoyal.backend.security.FirebaseAuthFilter;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmailAuthController.class)
@Import({SecurityConfig.class, FirebaseAuthFilter.class, RateLimitService.class})
class EmailAuthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EmailAuthService service;
    @MockitoBean FirebaseAuth auth;

    @Test
    void signedOutCustomerCanRegisterAndVerify() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content("{\"email\":\"Customer@example.com\",\"challenge\":\"" + "c".repeat(43) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        verify(service).register("customer@example.com", "c".repeat(43));
        when(service.verify(anyString(), anyString())).thenReturn(
                new EmailAuthService.SignInResponse(true, "customer@example.com", "custom-token"));
        mvc.perform(post("/api/v1/auth/verify").contentType("application/json")
                .header("Authorization", "Bearer stale-token")
                .content("{\"token\":\"" + "t".repeat(43) + "\",\"verifier\":\"" + "v".repeat(43) + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.customToken").value("custom-token"));
        verifyNoInteractions(auth);
    }

    @Test
    void malformedRequestsNeverReachService() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content("{\"email\":\"invalid\"}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/verify").contentType("application/json")
                .content("{\"token\":\"invalid\"}")) .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void emailScannerCannotRedeemLinkAndOtherRoutesStayProtected() throws Exception {
        mvc.perform(get("/api/v1/auth/email")).andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/api/v1/admin/cashiers")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
