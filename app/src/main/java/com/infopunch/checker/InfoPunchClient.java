package com.infopunch.checker;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class InfoPunchClient {
    private static final String APP_ENTRY_URL = "https://app.info-punch.com";
    private static final String DEFAULT_API_BASE_URL = "https://srv5.info-punch.com:44348";
    private static final long TOKEN_REFRESH_WINDOW_MS = 60_000L;
    private static final Map<String, TokenCacheEntry> TOKEN_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Object> TOKEN_LOCKS = new ConcurrentHashMap<>();
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static volatile String discoveredApiBaseUrl;
    private final OkHttpClient httpClient = HTTP_CLIENT;

    public LoginResult loginAndFetchUser(String apiBaseUrl, String companyCode, String nip) throws IOException, JSONException {
        String resolvedBaseUrl = resolveApiBaseUrl(apiBaseUrl);
        String token = fetchToken(resolvedBaseUrl, companyCode);
        JSONObject user = fetchUser(resolvedBaseUrl, token, nip);
        JSONObject parameters = fetchParameters(resolvedBaseUrl, token);
        String sitePath = fetchSitePath(resolvedBaseUrl, token);
        return new LoginResult(resolvedBaseUrl, user, parameters, sitePath);
    }

    public JSONObject fetchUserFast(String apiBaseUrl, String companyCode, String nip) throws IOException, JSONException {
        String resolvedBaseUrl = resolveApiBaseUrl(apiBaseUrl);
        String token = fetchToken(resolvedBaseUrl, companyCode);
        return fetchUser(resolvedBaseUrl, token, nip);
    }

    public CommunicationStatus diagnoseCommunication(String apiBaseUrl, String companyCode, String nip)
            throws IOException, JSONException {
        String resolvedBaseUrl = resolveApiBaseUrl(apiBaseUrl);
        String token = fetchToken(resolvedBaseUrl, companyCode);
        JSONObject user = fetchUser(resolvedBaseUrl, token, nip);
        JSONObject parameters = fetchParameters(resolvedBaseUrl, token);
        String employeeUniqueId = user.optString("employeeUniqueId", "");
        boolean messagingEndpointReachable = !employeeUniqueId.isEmpty()
                && isSuccessfulGet(resolvedBaseUrl + "/Api/messages/getThreads/" + employeeUniqueId, token);
        JSONObject lastPunch = user.optJSONObject("LastPunch");
        return new CommunicationStatus(
                parameters.optBoolean("AppMobileUsed", false) && user.optBoolean("MobilePunch", false),
                parameters.optBoolean("mobileChatActive", false),
                messagingEndpointReachable,
                lastPunch != null && !lastPunch.optString("Note", "").trim().isEmpty()
        );
    }

    private boolean isSuccessfulGet(String url, String accessToken) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    private String fetchToken(String apiBaseUrl, String companyCode) throws IOException, JSONException {
        String cacheKey = apiBaseUrl + "|" + companyCode;
        Object tokenLock = TOKEN_LOCKS.computeIfAbsent(cacheKey, ignored -> new Object());
        synchronized (tokenLock) {
            TokenCacheEntry cached = TOKEN_CACHE.get(cacheKey);
            if (cached != null && cached.expiresAtMs - TOKEN_REFRESH_WINDOW_MS > System.currentTimeMillis()) {
                return cached.token;
            }

            RequestBody formBody = new FormBody.Builder()
                    .add("username", companyCode)
                    .add("password", companyCode)
                    .add("grant_type", "password")
                    .build();

            Request request = new Request.Builder()
                    .url(apiBaseUrl + "/oauth/token")
                    .post(formBody)
                    .header("Accept", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Connexion OAuth impossible (" + response.code() + ")");
                }

                JSONObject json = new JSONObject(body);
                String accessToken = json.optString("access_token");
                if (accessToken.isEmpty()) {
                    throw new IOException("Jeton OAuth absent dans la reponse.");
                }
                int expiresIn = json.optInt("expires_in", 3600);
                TOKEN_CACHE.put(cacheKey, new TokenCacheEntry(accessToken, System.currentTimeMillis() + expiresIn * 1000L));
                return accessToken;
            }
        }
    }

    private JSONObject fetchUser(String apiBaseUrl, String accessToken, String nip) throws IOException, JSONException {
        HttpUrl base = HttpUrl.parse(apiBaseUrl + "/Parameters/User");
        if (base == null) {
            throw new IOException("URL Info-Punch invalide.");
        }
        String url = base.newBuilder().addQueryParameter("NIP", nip).build().toString();
        return getJsonObject(url, accessToken, "Lecture utilisateur impossible");
    }

    private JSONObject fetchParameters(String apiBaseUrl, String accessToken) throws IOException, JSONException {
        return getJsonObject(apiBaseUrl + "/Parameters", accessToken, "Lecture des parametres impossible");
    }

    private String fetchSitePath(String apiBaseUrl, String accessToken) throws IOException, JSONException {
        JSONObject response = getJsonObject(apiBaseUrl + "/Parameters/SitePath", accessToken, "Lecture du sitePath impossible");
        return response.optString("Path", "/");
    }

    private JSONObject getJsonObject(String url, String accessToken, String errorPrefix) throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException(errorPrefix + " (" + response.code() + ")");
            }
            return new JSONObject(body);
        }
    }

    private String normalizeBaseUrl(String apiBaseUrl) throws IOException {
        String value = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        if (value.isEmpty()) {
            return DEFAULT_API_BASE_URL;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || !"https".equalsIgnoreCase(parsed.scheme()) || parsed.host().isEmpty()) {
            throw new IOException("L'API Info-Punch doit utiliser une adresse HTTPS valide.");
        }
        return parsed.newBuilder().query(null).fragment(null).build().toString().replaceAll("/$", "");
    }

    private String resolveApiBaseUrl(String apiBaseUrl) throws IOException {
        String normalized = normalizeBaseUrl(apiBaseUrl);
        if (normalized.contains("app.info-punch.com")) {
            String discovered = discoverApiBaseUrlFromApp();
            if (discovered != null && !discovered.isEmpty()) {
                return discovered;
            }
            return DEFAULT_API_BASE_URL;
        }
        return normalized;
    }

    private String discoverApiBaseUrlFromApp() throws IOException {
        if (discoveredApiBaseUrl != null && !discoveredApiBaseUrl.isEmpty()) {
            return discoveredApiBaseUrl;
        }
        Request appRequest = new Request.Builder()
                .url(APP_ENTRY_URL)
                .get()
                .build();

        String html;
        try (Response response = httpClient.newCall(appRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Impossible de charger app.info-punch.com (" + response.code() + ")");
            }
            html = response.body() != null ? response.body().string() : "";
        }

        Matcher scriptMatcher = Pattern.compile("/js/mobile\\.[^\"]+\\.js").matcher(html);
        if (!scriptMatcher.find()) {
            return DEFAULT_API_BASE_URL;
        }

        String scriptUrl = APP_ENTRY_URL + scriptMatcher.group();
        Request jsRequest = new Request.Builder()
                .url(scriptUrl)
                .get()
                .build();

        try (Response response = httpClient.newCall(jsRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Impossible de charger le script mobile Info-Punch (" + response.code() + ")");
            }
            String script = response.body() != null ? response.body().string() : "";
            Matcher apiMatcher = Pattern.compile("https://[A-Za-z0-9.-]+:44348").matcher(script);
            String selected = null;
            while (apiMatcher.find()) {
                String candidate = apiMatcher.group();
                if (!candidate.contains("apptest")) {
                    selected = candidate;
                }
            }
            discoveredApiBaseUrl = selected != null ? selected : DEFAULT_API_BASE_URL;
            return discoveredApiBaseUrl;
        }
    }

    public static class LoginResult {
        public final String resolvedApiUrl;
        public final JSONObject user;
        public final JSONObject parameters;
        public final String sitePath;

        public LoginResult(String resolvedApiUrl, JSONObject user, JSONObject parameters, String sitePath) {
            this.resolvedApiUrl = resolvedApiUrl;
            this.user = user;
            this.parameters = parameters;
            this.sitePath = sitePath;
        }
    }

    public static class CommunicationStatus {
        public final boolean punchNoteEnabled;
        public final boolean messagingEnabled;
        public final boolean messagingEndpointReachable;
        public final boolean previousPunchNoteSeen;

        CommunicationStatus(
                boolean punchNoteEnabled,
                boolean messagingEnabled,
                boolean messagingEndpointReachable,
                boolean previousPunchNoteSeen
        ) {
            this.punchNoteEnabled = punchNoteEnabled;
            this.messagingEnabled = messagingEnabled;
            this.messagingEndpointReachable = messagingEndpointReachable;
            this.previousPunchNoteSeen = previousPunchNoteSeen;
        }
    }

    private static class TokenCacheEntry {
        final String token;
        final long expiresAtMs;

        TokenCacheEntry(String token, long expiresAtMs) {
            this.token = token;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
