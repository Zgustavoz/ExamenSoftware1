package com.diagramas.platform.design.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Criterio de «diagrama completo» (D-10): ≥ 1 clase y ninguna clase vacía (≥ 1 atributo o método). */
public final class DiagramCompleteness {

    private DiagramCompleteness() {}

    public static boolean isComplete(JsonNode content) {
        JsonNode classes = content == null ? null : content.get("classes");
        if (classes == null || !classes.isArray() || classes.isEmpty()) {
            return false;
        }
        for (JsonNode c : classes) {
            if (c.path("attributes").size() == 0 && c.path("methods").size() == 0) {
                return false;
            }
        }
        return true;
    }
}
