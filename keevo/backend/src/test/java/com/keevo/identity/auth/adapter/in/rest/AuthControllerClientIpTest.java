package com.keevo.identity.auth.adapter.in.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("16.11 — client IP of the refresh rate limit")
class AuthControllerClientIpTest {

    @Test
    @DisplayName("right-most X-Forwarded-For entry wins (the one the proxy appended)")
    void rightMostForwardedEntry() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.2");
        r.addHeader("X-Forwarded-For", "6.6.6.6, 1.2.3.4 , 41.202.1.7");
        assertThat(AuthController.clientIp(r)).isEqualTo("41.202.1.7");
    }

    @Test
    @DisplayName("no header → socket address")
    void socketAddress() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.2");
        assertThat(AuthController.clientIp(r)).isEqualTo("10.0.0.2");
    }
}
