package io.github.knoxlink.companion;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Talks to the AI service the user picked for the diary writer: Claude (Anthropic), OpenAI,
 * Google Gemini, OpenRouter, or any OpenAI-compatible server (a model on a PC with Ollama or
 * LM Studio). One blocking request per call, so call it off the main thread. Only the prompt it
 * is given goes out, with the user's own key; nothing about the user or the device.
 */
final class AiClient {
    private AiClient() {}

    static final String[] PROVIDERS = {"claude", "openai", "gemini", "openrouter", "custom"};

    static String label(String provider) {
        switch (provider) {
            case "openai": return "OpenAI";
            case "gemini": return "Gemini";
            case "openrouter": return "OpenRouter";
            case "custom": return "Custom";
            default: return "Claude";
        }
    }

    /** A few good models for the service, the cheapest first (the first one is the default). */
    static String[] models(String provider) {
        switch (provider) {
            case "openai": return new String[]{"gpt-5-mini", "gpt-5", "gpt-4.1-mini"};
            case "gemini": return new String[]{"gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"};
            case "openrouter": return new String[]{"anthropic/claude-haiku-4.5", "openai/gpt-5-mini", "google/gemini-2.5-flash"};
            case "custom": return new String[0];
            default: return new String[]{"claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"};
        }
    }

    static String defaultModel(String provider) {
        String[] m = models(provider);
        return m.length > 0 ? m[0] : "";
    }

    // ------------------------------------------------------------------ settings

    static final String ON = "aiOn", PROVIDER = "aiProvider", BASE = "aiBase";

    static String modelKey(String provider) {
        return "aiModel_" + provider;
    }

    /** One key per service, so trying another one doesn't lose the first. */
    static String keyKey(String provider) {
        return "aiKey_" + provider;
    }

    /** The service, model, key and (custom) server address the user picked. */
    static final class Config {
        final String provider, model, key, base;

        Config(String provider, String model, String key, String base) {
            this.provider = provider;
            this.model = model;
            this.key = key;
            this.base = base;
        }

        /** What's still missing before it can be used, or null. */
        String missing() {
            if ("custom".equals(provider) && base.isEmpty()) return "Type your server's address";
            if (model.isEmpty()) return "Type the model's name";
            if (!"custom".equals(provider) && key.isEmpty()) return "Add your API key";
            return null;
        }

        String describe() {
            return label(provider) + " · " + model;
        }
    }

    static Config config(SharedPreferences prefs) {
        String p = prefs.getString(PROVIDER, "claude");
        boolean known = false;
        for (String x : PROVIDERS) known |= x.equals(p);
        if (!known) p = "claude";
        return new Config(p, prefs.getString(modelKey(p), defaultModel(p)).trim(), prefs.getString(keyKey(p), "").trim(),
                prefs.getString(BASE, "").trim());
    }

    // ------------------------------------------------------------------ requests

    /** Why a request didn't work, in a few plain words for a toast. */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    /**
     * Sends one prompt and returns the model's text. maxTokens is what the answer itself needs;
     * models that think before they answer (most of them now) get room for that on top.
     */
    static String complete(Config c, String system, String user, int maxTokens) throws Failure {
        String text;
        try {
            switch (c.provider) {
                case "claude":
                    text = claude(c, system, user, maxTokens);
                    break;
                case "gemini":
                    text = gemini(c, system, user, Math.max(maxTokens * 4, 1200));
                    break;
                default:
                    text = chat(c, system, user, Math.max(maxTokens * 4, 1200));
                    break;
            }
        } catch (JSONException e) {
            throw new Failure("The service's answer didn't make sense");
        }
        text = clean(text);
        if (text.isEmpty()) throw new Failure("The model sent back no text");
        return text;
    }

