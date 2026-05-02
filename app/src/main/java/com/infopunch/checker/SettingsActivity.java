package com.infopunch.checker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {
    private static final String APP_ENTRY_URL = "https://app.info-punch.com";
    private static final int REQUEST_NOTIFICATIONS = 2001;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final InfoPunchClient infoPunchClient = new InfoPunchClient();
    private final BridgeClient bridgeClient = new BridgeClient();

    private SessionManager sessionManager;
    private AppUpdateManager appUpdateManager;
    private TextView activeAccountView;
    private LinearLayout accountsContainer;
    private EditText companyCodeInput;
    private EditText nipInput;
    private SwitchMaterial biometricSwitch;
    private SwitchMaterial notificationsSwitch;
    private SwitchMaterial soundSwitch;
    private SwitchMaterial realtimeSwitch;
    private SwitchMaterial backgroundSwitch;
    private SwitchMaterial ultraFastSwitch;
    private EditText bridgeUrlInput;
    private EditText bridgeTokenInput;
    private TextView ringtoneValueView;
    private TextView updateStatusView;
    private SwitchMaterial autoUpdateSwitch;
    private Spinner themeSpinner;
    private boolean updating = false;

    private final ActivityResultLauncher<Intent> ringtonePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                Uri uri = result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                sessionManager.setNotificationRingtone(uri != null ? uri.toString() : "");
                bindValues();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ThemeManager.apply(this);

        activeAccountView = findViewById(R.id.activeAccountView);
        accountsContainer = findViewById(R.id.accountsContainer);
        companyCodeInput = findViewById(R.id.companyCodeInput);
        nipInput = findViewById(R.id.nipInput);
        biometricSwitch = findViewById(R.id.biometricSwitch);
        notificationsSwitch = findViewById(R.id.notificationsSwitch);
        soundSwitch = findViewById(R.id.soundSwitch);
        realtimeSwitch = findViewById(R.id.realtimeSwitch);
        backgroundSwitch = findViewById(R.id.backgroundSwitch);
        ultraFastSwitch = findViewById(R.id.ultraFastSwitch);
        bridgeUrlInput = findViewById(R.id.bridgeUrlInput);
        bridgeTokenInput = findViewById(R.id.bridgeTokenInput);
        ringtoneValueView = findViewById(R.id.ringtoneValueView);
        updateStatusView = findViewById(R.id.updateStatusView);
        autoUpdateSwitch = findViewById(R.id.autoUpdateSwitch);
        themeSpinner = findViewById(R.id.themeSpinner);
        View updateSection = findViewById(R.id.updateSection);
        View bridgeSection = findViewById(R.id.bridgeSection);
        Button addAccountButton = findViewById(R.id.addAccountButton);
        Button pickRingtoneButton = findViewById(R.id.pickRingtoneButton);
        Button resetRingtoneButton = findViewById(R.id.resetRingtoneButton);
        Button saveBridgeButton = findViewById(R.id.saveBridgeButton);
        Button registerBridgeButton = findViewById(R.id.registerBridgeButton);
        Button checkUpdatesButton = findViewById(R.id.checkUpdatesButton);
        Button installUpdateButton = findViewById(R.id.installUpdateButton);
        Button logoutButton = findViewById(R.id.logoutButton);

        try {
            sessionManager = new SessionManager(this);
            if (BuildConfig.EXTERNAL_UPDATES_ENABLED) {
                appUpdateManager = new AppUpdateManager(this);
                UpdateScheduler.schedule(this);
            }
        } catch (Exception exception) {
            finish();
            return;
        }

        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            updateSection.setVisibility(View.GONE);
        }
        if (!BuildConfig.ULTRA_FAST_BRIDGE_ENABLED) {
            sessionManager.setUltraFastEnabled(false);
            ultraFastSwitch.setVisibility(View.GONE);
            bridgeSection.setVisibility(View.GONE);
        }

        setupThemeSpinner();
        bindValues();

        biometricSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updating) {
                return;
            }
            if (isChecked && !canUseSecureUnlock()) {
                bindValues();
                showMessage("Aucune biometrie ni securite d'appareil disponible.");
                return;
            }
            sessionManager.setProtectionEnabled(isChecked);
        });

        notificationsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updating) {
                return;
            }
            sessionManager.setNotificationsEnabled(isChecked);
            if (isChecked) {
                requestNotificationPermissionIfNeeded();
            }
        });

        soundSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updating) {
                return;
            }
            sessionManager.setNotificationSoundEnabled(isChecked);
        });

        realtimeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updating) {
                return;
            }
            sessionManager.setRealtimeMonitorEnabled(isChecked);
        });

        backgroundSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updating) {
                return;
            }
            sessionManager.setBackgroundMonitorEnabled(isChecked);
            if (isChecked) {
                PunchMonitorScheduler.schedule(this);
            } else if (!sessionManager.hasAnyBackgroundMonitorEnabled()) {
                PunchMonitorScheduler.cancel(this);
            }
        });

        ultraFastSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updating) {
                return;
            }
            if (!BuildConfig.ULTRA_FAST_BRIDGE_ENABLED) {
                sessionManager.setUltraFastEnabled(false);
                bindValues();
                return;
            }
            sessionManager.setUltraFastEnabled(isChecked);
            if (isChecked) {
                saveBridgeSettings();
                if (sessionManager.getBridgeUrl().isEmpty() || sessionManager.getBridgeToken().isEmpty()) {
                    sessionManager.setUltraFastEnabled(false);
                    bindValues();
                    showMessage("Associe d'abord l'appareil au serveur Linux.");
                    return;
                }
                try {
                    UltraFastMonitorService.start(this);
                } catch (Exception exception) {
                    sessionManager.setUltraFastEnabled(false);
                    bindValues();
                    showMessage("Impossible de demarrer le mode ultra-rapide: " + exception.getClass().getSimpleName());
                }
            } else {
                UltraFastMonitorService.stop(this);
            }
        });

        autoUpdateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updating) {
                return;
            }
            if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
                sessionManager.setAutoUpdateEnabled(false);
                bindValues();
                return;
            }
            sessionManager.setAutoUpdateEnabled(isChecked);
        });

        themeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (updating) {
                    return;
                }
                sessionManager.setThemeName(themeValueForPosition(position));
                ThemeManager.apply(SettingsActivity.this);
                PunchWidgetProvider.refreshAll(SettingsActivity.this);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        addAccountButton.setOnClickListener(v -> addOrUpdateAccount());
        pickRingtoneButton.setOnClickListener(v -> pickRingtone());
        resetRingtoneButton.setOnClickListener(v -> {
            sessionManager.setNotificationRingtone("");
            bindValues();
        });
        saveBridgeButton.setOnClickListener(v -> {
            saveBridgeSettings();
            showMessage("Serveur Linux enregistre.");
        });
        registerBridgeButton.setOnClickListener(v -> registerCurrentAccountToBridge());
        checkUpdatesButton.setOnClickListener(v -> checkUpdatesNow());
        installUpdateButton.setOnClickListener(v -> openBestUpdateAction());
        logoutButton.setOnClickListener(v -> disconnectCurrentAccount());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.apply(this);
    }

    private void bindValues() {
        updating = true;
        SessionManager.SessionData current = sessionManager.getSession();
        if (current == null) {
            activeAccountView.setText("Aucun compte actif");
            accountsContainer.removeAllViews();
            updating = false;
            return;
        }

        activeAccountView.setText(current.fullName + " (" + current.companyCode + " / " + current.nip + ")");
        companyCodeInput.setText("");
        nipInput.setText("");
        bridgeUrlInput.setText(sessionManager.getBridgeUrl());
        bridgeTokenInput.setText("");
        bridgeTokenInput.setHint(sessionManager.getBridgeToken().isEmpty()
                ? getString(R.string.settings_bridge_token)
                : "Appareil deja associe - entre un code pour re-associer");
        biometricSwitch.setChecked(current.protectionEnabled);
        notificationsSwitch.setChecked(current.notificationsEnabled);
        soundSwitch.setChecked(current.notificationSoundEnabled);
        realtimeSwitch.setChecked(current.realtimeMonitorEnabled);
        backgroundSwitch.setChecked(current.backgroundMonitorEnabled);
        ultraFastSwitch.setChecked(sessionManager.isUltraFastEnabled());
        autoUpdateSwitch.setChecked(sessionManager.isAutoUpdateEnabled());
        ringtoneValueView.setText(resolveRingtoneTitle(current.notificationRingtone));
        updateStatusView.setText(buildUpdateStatusText());
        themeSpinner.setSelection(positionForTheme(sessionManager.getThemeName()));
        renderAccounts(sessionManager.getAccounts(), current.accountId);
        updating = false;
    }

    private void renderAccounts(List<SessionManager.SessionData> accounts, String activeAccountId) {
        accountsContainer.removeAllViews();
        for (SessionManager.SessionData account : accounts) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(14, 12, 14, 12);
            card.setBackgroundResource(R.drawable.bg_card_secondary);

            TextView labelView = new TextView(this);
            String label = account.fullName + " (" + account.companyCode + " / " + account.nip + ")";
            if (account.accountId.equals(activeAccountId)) {
                label = "Compte par defaut - " + label;
            }
            labelView.setText(label);
            labelView.setTextColor(getColor(R.color.text_primary));
            labelView.setTextSize(15f);
            card.addView(labelView);

            if (!account.accountId.equals(activeAccountId)) {
                Button defaultButton = new Button(this);
                defaultButton.setText("Definir par defaut");
                defaultButton.setAllCaps(false);
                defaultButton.setBackgroundResource(R.drawable.bg_button_secondary);
                defaultButton.setTextColor(getColor(R.color.text_primary));
                defaultButton.setOnClickListener(v -> {
                    sessionManager.setActiveAccount(account.accountId);
                    bindValues();
                });
                card.addView(defaultButton);
            }

            SwitchMaterial accountNotifications = new SwitchMaterial(this);
            accountNotifications.setText("Notifications");
            accountNotifications.setTextColor(getColor(R.color.text_primary));
            accountNotifications.setChecked(account.notificationsEnabled);
            accountNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> sessionManager.setAccountNotificationsEnabled(account.accountId, isChecked));
            card.addView(accountNotifications);

            SwitchMaterial accountSound = new SwitchMaterial(this);
            accountSound.setText("Son");
            accountSound.setTextColor(getColor(R.color.text_primary));
            accountSound.setChecked(account.notificationSoundEnabled);
            accountSound.setOnCheckedChangeListener((buttonView, isChecked) -> sessionManager.setAccountNotificationSoundEnabled(account.accountId, isChecked));
            card.addView(accountSound);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = 8;
            card.setLayoutParams(params);
            accountsContainer.addView(card);
        }
    }

    private void setupThemeSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"Clair", "Sombre", "Rouge", "Bleu", "Vert", "Gris"}
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(adapter);
    }

    private String themeValueForPosition(int position) {
        switch (position) {
            case 0: return ThemeManager.THEME_LIGHT;
            case 1: return ThemeManager.THEME_DARK;
            case 2: return ThemeManager.THEME_RED;
            case 3: return ThemeManager.THEME_BLUE;
            case 5: return ThemeManager.THEME_GRAY;
            case 4:
            default: return ThemeManager.THEME_GREEN;
        }
    }

    private int positionForTheme(String theme) {
        if (ThemeManager.THEME_LIGHT.equals(theme)) return 0;
        if (ThemeManager.THEME_DARK.equals(theme)) return 1;
        if (ThemeManager.THEME_RED.equals(theme)) return 2;
        if (ThemeManager.THEME_BLUE.equals(theme)) return 3;
        if (ThemeManager.THEME_GRAY.equals(theme)) return 5;
        return 4;
    }

    private void addOrUpdateAccount() {
        String companyCode = companyCodeInput.getText().toString().trim();
        String nip = nipInput.getText().toString().trim();
        if (companyCode.isEmpty() || nip.isEmpty()) {
            showMessage("Le code de compagnie et le NIP sont requis.");
            return;
        }

        executorService.execute(() -> {
            try {
                InfoPunchClient.LoginResult result = infoPunchClient.loginAndFetchUser(APP_ENTRY_URL, companyCode, nip);
                runOnUiThread(() -> {
                    SessionManager.SessionData previous = sessionManager.findAccount(companyCode + ":" + nip);
                    boolean protectionEnabled = previous != null && previous.protectionEnabled;
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
                    SessionManager.SessionData updated = sessionManager.findAccount(companyCode + ":" + nip);
                    if (updated != null && (updated.lastPunchSignature == null || updated.lastPunchSignature.isEmpty())) {
                        sessionManager.setLastPunchSignature(updated.accountId, buildPunchSignature(result.user.optJSONObject("LastPunch")));
                        if (result.user.optJSONObject("LastPunch") != null) {
                            sessionManager.setLastPunchTime(updated.accountId, result.user.optJSONObject("LastPunch").optString("CheckTime", ""));
                        }
                    }
                    PunchMonitorScheduler.schedule(this);
                    bindValues();
                    showMessage("Compte connecte.");
                });
            } catch (Exception exception) {
                runOnUiThread(() -> showMessage(exception.getMessage() != null ? exception.getMessage() : "Connexion impossible."));
            }
        });
    }

    private void pickRingtone() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        String current = sessionManager.getNotificationRingtone();
        if (current != null && !current.isEmpty()) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current));
        }
        ringtonePickerLauncher.launch(intent);
    }

    private void saveBridgeSettings() {
        sessionManager.setBridgeUrl(bridgeUrlInput.getText().toString().trim());
    }

    private void checkUpdatesNow() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED || appUpdateManager == null) {
            showMessage("Les mises a jour GitHub externes ne sont pas actives sur cette version.");
            return;
        }
        executorService.execute(() -> {
            try {
                appUpdateManager.checkForUpdates(true);
                runOnUiThread(() -> {
                    bindValues();
                    PunchWidgetProvider.refreshAll(this);
                    showMessage(sessionManager.isUpdateAvailable()
                            ? "Mise a jour detectee."
                            : "Aucune mise a jour disponible.");
                });
            } catch (Exception exception) {
                runOnUiThread(() -> showMessage(exception.getMessage() != null
                        ? exception.getMessage()
                        : "Verification des mises a jour impossible."));
            }
        });
    }

    private void openBestUpdateAction() {
        try {
            if (!BuildConfig.EXTERNAL_UPDATES_ENABLED || appUpdateManager == null) {
                showMessage("Cette version utilise le canal de mise a jour Google Play.");
                return;
            }
            Intent intent = appUpdateManager.buildBestActionIntent();
            if (intent == null) {
                showMessage("Aucune mise a jour telechargee ou disponible.");
                return;
            }
            startActivity(intent);
        } catch (Exception exception) {
            showMessage("Ouverture de la mise a jour impossible.");
        }
    }

    private void registerCurrentAccountToBridge() {
        if (!BuildConfig.ULTRA_FAST_BRIDGE_ENABLED) {
            showMessage("Le mode ultra-rapide n'est pas disponible sur cette version.");
            return;
        }
        SessionManager.SessionData current = sessionManager.getSession();
        if (current == null) {
            return;
        }
        saveBridgeSettings();
        String bridgeUrl = sessionManager.getBridgeUrl();
        if (bridgeUrl.isEmpty()) {
            showMessage("Entre l'URL du serveur Linux avant la synchronisation.");
            return;
        }
        String existingDeviceToken = sessionManager.getBridgeToken();
        String pairCode = bridgeTokenInput.getText().toString().trim();
        if (existingDeviceToken.isEmpty() && pairCode.length() != 6) {
            showMessage("Entre le code de pairage a 6 chiffres affiche sur Linux.");
            return;
        }

        executorService.execute(() -> {
            try {
                boolean newlyPaired = false;
                String deviceToken = existingDeviceToken;
                if (deviceToken.isEmpty() || pairCode.length() == 6) {
                    deviceToken = bridgeClient.pairDevice(
                            bridgeUrl,
                            pairCode,
                            sessionManager.getDeviceId(),
                            Build.MANUFACTURER + " " + Build.MODEL
                    );
                    sessionManager.setBridgeToken(deviceToken);
                    newlyPaired = true;
                }
                bridgeClient.registerAccount(bridgeUrl, deviceToken, current);
                boolean pairedNow = newlyPaired;
                runOnUiThread(() -> {
                    bridgeTokenInput.setText("");
                    bindValues();
                    showMessage(pairedNow
                            ? "Appareil associe et compte synchronise avec le serveur Linux."
                            : "Compte synchronise avec le serveur Linux.");
                });
            } catch (Exception exception) {
                runOnUiThread(() -> showMessage(exception.getMessage() != null ? exception.getMessage() : "Synchronisation impossible."));
            }
        });
    }

    private void disconnectCurrentAccount() {
        SessionManager.SessionData current = sessionManager.getSession();
        if (current == null) {
            return;
        }
        sessionManager.removeAccount(current.accountId);
        if (!sessionManager.hasAnyBackgroundMonitorEnabled()) {
            PunchMonitorScheduler.cancel(this);
        }
        if (!sessionManager.hasSession()) {
            UltraFastMonitorService.stop(this);
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }
        bindValues();
        showMessage("Compte deconnecte.");
    }

    private String resolveRingtoneTitle(String uriValue) {
        if (uriValue == null || uriValue.isEmpty()) {
            return "Sonnerie systeme par defaut";
        }
        Ringtone ringtone = RingtoneManager.getRingtone(this, Uri.parse(uriValue));
        return ringtone != null ? ringtone.getTitle(this) : "Sonnerie personnalisee";
    }

    private boolean canUseSecureUnlock() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS;
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

    private void showMessage(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    private String buildUpdateStatusText() {
        if (!BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            return "Cette version utilise le canal de mise a jour Google Play.";
        }
        if (appUpdateManager == null) {
            return "Etat des mises a jour indisponible.";
        }
        AppUpdateManager.UpdateState state = appUpdateManager.getState();
        if (!state.updateAvailable) {
            return "Aucune mise a jour detectee pour le moment.";
        }
        if (appUpdateManager.hasDownloadedApk()) {
            return "Mise a jour " + state.latestVersion + " telechargee et prete a installer.";
        }
        return "Mise a jour " + state.latestVersion + " disponible sur GitHub.";
    }

    @Override
    protected void onDestroy() {
        executorService.shutdownNow();
        super.onDestroy();
    }
}
