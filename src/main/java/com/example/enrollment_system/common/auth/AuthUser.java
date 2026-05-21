package com.example.enrollment_system.common.auth;

import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.user.UserRole;

public record AuthUser(Long id, UserRole role) {

    public boolean isCreator() {
        return role == UserRole.CREATOR;
    }

    public boolean isStudent() {
        return role == UserRole.STUDENT;
    }

    public void requireCreator() {
        if (!isCreator()) {
            throw ErrorCode.FORBIDDEN.asException();
        }
    }

    public void requireStudent() {
        if (!isStudent()) {
            throw ErrorCode.FORBIDDEN.asException();
        }
    }
}
