package com.bodia.shoptrader.ai;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class CerebrasClient {
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final double topP;
    private final int maxTokens;
    private final int timeoutSeconds;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new GsonBuilder().create();

    public static class QuestSpec {
        public String material;
        public int amount;
        public Double reward; // optional
    }

    private JsonArray extractArrayForKey(String text, String key) {
        if (text == null || key == null) return null;
        int k = text.indexOf('"' + key + '"');
        if (k < 0) {
            // try without quotes (very loose)
            k = text.toLowerCase(java.util.Locale.ROOT).indexOf(key.toLowerCase(java.util.Locale.ROOT));
            if (k < 0) return null;
        }
        int lb = text.indexOf('[', k);
        if (lb < 0) return null;
        String balanced = extractBalancedArrayFrom(text, lb);
        if (balanced == null) return null;
        try {
            return JsonParser.parseString(balanced).getAsJsonArray();
        } catch (Exception ignore) {
            try {
                JsonReader reader = new JsonReader(new StringReader(balanced));
                try {
                    Class<?> strictnessCls = Class.forName("com.google.gson.stream.Strictness");
                    Object LENIENT = strictnessCls.getField("LENIENT").get(null);
                    reader.getClass().getMethod("setStrictness", strictnessCls).invoke(reader, LENIENT);
                } catch (Throwable t) { try { reader.setLenient(true); } catch (Throwable ignore2) {} }
                JsonElement el = JsonParser.parseReader(reader);
                if (el != null && el.isJsonArray()) return el.getAsJsonArray();
            } catch (Exception ignore2) {}
        }
        return null;
    }

    private JsonArray extractJsonArray(String text) {
        if (text == null) return null;
        // Try direct parse first
        try {
            JsonArray direct = JsonParser.parseString(text).getAsJsonArray();
            if (direct != null) return direct;
        } catch (Exception ignore) {}
        // Scan for a balanced array
        int idx = -1;
        while (true) {
            idx = text.indexOf('[', idx + 1);
            if (idx < 0) return null;
            String candidate = extractBalancedArrayFrom(text, idx);
            if (candidate == null) return null;
            try {
                // Parse leniently via reader as well
                JsonReader reader = new JsonReader(new StringReader(candidate));
                try {
                    Class<?> strictnessCls = Class.forName("com.google.gson.stream.Strictness");
                    Object LENIENT = strictnessCls.getField("LENIENT").get(null);
                    reader.getClass().getMethod("setStrictness", strictnessCls).invoke(reader, LENIENT);
                } catch (Throwable t) { try { reader.setLenient(true); } catch (Throwable ignore2) {} }
                JsonElement el = JsonParser.parseReader(reader);
                if (el != null && el.isJsonArray()) return el.getAsJsonArray();
            } catch (Exception ignore) {}
        }
    }

    private String extractBalancedArrayFrom(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    public CerebrasClient(String apiKey, String model, double temperature, double topP, int maxTokens, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<QuestSpec> generateQuests(String systemPrompt, String userPrompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);
        body.addProperty("top_p", topP);
        // Ask for strict JSON object output (OpenAI-compatible JSON mode)
        // Avoid enabling for "thinking" models which may fail with incomplete_json_output.
        try {
            String ml = model == null ? "" : model.toLowerCase(Locale.ROOT);
            if (!ml.contains("thinking")) {
                JsonObject rf = new JsonObject();
                rf.addProperty("type", "json_object");
                body.add("response_format", rf);
            }
        } catch (Throwable ignore) {
            // If the provider doesn't support response_format, safe to ignore
        }
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt == null ? "" : systemPrompt);
        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", userPrompt);
        messages.add(sys);
        messages.add(usr);
        body.add("messages", messages);

        String json = gson.toJson(body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.cerebras.ai/v1/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            // Attempt to recover from error body (e.g., incomplete_json_output with 'failed_generation' content)
            List<QuestSpec> recovered = tryRecoverFromError(resp.body());
            if (recovered != null) return recovered;
            throw new RuntimeException("Cerebras API HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Exception e) {
            // Try lenient parsing of the entire response body
            root = tryParseJsonLenient(resp.body());
            if (root == null) {
                throw new RuntimeException("Cerebras returned malformed top-level JSON. Preview: " + preview(resp.body()));
            }
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) return List.of();
        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject msg = first.getAsJsonObject("message");
        String content = msg.get("content").getAsString();
        // Strip common code fences and trim (handle any ```lang prefix)
        content = content.replaceAll("(?s)```[a-zA-Z0-9]*\\s*", "");
        content = content.replace("```", "").trim();
        // Try to extract a balanced JSON object that contains the 'quests' key
        JsonObject out = extractJsonObjectWithKey(content, "quests");
        if (out == null) {
            // Some models may return a top-level array instead of an object; attempt to parse an array and wrap it
            JsonArray arr = extractJsonArray(content);
            if (arr != null) {
                out = new JsonObject();
                out.add("quests", arr);
            } else {
                // Last-resort: extract the array that follows a '"quests"' key even if braces are missing
                JsonArray arr2 = extractArrayForKey(content, "quests");
                if (arr2 != null) {
                    out = new JsonObject();
                    out.add("quests", arr2);
                } else {
                    throw new RuntimeException("Cerebras returned malformed JSON content. Preview: " + preview(content));
                }
            }
        }
        JsonArray quests = out.getAsJsonArray("quests");
        List<QuestSpec> list = new ArrayList<>();
        if (quests != null) {
            for (int i = 0; i < quests.size(); i++) {
                JsonObject q = quests.get(i).getAsJsonObject();
                QuestSpec spec = new QuestSpec();
                spec.material = q.get("material").getAsString();
                spec.amount = q.get("amount").getAsInt();
                if (q.has("reward") && !q.get("reward").isJsonNull()) {
                    try { spec.reward = q.get("reward").getAsDouble(); } catch (Exception ignore) {}
                }
                list.add(spec);
            }
        }
        return list;
    }

    private JsonObject tryParseJsonLenient(String s) {
        if (s == null) return null;
        try {
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception ignore) {}
        try {
            JsonReader reader = new JsonReader(new StringReader(s));
            // Gson 2.11+: setStrictness(Strictness.LENIENT). Fallback to older setLenient.
            try {
                Class<?> strictnessCls = Class.forName("com.google.gson.stream.Strictness");
                Object LENIENT = strictnessCls.getField("LENIENT").get(null);
                reader.getClass().getMethod("setStrictness", strictnessCls).invoke(reader, LENIENT);
            } catch (Throwable t) {
                try { reader.setLenient(true); } catch (Throwable ignore2) {}
            }
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractBalancedFrom(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private JsonObject extractJsonObjectWithKey(String text, String requiredKey) {
        if (text == null) return null;
        int idx = -1;
        while (true) {
            idx = text.indexOf('{', idx + 1);
            if (idx < 0) return null;
            String candidate = extractBalancedFrom(text, idx);
            if (candidate == null) return null;
            JsonObject obj = tryParseJsonLenient(candidate);
            if (obj != null) {
                if (requiredKey == null || obj.has(requiredKey)) return obj;
            }
        }
    }

    private String preview(String s) {
        if (s == null) return "<null>";
        String flat = s.replaceAll("\n", " ").replaceAll("\r", " ").trim();
        if (flat.length() > 200) flat = flat.substring(0, 200) + "...";
        return flat;
    }

    private List<QuestSpec> tryRecoverFromError(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonObject err = tryParseJsonLenient(body);
            if (err == null) return null;
            if (err.has("failed_generation") && !err.get("failed_generation").isJsonNull()) {
                String content = err.get("failed_generation").getAsString();
                // Strip code fences and trim
                content = content.replaceAll("(?s)```[a-zA-Z0-9]*\\s*", "");
                content = content.replace("```", "").trim();
                JsonObject out = extractJsonObjectWithKey(content, "quests");
                if (out == null) {
                    JsonArray arr = extractJsonArray(content);
                    if (arr != null) {
                        out = new JsonObject();
                        out.add("quests", arr);
                    } else {
                        JsonArray arr2 = extractArrayForKey(content, "quests");
                        if (arr2 != null) {
                            out = new JsonObject();
                            out.add("quests", arr2);
                        }
                    }
                }
                if (out != null) {
                    JsonArray quests = out.getAsJsonArray("quests");
                    if (quests == null) return null;
                    List<QuestSpec> list = new ArrayList<>();
                    for (int i = 0; i < quests.size(); i++) {
                        try {
                            JsonObject q = quests.get(i).getAsJsonObject();
                            QuestSpec spec = new QuestSpec();
                            spec.material = q.get("material").getAsString();
                            spec.amount = q.get("amount").getAsInt();
                            if (q.has("reward") && !q.get("reward").isJsonNull()) {
                                try { spec.reward = q.get("reward").getAsDouble(); } catch (Exception ignore) {}
                            }
                            list.add(spec);
                        } catch (Exception ignore) { /* skip malformed entries */ }
                    }
                    return list.isEmpty() ? null : list;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }
}
