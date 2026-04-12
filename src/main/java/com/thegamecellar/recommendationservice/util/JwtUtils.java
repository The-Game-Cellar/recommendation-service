package com.thegamecellar.recommendationservice.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

public class JwtUtils {

    private JwtUtils() {}

    public static String getUserId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getSubject();
    }

    public static String getBearerToken(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return "Bearer " + jwt.getTokenValue();
    }
}
