package com.infopunch.checker;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AppUpdateManager {
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/butts136/cardinal-punch/releases/latest";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private final Context appContext;
    private final SessionManager sessionManager;
    private final OkHttpClient httpClient = new OkHttpClient();

    public AppUpdateManager(Context context) throws Exception {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager(appContext);
    }

    public UpdateState getState() {
        return new UpdateState(
                sessionManager.isUpdateAvailable(),
                sessionManager.getUpdateLatestVersion(),
                sessionManager.getUpdateReleaseUrl(),
                sessionManager.getUpdateApkUrl(),
                sessionManager.getDownloadedUpdatePath(),
                sessionManager.getUpdateLastCheck(),
                sessionManager.isAutoUpdateEnabled()
        );
    }

    public boolean shouldCheckNow() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            return false;
        }
        long lastCheck = sessionManager.getUpdateLastCheck();
        return lastCheck <= 0 || System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS;
    }

    public UpdateState checkForUpdates(boolean allowAutoDownload) throws Exception {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            sessionManager.setUpdateAvailable(false);
            sessionManager.setDownloadedUpdatePath("");
            return getState();
        }
        Request request = new Request.Builder()
                .url(LATEST_RELEASE_URL)
                .get()
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Cardinal-Punch-Android")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Verification GitHub impossible (" + response.code() + ")");
            }

            JSONObject json = new JSONObject(body);
            String latestVersion = normalizeVersion(json.optString("tag_name", json.optString("name", "")));
            String releaseUrl = json.optString("html_url", "");
            String apkUrl = findApkAssetUrl(json.optJSONArray("assets"));
            boolean updateAvailable = isNewerThanCurrent(latestVersion);

            sessionManager.setUpdateLastCheck(System.currentTimeMillis());
            sessionManager.setUpdateAvailable(updateAvailable);
            sessionManager.setUpdateLatestVersion(latestVersion);
            sessionManager.setUpdateReleaseUrl(releaseUrl);
            sessionManager.setUpdateApkUrl(apkUrl);

            if (!updateAvailable) {
                sessionManager.setDownloadedUpdatePath("");
                return getState();
            }

            if (allowAutoDownload && sessionManager.isAutoUpdateEnabled() && !apkUrl.isEmpty()) {
                String downloadedPath = downloadApk(latestVersion, apkUrl);
                sessionManager.setDownloadedUpdatePath(downloadedPath);
                NotificationHelper.ensureChannel(appContext);
                NotificationHelper.showUpdateNotification(
                        appContext,
                        "Mise a jour " + latestVersion + " telechargee",
                        "Touchez pour installer la nouvelle version.",
                        buildInstallIntent(downloadedPath)
                );
            }

            return getState();
        }
    }

    public String downloadLatestApkIfAvailable() throws Exception {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            throw new IOException("Les mises a jour APK externes ne sont pas actives sur cette version.");
        }
        UpdateState state = getState();
        if (!state.updateAvailable || state.apkUrl == null || state.apkUrl.isEmpty()) {
            throw new IOException("Aucune mise a jour APK telechargeable n'est disponible.");
        }
        String downloadedPath = downloadApk(state.latestVersion, state.apkUrl);
        sessionManager.setDownloadedUpdatePath(downloadedPath);
        return downloadedPath;
    }

    public Intent buildBestActionIntent() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            return null;
        }
        UpdateState state = getState();
        if (state.downloadedApkPath != null && !state.downloadedApkPath.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !appContext.getPackageManager().canRequestPackageInstalls()) {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + appContext.getPackageName())
                );
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return settingsIntent;
            }
            return buildInstallIntent(state.downloadedApkPath);
        }
        if (state.releaseUrl != null && !state.releaseUrl.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(state.releaseUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }
        return null;
    }

    public boolean hasDownloadedApk() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            return false;
        }
        String path = sessionManager.getDownloadedUpdatePath();
        return path != null && !path.isEmpty() && new File(path).exists();
    }

    private String findApkAssetUrl(JSONArray assets) {
        if (assets == null) {
            return "";
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
            if (name.endsWith(".apk")) {
                return asset.optString("browser_download_url", "");
            }
        }
        return "";
    }

    private boolean isNewerThanCurrent(String latestVersion) {
        if (latestVersion == null || latestVersion.isEmpty()) {
            return false;
        }
        String currentVersion = normalizeVersion(getCurrentVersionName());
        return compareVersions(latestVersion, currentVersion) > 0;
    }

    private String getCurrentVersionName() {
        try {
            PackageManager packageManager = appContext.getPackageManager();
            PackageInfo info = packageManager.getPackageInfo(appContext.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "0";
        } catch (Exception ignored) {
            return "0";
        }
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int l = i < leftParts.length ? parseInt(leftParts[i]) : 0;
            int r = i < rightParts.length ? parseInt(rightParts[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String normalizeVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replace("version", "").replace("v", "").trim();
        cleaned = cleaned.replaceAll("[^0-9.]", "");
        return cleaned;
    }

    private String downloadApk(String version, String apkUrl) throws Exception {
        File dir = new File(appContext.getExternalFilesDir(null), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Creation du dossier de mise a jour impossible.");
        }
        String safeVersion = version == null || version.isEmpty() ? "latest" : version.replaceAll("[^0-9.]", "_");
        File destination = new File(dir, "cardinal-punch-" + safeVersion + ".apk");

        Request request = new Request.Builder()
                .url(apkUrl)
                .get()
                .header("Accept", "*/*")
                .header("User-Agent", "Cardinal-Punch-Android")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Telechargement impossible (" + response.code() + ")");
            }
            InputStream input = response.body() != null ? response.body().byteStream() : null;
            if (input == null) {
                throw new IOException("Aucune donnee APK recue.");
            }
            try (FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        }
        return destination.getAbsolutePath();
    }

    public Intent buildInstallIntent(String filePath) {
        File file = new File(filePath);
        Uri uri = FileProvider.getUriForFile(
                appContext,
                appContext.getPackageName() + ".fileprovider",
                file
        );
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static class UpdateState {
        public final boolean updateAvailable;
        public final String latestVersion;
        public final String releaseUrl;
        public final String apkUrl;
        public final String downloadedApkPath;
        public final long lastCheck;
        public final boolean autoDownloadEnabled;

        public UpdateState(
                boolean updateAvailable,
                String latestVersion,
                String releaseUrl,
                String apkUrl,
                String downloadedApkPath,
                long lastCheck,
                boolean autoDownloadEnabled
        ) {
            this.updateAvailable = updateAvailable;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.apkUrl = apkUrl;
            this.downloadedApkPath = downloadedApkPath;
            this.lastCheck = lastCheck;
            this.autoDownloadEnabled = autoDownloadEnabled;
        }
    }
}