    private static String claude(Config c, String system, String user, int maxTokens) throws Failure, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", c.model);
        // the current models think before they answer, and the thinking counts toward max_tokens:
        // room for it, and the low effort level, plenty for a diary page
        body.put("max_tokens", Math.max(maxTokens * 4, 1200));
        body.put("system", system);
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)));
        body.put("output_config", new JSONObject().put("effort", "low"));
        JSONObject r;
        try {
            r = post("https://api.anthropic.com/v1/messages", body, 45000,
                    "x-api-key", c.key, "anthropic-version", "2023-06-01");
        } catch (Failure f) {
            // older models don't know the effort setting: once more without it
            String why = String.valueOf(f.getMessage());
            if (!why.contains("effort") && !why.contains("output_config")) throw f;
            body.remove("output_config");
            r = post("https://api.anthropic.com/v1/messages", body, 45000,
                    "x-api-key", c.key, "anthropic-version", "2023-06-01");
        }
        JSONArray content = r.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b != null && "text".equals(b.optString("type"))) sb.append(b.optString("text"));
        }
        if (sb.length() == 0 && "refusal".equals(r.optString("stop_reason"))) throw new Failure("The model declined to write this page");
        if (sb.length() == 0 && "max_tokens".equals(r.optString("stop_reason"))) {
            throw new Failure("The model ran out of room before it wrote anything");
        }
        return sb.toString();
    }

    private static String gemini(Config c, String system, String user, int maxTokens) throws Failure, JSONException {
        String model = c.model.startsWith("models/") ? c.model.substring(7) : c.model;
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
        body.put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", user)))));
        body.put("generationConfig", new JSONObject().put("maxOutputTokens", maxTokens));
        JSONObject r = post("https://generativelanguage.googleapis.com/v1beta/models/" + Uri.encode(model) + ":generateContent",
                body, 45000, "x-goog-api-key", c.key);
        JSONArray cands = r.optJSONArray("candidates");
        JSONObject first = cands == null ? null : cands.optJSONObject(0);
        if (first == null) {
            JSONObject fb = r.optJSONObject("promptFeedback");
            String why = fb == null ? "" : fb.optString("blockReason");
            throw new Failure(why.isEmpty() ? "The model sent back no text" : "The service blocked the request (" + why + ")");
        }
        JSONObject content = first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && !part.optBoolean("thought") && !part.isNull("text")) sb.append(part.optString("text"));
        }
        if (sb.length() == 0 && "MAX_TOKENS".equals(first.optString("finishReason"))) {
            throw new Failure("The model ran out of room before it wrote anything");
        }
        return sb.toString();
    }

    /** OpenAI's chat completions, which OpenRouter, Ollama, LM Studio and most others speak too. */
    private static String chat(Config c, String system, String user, int maxTokens) throws Failure, JSONException {
        boolean openai = "openai".equals(c.provider);
        String base = openai ? "https://api.openai.com/v1"
                : "openrouter".equals(c.provider) ? "https://openrouter.ai/api/v1" : serverUrl(c.base);
        JSONObject body = new JSONObject();
        body.put("model", c.model);
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user)));
        // OpenAI's newer models only take max_completion_tokens; the others still read max_tokens
        body.put(openai ? "max_completion_tokens" : "max_tokens", maxTokens);
        JSONObject r = post(base + "/chat/completions", body, "custom".equals(c.provider) ? 120000 : 45000,
                "Authorization", c.key.isEmpty() ? null : "Bearer " + c.key);
        JSONArray choices = r.optJSONArray("choices");
        JSONObject first = choices == null ? null : choices.optJSONObject(0);
        JSONObject msg = first == null ? null : first.optJSONObject("message");
        if (msg == null) throw new Failure("The model sent back no text");
        if (!msg.isNull("refusal") && !msg.optString("refusal").isEmpty()) throw new Failure("The model declined to write this page");
        String text = msg.isNull("content") ? "" : msg.optString("content");
        if (text.trim().isEmpty() && "length".equals(first.optString("finish_reason"))) {
            throw new Failure("The model ran out of room before it wrote anything");
        }
        return text;
    }

    /** A server address as typed ("192.168.1.20:11434") -> its OpenAI-style API root ("http://192.168.1.20:11434/v1"). */
    static String serverUrl(String typed) {
        String s = typed.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/chat/completions")) s = s.substring(0, s.length() - "/chat/completions".length());
        if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) s = "http://" + s;
        String path = Uri.parse(s).getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) s = s + "/v1";
        return s;
    }

    private static JSONObject post(String url, JSONObject body, int readMs, String... headers) throws Failure {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(readMs);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setUseCaches(false);
            h.setRequestProperty("Content-Type", "application/json");
            h.setRequestProperty("Accept", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) {
                if (headers[i + 1] != null && !headers[i + 1].isEmpty()) h.setRequestProperty(headers[i], headers[i + 1]);
            }
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            h.setFixedLengthStreamingMode(out.length);
            try (OutputStream o = h.getOutputStream()) {
                o.write(out);
            }
            int code = h.getResponseCode();
            String text = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
            if (code < 200 || code >= 300) throw new Failure(httpError(code, text));
            return new JSONObject(text);
        } catch (Failure f) {
            throw f;
        } catch (java.net.UnknownHostException e) {
            throw new Failure("No internet, or the address is wrong");
        } catch (java.net.SocketTimeoutException e) {
            throw new Failure("No answer in time");
        } catch (java.net.ConnectException e) {
            throw new Failure("Couldn't reach " + Uri.parse(url).getHost());
        } catch (javax.net.ssl.SSLException e) {
            throw new Failure("The secure connection failed");
        } catch (JSONException e) {
            throw new Failure("The service's answer didn't make sense");
        } catch (Exception e) {
            throw new Failure("Network error" + (e.getMessage() == null ? "" : ": " + shorten(e.getMessage(), 80)));
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0 && b.size() < (1 << 20)) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** "The key wasn't accepted: <the service's own words>". */
    private static String httpError(int code, String body) {
        String msg = null;
        try {
            Object o = body.trim().startsWith("[") ? new JSONArray(body).opt(0) : new JSONObject(body);
            if (o instanceof JSONObject) {
                Object e = ((JSONObject) o).opt("error");
                if (e instanceof JSONObject) msg = ((JSONObject) e).optString("message", null);
                else if (e instanceof String) msg = (String) e;
                if (msg == null) msg = ((JSONObject) o).optString("message", null);
            }
        } catch (Exception ignored) {
        }
        String what;
        switch (code) {
            case 401:
            case 403:
                what = "The key wasn't accepted";
                break;
            case 404:
                what = "Model not found";
                break;
            case 429:
                what = "Too many requests, or no credit left";
                break;
            case 400:
                what = "The service refused the request";
                break;
            default:
                what = code >= 500 ? "The service is having trouble" : "Error " + code;
        }
        return msg == null || msg.trim().isEmpty() ? what : what + ": " + shorten(msg.trim(), 140);
    }

    static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** The page text alone: no thinking, markdown, heading or quotes around it. */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?s)<think>.*?</think>", "").replace("\r", "");
        t = t.replace("**", "").replace("__", "");
        t = t.replaceAll("(?m)^#+\\s*", "");
        t = t.trim();
        // a "Day 12" or "Day 12 - July 20" heading line the page already has
        t = t.replaceFirst("^(?i)(day\\s+\\d+[^\\n]{0,40})\\n+", "").trim();
        if (t.length() > 1 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("“") && t.endsWith("”")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        t = t.replaceAll("\n{3,}", "\n\n");
        if (t.length() > 1500) {
            int cut = Math.max(t.lastIndexOf(". ", 1500), Math.max(t.lastIndexOf("! ", 1500), t.lastIndexOf("? ", 1500)));
            t = cut > 750 ? t.substring(0, cut + 1) : t.substring(0, 1499) + "…";
        }
        return t;
    }
}
