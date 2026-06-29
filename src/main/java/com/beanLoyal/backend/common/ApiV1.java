package com.beanLoyal.backend.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marker for controllers that should live under /api/v1.
// Composed annotation: @RestController + tag for WebMvcConfig path prefixing.
// Apply to any feature controller (loyalty, rewards, cashier, admin, push).
// Do NOT apply to HealthController — health stays at root /health.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@RestController
public @interface ApiV1 {
}
