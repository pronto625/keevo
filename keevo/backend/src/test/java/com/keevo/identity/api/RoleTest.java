package com.keevo.identity.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Role — store members and V1 vocabulary (Story 19.5)")
class RoleTest {

    @Test
    @DisplayName("EMPLOYEE, MANAGER and VIEWER are store members; OWNER, SUPER_ADMIN, null and unknown are not")
    void storeMembers() {
        assertThat(Role.EMPLOYEE.isStoreMember()).isTrue();
        assertThat(Role.MANAGER.isStoreMember()).isTrue();
        assertThat(Role.VIEWER.isStoreMember()).isTrue();
        assertThat(Role.OWNER.isStoreMember()).isFalse();
        assertThat(Role.SUPER_ADMIN.isStoreMember()).isFalse();
        assertThat(Role.isStoreMember("VIEWER")).isTrue();
        assertThat(Role.isStoreMember(null)).isFalse();
        assertThat(Role.isStoreMember("GHOST")).isFalse();
    }

    @Test
    @DisplayName("V1 label: MANAGER and VIEWER → EMPLOYEE, the others unchanged")
    void v1Label() {
        assertThat(Role.v1Label("MANAGER")).isEqualTo("EMPLOYEE");
        assertThat(Role.v1Label("VIEWER")).isEqualTo("EMPLOYEE");
        assertThat(Role.v1Label("EMPLOYEE")).isEqualTo("EMPLOYEE");
        assertThat(Role.v1Label("OWNER")).isEqualTo("OWNER");
        assertThat(Role.v1Label("SUPER_ADMIN")).isEqualTo("SUPER_ADMIN");
        assertThat(Role.v1Label(null)).isNull();
        assertThat(Role.v1Label("GHOST")).isEqualTo("GHOST");
    }
}
