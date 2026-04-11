package com.infopunch.checker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    private TextView activeAccountView;
    private LinearLayout accountsContainer;
    private EditText companyCodeInput;
    private EditText nipInput;
    private SwitchMaterial biometricSwitch;
    private SwitchMaterial notificationsSwitch;
    private SwitchMaterial realtimeSwitch;
    private SwitchMaterial backgroundSwitch;
    private SwitchMaterial ultraFastSwitch;
    private EditText bridgeUrlInput;
    private EditText bridgeTokenInput;
    private TextView ringtoneValueView;
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

        activeAccountView = findViewById(R.id.activeAccountView);
        accountsContainer = findViewById(R.id.accountsContainer);
        companyCodeInput = findViewById(R.id.companyCodeInput);
        nipInput = findViewById(R.id.nipInput);
        biometricSwitch = findViewById(R.id.biometricSwitch);
        notificationsSwitch = findViewById(R.id.notificationsSwitch);
        realtimeSwitch = findViewById(R.id.realtimeSwitch);
        backgroundSwitch = findViewById(R.id.backgroundSwitch);
        ultraFastSwitch = findViewById(R.id.ultraFastSwitch);
        bridgeUrlInput = findViewById(R.id.bridgeUrlInput);
        bridgeTokenInput = findViewById(R.id.bridgeTokenInput);
        ringtoneValueView = findViewById(R.id.ringtoneValueView);
        Button addAccountButton = findViewById(R.id.addAccountButton);
        Button pickRingtoneButton = findViewById(R.id.pickRingtoneButton);
        Button resetRingtoneButton = findViewById(R.id.resetRingtoneButton);
        Button saveBridgeButton = findViewById(R.id.saveBridgeButton);
        Button registerBridgeButton = findViewById(R.id.registerBridgeButton);
        Button logoutButton = findViewById(R.id.logoutButton);

        try {
            sessionManager = new SessionManager(this);
        } catch (Exception exception) {
            finish();
            return;
        }

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
        logoutButton.setOnClickListener(v -> disconnectCurrentAccount());
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
        companyCodeInput.setText(current.companyCode);
        nipInput.setText(current.nip);
        bridgeUrlInput.setText(sessionManager.getBridgeUrl());
        bridgeTokenInput.setText("");
        bridgeTokenInput.setHint(sessionManager.getBridgeToken().isEmpty()
                ? getString(R.string.settings_bridge_token)
                : "Appareil deja associe - entre un code pour re-associer");
        biometricSwitch.setChecked(current.protectionEnabled);
        notificationsSwitch.setChecked(current.notificationsEnabled);
        realtimeSwitch.setChecked(current.realtimeMonitorEnabled);
        backgroundSwitch.setChecked(current.backgroundMonitorEnabled);
        ultraFastSwitch.setChecked(sessionManager.isUltraFastEnabled());
        ringtoneValueView.setText(resolveRingtoneTitle(current.notificationRingtone));
        renderAccounts(sessionManager.getAccounts(), current.accountId);
        updating = false;
    }

    private void renderAccounts(List<SessionManager.SessionData> accounts, String activeAccountId) {
        accountsContainer.removeAllViews();
        for (SessionManager.SessionData account : accounts) {
            Button button = new Button(this);
            String label = account.fullName + " (" + account.companyCode + " / " + account.nip + ")";
            if (account.accountId.equals(activeAccountId)) {
                label = "Actif - " + label;
                button.setBackgroundResource(R.drawable.bg_button_primary);
                button.setTextColor(getColor(android.R.color.white));
            } else {
                button.setBackgroundResource(R.drawable.bg_button_secondary);
                button.setTextColor(getColor(R.color.text_primary));
            }
            button.setText(label);
            button.setAllCaps(false);
            button.setOnClickListener(v -> {
                sessionManager.setActiveAccount(account.accountId);
                bindValues();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = 8;
            button.setLayoutParams(params);
            accountsContainer.addView(button);
        }
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

    private void registerCurrentAccountToBridge() {
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

    @Override
    protected void onDestroy() {
        executorService.shutdownNow();
        super.onDestroy();
    }
}
