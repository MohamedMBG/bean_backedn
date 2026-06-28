package com.beanLoyal.backend.config;

import com.google.firebase.auth.FirebaseAuth;
import lombok.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class FirebaseAdminConfig {

    @Value("${firebase.credentials.path:}")
    private String firebaseCredentialsPath;

    @Value("${firebase.credentials.json:}")
    private String firebaseCredentialsJson;

    public void init() throws IOException {

    }

    @Bean
    public FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }

}
