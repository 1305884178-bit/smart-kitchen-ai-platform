package com.smartkitchen.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PasswordEncoderTest {

    @Test
    public void seedHashMatches123456() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches("123456",
                "$2b$10$x.F77m/a/KziZW22lWFaVu7agSjUNN5I1J0SblLzHHRHiSz5.fmO."));
    }
}
