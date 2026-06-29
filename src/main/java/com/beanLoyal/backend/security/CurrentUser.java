package com.beanLoyal.backend.security;

import java.util.Map;

public record CurrentUser(
        String uid,
        String email,
        Map<String , Object> claims
) { }