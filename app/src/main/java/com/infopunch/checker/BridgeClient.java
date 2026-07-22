package com.infopunch.checker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BridgeClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient httpClient = new OkHttpClient();
    private final OkHttpClient waitHttpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            .build();

    public String pairDevice(String baseUrl, String pairCode, String deviceId, String deviceName) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("pair_code", pairCode);
        payload.put("device_id", deviceId);
        payload.put("device_name", deviceName);

        Request request = new Request.Builder()
                .url(normalizeBaseUrl(baseUrl) + "/api/pair")
                .post(RequestBody.create(payload.toString(), JSON))
                .header("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BridgeException("Pairage impossible (" + response.code() + ")", response.code());
            }
            JSONObject json = new JSONObject(body);
            String deviceToken = json.optString("device_token", "");
            if (deviceToken.isEmpty()) {
                throw new IOException("Jeton d'appareil absent apres le pairage.");
            }
            return deviceToken;
        }
    }

    public void registerAccount(String baseUrl, String deviceToken, SessionManager.SessionData account) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("account_id", account.accountId);
        payload.put("company_code", account.companyCode);
        payload.put("nip", account.nip);
        payload.put("api_url", account.apiUrl);
        payload.put("site_path", account.sitePath);
        payload.put("hours_link", account.hoursLink);
        payload.put("full_name", account.fullName);
        payload.put("user_id", account.userId);

        Request request = new Request.Builder()
                .url(normalizeBaseUrl(baseUrl) + "/api/register_account")
                .post(RequestBody.create(payload.toString(), JSON))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + deviceToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new BridgeException("Enregistrement serveur impossible (" + response.code() + ")", response.code());
            }
        }
    }

    public BridgeState fetchState(String baseUrl, String deviceToken, String accountId) throws Exception {
        HttpUrl url = HttpUrl.parse(normalizeBaseUrl(baseUrl) + "/api/state")
                .newBuilder()
                .addQueryParameter("account_id", accountId)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + deviceToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BridgeException("Lecture serveur impossible (" + response.code() + ")", response.code());
            }
            JSONObject json = new JSONObject(body);
            return BridgeState.fromJson(json);
        }
    }

    public BridgeState waitForStateChange(
            String baseUrl,
            String deviceToken,
            String accountId,
            String lastSignature,
            boolean missingPunchAlerted,
            int timeoutSeconds
    ) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("account_id", accountId);
        payload.put("last_signature", lastSignature == null ? "" : lastSignature);
        payload.put("last_missing", missingPunchAlerted);
        payload.put("timeout", timeoutSeconds);

        Request request = new Request.Builder()
                .url(normalizeBaseUrl(baseUrl) + "/api/wait_state")
                .post(RequestBody.create(payload.toString(), JSON))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + deviceToken)
                .build();

        try (Response response = waitHttpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BridgeException("Attente serveur impossible (" + response.code() + ")", response.code());
            }
            JSONObject json = new JSONObject(body);
            return BridgeState.fromJson(json);
        }
    }

    private String normalizeBaseUrl(String baseUrl) throws IOException {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || !"https".equalsIgnoreCase(parsed.scheme()) || parsed.host().isEmpty()) {
            throw new IOException("Le relais doit utiliser une adresse HTTPS valide.");
        }
        return parsed.newBuilder().query(null).fragment(null).build().toString().replaceAll("/$", "");
    }

    public static class BridgeState {
        public final String accountId;
        public final String fullName;
        public final String lastPunchSignature;
        public final String lastPunchTime;
        public final String lastPunchKind;
        public final String bankHours;
        public final String todayDate;
        public final List<String> todayShifts;
        public final boolean missingPunch;
        public final long pendingOutSinceEpochMs;
        public final boolean timeout;

        public BridgeState(
                String accountId,
                String fullName,
                String lastPunchSignature,
                String lastPunchTime,
                String lastPunchKind,
                String bankHours,
                String todayDate,
                List<String> todayShifts,
                boolean missingPunch,
                long pendingOutSinceEpochMs,
                boolean timeout
        ) {
            this.accountId = accountId;
            this.fullName = fullName;
            this.lastPunchSignature = lastPunchSignature;
            this.lastPunchTime = lastPunchTime;
            this.lastPunchKind = lastPunchKind;
            this.bankHours = bankHours;
            this.todayDate = todayDate;
            this.todayShifts = todayShifts;
            this.missingPunch = missingPunch;
            this.pendingOutSinceEpochMs = pendingOutSinceEpochMs;
            this.timeout = timeout;
        }

        public static BridgeState fromJson(JSONObject json) {
            JSONArray shiftsArray = json.optJSONArray("today_shifts");
            List<String> shifts = new ArrayList<>();
            if (shiftsArray != null) {
                for (int i = 0; i < shiftsArray.length(); i++) {
                    shifts.add(shiftsArray.optString(i));
                }
            }
            return new BridgeState(
                    json.optString("account_id"),
                    json.optString("full_name"),
                    json.optString("last_punch_signature"),
                    json.optString("last_punch_time"),
                    json.optString("last_punch_kind", "unknown"),
                    json.optString("bank_hours"),
                    json.optString("today"),
                    shifts,
                    json.optBoolean("missing_punch", false),
                    json.optLong("pending_out_since", 0L),
                    json.optBoolean("timeout", false)
            );
        }
    }

    public static class BridgeException extends IOException {
        public final int statusCode;

        public BridgeException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
