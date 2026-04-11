package com.infopunch.checker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String APP_ENTRY_URL = "https://app.info-punch.com";
    private static final int REQUEST_NOTIFICATIONS = 1002;
    public static final String EXTRA_ACCOUNT_ID = "account_id";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final InfoPunchClient infoPunchClient = new InfoPunchClient();

    private EditText companyCodeInput;
    private EditText nipInput;
    private Button connectButton;
    private ProgressBar progressBar;
    private TextView statusView;
    private View loginContainer;
    private View menuContainer;
    private TextView welcomeView;
    private TextView placeholderView;
    private Button menuHoursButton;
    private Button menuBankButton;
    private Button menuTimesheetButton;
    private Button menuSettingsButton;
    private View updateBanner;
    private TextView updateBannerText;
    private Button updateBannerButton;

    private SessionManager sessionManager;
    private boolean hasUnlockedCurrentSession = false;
    private PunchRealtimeMonitor realtimeMonitor;
    private AppUpdateManager appUpdateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        companyCodeInput = findViewById(R.id.companyCodeInput);
        nipInput = findViewById(R.id.nipInput);
        connectButton = findViewById(R.id.connectButton);
        progressBar = findViewById(R.id.progressBar);
        statusView = findViewById(R.id.statusView);
        loginContainer = findViewById(R.id.loginContainer);
        menuContainer = findViewById(R.id.menuContainer);
        welcomeView = findViewById(R.id.welcomeView);
        placeholderView = findViewById(R.id.placeholderView);
        menuHoursButton = findViewById(R.id.menuHoursButton);
        menuBankButton = findViewById(R.id.menuBankButton);
        menuTimesheetButton = findViewById(R.id.menuTimesheetButton);
        menuSettingsButton = findViewById(R.id.menuSettingsButton);
        updateBanner = findViewById(R.id.updateBanner);
        updateBannerText = findViewById(R.id.updateBannerText);
        updateBannerButton = findViewById(R.id.updateBannerButton);

        try {
            sessionManager = new SessionManager(this);
            if (BuildConfig.EXTERNAL_UPDATES_ENABLED) {
                appUpdateManager = new AppUpdateManager(this);
            }
        } catch (Exception exception) {
            showMessage("Impossible d'initialiser le stockage securise.");
            finish();
            return;
        }

        NotificationHelper.ensureChannel(this);
        realtimeMonitor = new PunchRealtimeMonitor(this, this::showMessage);

        loadDefaults();
        setupActions();
        handleIntentAccount(getIntent());
        bindUpdateBanner();
        scheduleUpdateCheckIfNeeded();

        if (sessionManager.hasSession()) {
            showLockedWaitingState();
        } else {
            showLoginScreen();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (realtimeMonitor != null) {
            realtimeMonitor.start();
        }
        SessionManager.SessionData session = sessionManager.getSession();
        if (session == null || hasUnlockedCurrentSession) {
            return;
        }
        if (session.protectionEnabled) {
            promptForUnlockAndConnect(session);
        } else {
            autoReconnect(session);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentAccount(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (realtimeMonitor != null) {
            realtimeMonitor.stop();
            realtimeMonitor = new PunchRealtimeMonitor(this, this::showMessage);
        }
    }

    private void setupActions() {
        connectButton.setOnClickListener(v -> connect());
        menuHoursButton.setOnClickListener(v -> openWorkedHours());
        menuBankButton.setOnClickListener(v -> showPlaceholderSection(getString(R.string.placeholder_coming_soon)));
        menuTimesheetButton.setOnClickListener(v -> showPlaceholderSection(getString(R.string.placeholder_coming_soon)));
        menuSettingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        updateBannerButton.setOnClickListener(v -> openBestUpdateAction());
    }

    private void loadDefaults() {
        SessionManager.SessionData session = sessionManager.getSession();
        if (session != null) {
            companyCodeInput.setText(session.companyCode);
            nipInput.setText(session.nip);
            return;
        }
        companyCodeInput.setText("");
        nipInput.setText("");
    }

    private void connect() {
        String companyCode = companyCodeInput.getText().toString().trim();
        String nip = nipInput.getText().toString().trim();

        if (companyCode.isEmpty() || nip.isEmpty()) {
            showMessage("Le code de compagnie et le NIP sont requis.");
            return;
        }

        boolean isFirstConnection = !sessionManager.hasSession();
        connectInternal(APP_ENTRY_URL, companyCode, nip, isFirstConnection);
    }

    private void connectInternal(String apiEntryUrl, String companyCode, String nip, boolean askSecurityPrompt) {
        setLoading(true);
        updateStatus("Connexion en cours...");

        executorService.execute(() -> {
            try {
                InfoPunchClient.LoginResult result = infoPunchClient.loginAndFetchUser(apiEntryUrl, companyCode, nip);
                runOnUiThread(() -> {
                    setLoading(false);
                    hasUnlockedCurrentSession = true;
                    storeSession(result, companyCode, nip);
                    showAuthenticatedHome(result.user.optString("Name", ""));
                    if (askSecurityPrompt) {
                        promptEnableSecurity();
                    }
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    updateStatus("Non connecte");
                    showLoginScreen();
                    showMessage(exception.getMessage() != null ? exception.getMessage() : "Erreur de connexion.");
                });
            }
        });
    }

    private void storeSession(InfoPunchClient.LoginResult result, String companyCode, String nip) {
        String accountId = companyCode + ":" + nip;
        SessionManager.SessionData current = sessionManager.getSession();
        boolean protectionEnabled = current != null && current.protectionEnabled;

        sessionManager.saveSession(
                result.resolvedApiUrl,
                companyCode,
                nip,
                protectionEnabled,
                result.user.optString("Name", ""),
                result.user.optString("UserId", ""),
                result.sitePath,
                result.parameters.optString("hoursWorkedLink", "")
        );
        SessionManager.SessionData stored = sessionManager.findAccount(accountId);
        if (stored != null && (stored.lastPunchSignature == null || stored.lastPunchSignature.isEmpty())) {
            sessionManager.setLastPunchSignature(accountId, buildPunchSignature(result.user.optJSONObject("LastPunch")));
            sessionManager.setLastPunchTime(accountId, result.user.optJSONObject("LastPunch") != null
                    ? result.user.optJSONObject("LastPunch").optString("CheckTime", "")
                    : "");
        }
        PunchMonitorScheduler.schedule(this);
        PunchWidgetProvider.refreshAll(this);
        requestNotificationPermissionIfNeeded();
    }

    private void autoReconnect(SessionManager.SessionData session) {
        if (progressBar.getVisibility() == View.VISIBLE) {
            return;
        }
        connectInternal(session.apiUrl, session.companyCode, session.nip, false);
    }

    private void showLoginScreen() {
        loginContainer.setVisibility(View.VISIBLE);
        menuContainer.setVisibility(View.GONE);
        updateStatus("Non connecte");
    }

    private void showLockedWaitingState() {
        loginContainer.setVisibility(View.GONE);
        menuContainer.setVisibility(View.GONE);
        updateStatus("Verification de session...");
    }

    private void showAuthenticatedHome(String fullName) {
        loginContainer.setVisibility(View.GONE);
        menuContainer.setVisibility(View.VISIBLE);
        SessionManager.SessionData session = sessionManager.getSession();
        String suffix = session != null && sessionManager.getAccounts().size() > 1
                ? " (" + session.companyCode + " / " + session.nip + ")"
                : "";
        welcomeView.setText(getString(R.string.welcome_prefix) + " " + fullName + suffix);
        updateStatus("Connecte");
        placeholderView.setText(getString(R.string.menu_instruction));
        bindUpdateBanner();
    }

    private void handleIntentAccount(Intent intent) {
        if (intent == null) {
            return;
        }
        String accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID);
        if (accountId != null && !accountId.trim().isEmpty()) {
            sessionManager.setActiveAccount(accountId);
        }
    }

    private void showPlaceholderSection(String message) {
        placeholderView.setText(message);
    }

    private void openWorkedHours() {
        SessionManager.SessionData session = sessionManager.getSession();
        if (session == null) {
            showMessage("Aucune session active.");
            showLoginScreen();
            return;
        }
        if (session.sitePath == null || session.sitePath.trim().isEmpty()
                || session.hoursLink == null || session.hoursLink.trim().isEmpty()) {
            showPlaceholderSection(getString(R.string.hours_unavailable));
            return;
        }
        startActivity(new Intent(this, HoursActivity.class));
    }

    private void promptEnableSecurity() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.security_dialog_title))
                .setMessage(getString(R.string.security_dialog_message))
                .setPositiveButton(getString(R.string.activate), (dialog, which) -> enableSecurityProtection())
                .setNegativeButton(getString(R.string.not_now), null)
                .show();
    }

    private void enableSecurityProtection() {
        if (!canUseSecureUnlock()) {
            showMessage("Aucune biometrie ni securite d'appareil disponible.");
            return;
        }
        sessionManager.setProtectionEnabled(true);
        showMessage("Protection activee.");
    }

    private void setLoading(boolean isLoading) {
        progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        connectButton.setEnabled(!isLoading);
        menuHoursButton.setEnabled(!isLoading);
        menuBankButton.setEnabled(!isLoading);
        menuTimesheetButton.setEnabled(!isLoading);
        menuSettingsButton.setEnabled(!isLoading);
    }

    private void updateStatus(String status) {
        statusView.setText(getString(R.string.status_prefix) + " " + status);
    }

    private boolean canUseSecureUnlock() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        int result = biometricManager.canAuthenticate(authenticators);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void promptForUnlockAndConnect(SessionManager.SessionData session) {
        if (!canUseSecureUnlock()) {
            updateStatus("Protection indisponible, connexion sans verrou");
            autoReconnect(session);
            return;
        }

        BiometricPrompt biometricPrompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        autoReconnect(session);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        updateStatus("Verrouille");
                        if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                && errorCode != BiometricPrompt.ERROR_USER_CANCELED
                                && errorCode != BiometricPrompt.ERROR_CANCELED) {
                            showMessage(errString.toString());
                        }
                        showLoginScreen();
                    }
                }
        );

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Debloquer Info-Punch")
                .setSubtitle("Utilise ta biometrie ou le code de verrouillage du telephone")
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build();

        updateStatus("En attente de deverrouillage");
        biometricPrompt.authenticate(promptInfo);
    }

    private void showMessage(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    private void bindUpdateBanner() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED || appUpdateManager == null || updateBanner == null) {
            if (updateBanner != null) {
                updateBanner.setVisibility(View.GONE);
            }
            return;
        }
        AppUpdateManager.UpdateState state = appUpdateManager.getState();
        if (!state.updateAvailable) {
            updateBanner.setVisibility(View.GONE);
            return;
        }
        updateBanner.setVisibility(View.VISIBLE);
        boolean downloaded = appUpdateManager.hasDownloadedApk();
        updateBannerText.setText(downloaded
                ? "Mise a jour " + state.latestVersion + " prete a installer."
                : "Mise a jour " + state.latestVersion + " disponible sur GitHub.");
        updateBannerButton.setText(downloaded ? "Installer" : "Voir");
    }

    private void openBestUpdateAction() {
        try {
            if (appUpdateManager == null) {
                return;
            }
            Intent intent = appUpdateManager.buildBestActionIntent();
            if (intent == null) {
                showMessage("Aucune action de mise a jour disponible.");
                return;
            }
            startActivity(intent);
        } catch (Exception exception) {
            showMessage("Ouverture de la mise a jour impossible.");
        }
    }

    private void scheduleUpdateCheckIfNeeded() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            return;
        }
        UpdateScheduler.schedule(this);
        if (appUpdateManager == null || !appUpdateManager.shouldCheckNow()) {
            return;
        }
        executorService.execute(() -> {
            try {
                appUpdateManager.checkForUpdates(true);
                runOnUiThread(this::bindUpdateBanner);
                PunchWidgetProvider.refreshAll(this);
            } catch (Exception ignored) {
            }
        });
    }

    private String buildPunchSignature(org.json.JSONObject lastPunch) {
        if (lastPunch == null) {
            return "";
        }
        return lastPunch.optString("CheckTime", "")
                + "|"
                + lastPunch.optString("CheckType", "")
                + "|"
                + lastPunch.optString("Note", "");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdownNow();
    }
}
