package com.aegis.intelligence;

public record RbacPermission(
        String capability,
        String verb,
        String resource,
        boolean allowed,
        String reason
) {
}
