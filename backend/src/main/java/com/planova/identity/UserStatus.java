package com.planova.identity;

/** DISABLED users cannot log in and their existing tokens stop working on the very next request. */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
