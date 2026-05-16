package com.basicsdriverupdate.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class JsonMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private JsonMapper() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNode parseTree(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.isEmpty()) {
            return MAPPER.createArrayNode();
        }
        return MAPPER.readTree(trimmed);
    }
}
