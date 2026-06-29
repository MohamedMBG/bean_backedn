package com.beanLoyal.backend.config;

import com.beanLoyal.backend.common.ApiV1;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;

// Auto-prefixes every controller annotated with @ApiV1 with "/api/v1".
// Means feature controllers declare paths like @GetMapping("/loyalty/earn") and
// the request URL becomes /api/v1/loyalty/earn.
// Controllers NOT annotated (HealthController, Actuator) stay at their own paths.
// Bumping to /api/v2 later = swap predicate + add @ApiV2; old controllers keep serving v1.
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                "/api/v1",
                HandlerTypePredicate.forAnnotation(ApiV1.class)
        );
    }
}
