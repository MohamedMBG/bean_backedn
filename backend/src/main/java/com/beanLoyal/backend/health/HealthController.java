package com.beanLoyal.backend.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String , Object> health() {
        Map<String , Object> response = new HashMap<>();
        response.put("ok" , true);
        response.put("message" , "Service is up and running");
        return response;
    }
}
