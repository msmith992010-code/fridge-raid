package uk.fridgeraid.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Minimal client for the Claude Messages API, with optional server-side web search. */
final class ClaudeClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private ClaudeClient() {}

    /** A page that came back from a web search. */
    static final class Source {
        final String url;
        final String title;
        Source(String url, String title) { this.url = url; this.title = title; }
    }

    /** What Claude said, plus every web page its searches returned. */
    static final class Reply {
        final StringBuilder text = new StringBuilder();
        final ArrayList<Source> sources = new ArrayList<>();
        final ArrayList<String> queries = new ArrayList<>();
        boolean truncated;
    }

    /** An error the API reported, such as a rejected key or no credit. */
    static final class ApiException extends Exception {
        final int status;
        final String type;
        ApiException(int status, String type, String message) {
            super(message);
            this.status = status;
            this.type = type == null ? "" : type;
        }
    }

    static Reply ask(String apiKey, String model, String prompt, boolean webSearch, int maxTokens)
            throws IOException, JSONException, ApiException {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", prompt));
        Reply reply = new Reply();

        // Web search runs on Anthropic's side. A long search can pause the turn; we then
        // send the partial answer back so Claude carries on where it stopped.
        for (int turn = 0; turn < 5; turn++) {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("messages", messages);
            if (webSearch) {
                JSONObject tool = new JSONObject()
                        .put("type", "web_search_20250305")
                        .put("name", "web_search")
                        .put("max_uses", 6)
                        .put("user_location", new JSONObject()
                                .put("type", "approximate")
                                .put("country", "GB"));
                body.put("tools", new JSONArray().put(tool));
            }

            JSONObject resp = post(apiKey, body);
            JSONArray content = resp.optJSONArray("content");
            if (content == null) content = new JSONArray();

            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block == null) continue;
                String type = block.optString("type");
                if ("text".equals(type)) {
                    reply.text.append(block.optString("text"));
                } else if ("server_tool_use".equals(type)) {
                    JSONObject input = block.optJSONObject("input");
                    if (input != null && input.has("query")) reply.queries.add(input.optString("query"));
                } else if ("web_search_tool_result".equals(type)) {
                    JSONArray results = block.optJSONArray("content");
                    if (results == null) continue; // a search error comes back as an object
                    for (int j = 0; j < results.length(); j++) {
                        JSONObject r = results.optJSONObject(j);
                        if (r == null) continue;
                        String url = r.optString("url", "");
                        if (!url.isEmpty()) reply.sources.add(new Source(url, r.optString("title", url)));
                    }
                }
            }

            String stop = resp.optString("stop_reason");
            if ("pause_turn".equals(stop)) {
                messages.put(new JSONObject().put("role", "assistant").put("content", content));
                continue;
            }
            if ("max_tokens".equals(stop)) reply.truncated = true;
            break;
        }
        return reply;
    }

    private static JSONObject post(String apiKey, JSONObject body)
            throws IOException, JSONException, ApiException {
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20_000);
            c.setReadTimeout(240_000); // searching several recipe sites can take a while
            c.setRequestProperty("x-api-key", apiKey);
            c.setRequestProperty("anthropic-version", API_VERSION);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("accept", "application/json");

            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }

            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);

            if (status >= 400) {
                String type = "";
                String message = "HTTP " + status;
                try {
                    JSONObject err = new JSONObject(text).optJSONObject("error");
                    if (err != null) {
                        type = err.optString("type", "");
                        message = err.optString("message", message);
                    }
                } catch (JSONException ignored) {
                    // body was not JSON; keep the status line
                }
                throw new ApiException(status, type, message);
            }
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        }
    }
}
