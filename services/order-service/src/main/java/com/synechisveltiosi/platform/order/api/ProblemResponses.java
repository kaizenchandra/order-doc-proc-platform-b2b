package com.synechisveltiosi.platform.order.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

public final class ProblemResponses {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ProblemResponses() {}
    public static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write(JSON.writeValueAsString(Map.of("type", "about:blank", "title", title, "status", status, "detail", detail)));
    }
}
