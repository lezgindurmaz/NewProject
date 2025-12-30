package com.example.rootdetector;
import android.content.Context;
import android.os.Build;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityTokenResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import java.util.Base64;
public class RootDetectorUtil {
    public interface IntegrityCheckCallback {
        void onFinished(CheckResult result);
    }
    public static class CheckResult {
        public final String checkName;
        public final boolean passed;
        public final String details;
        public CheckResult(String checkName, boolean passed, String details) {
            this.checkName = checkName;
            this.passed = passed;
            this.details = details;
        }
    }
    public static List<CheckResult> performSyncChecks() {
        List<CheckResult> results = new ArrayList<>();
        results.add(checkSystemProperties());
        results.add(checkSELinuxStatus());
        results.add(checkBootloaderStatus());
        results.add(checkAVBStatus());
        results.add(checkForSuBinary());
        return results;
    }
    public static void performIntegrityCheck(Context context, IntegrityCheckCallback callback) {
        IntegrityManager integrityManager = IntegrityManagerFactory.create(context);
        integrityManager.requestIntegrityToken(IntegrityTokenRequest.builder().setNonce("R00T").build())
            .addOnSuccessListener(response -> {
                String verdict = parseVerdictFromToken(response.token());
                boolean passed = "MEETS_STRONG_INTEGRITY".equals(verdict);
                callback.onFinished(new CheckResult("Google Play Integrity (STRONG)", passed, "Verdict: " + verdict));
            })
            .addOnFailureListener(e -> {
                callback.onFinished(new CheckResult("Google Play Integrity (STRONG)", false, "API call failed: " + e.getMessage()));
            });
    }
    private static String parseVerdictFromToken(String token) {
        try {
            String[] parts = token.split("\.");
            if (parts.length < 2) return "Invalid Token Structure";
            byte[] decodedBytes = Base64.getDecoder().decode(parts[1]);
            String payload = new String(decodedBytes, "UTF-8");
            JSONObject json = new JSONObject(payload);
            JSONObject deviceIntegrity = json.getJSONObject("deviceIntegrity");
            return deviceIntegrity.getString("deviceRecognitionVerdict");
        } catch (Exception e) {
            return "Verdict Parsing Failed: " + e.getMessage();
        }
    }
    private static CheckResult checkSystemProperties() {
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return new CheckResult("Build Tags", false, "Device is signed with test-keys.");
        }
        return new CheckResult("Build Tags", true, "Device is signed with release-keys.");
    }
    private static CheckResult checkSELinuxStatus() {
        try {
            Process process = Runtime.getRuntime().exec("getenforce");
            BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String s = r.readLine();
            if ("Enforcing".equalsIgnoreCase(s)) {
                return new CheckResult("SELinux Status", true, "SELinux is in Enforcing mode.");
            } else {
                return new CheckResult("SELinux Status", false, "SELinux is not Enforcing (Status: " + s + ").");
            }
        } catch (Exception e) {
            return new CheckResult("SELinux Status", false, "Could not check: " + e.getMessage());
        }
    }
    private static CheckResult checkBootloaderStatus() {
        String state = System.getProperty("ro.boot.vbmeta.device_state");
        if ("locked".equalsIgnoreCase(state)) {
            return new CheckResult("Bootloader State", true, "Bootloader is locked.");
        } else {
            return new CheckResult("Bootloader State", false, "Bootloader is unlocked (State: " + state + ").");
        }
    }
    private static CheckResult checkAVBStatus() {
        String state = System.getProperty("ro.boot.verifiedbootstate");
        if ("green".equalsIgnoreCase(state)) {
            return new CheckResult("Android Verified Boot", true, "AVB status is GREEN.");
        } else {
            return new CheckResult("Android Verified Boot", false, "AVB status is not GREEN (State: " + state + ").");
        }
    }
    private static CheckResult checkForSuBinary() {
        String[] paths = {"/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su"};
        for (String path : paths) {
            if (new File(path).exists()) {
                return new CheckResult("su Binary Check", false, "'su' binary found at: " + path);
            }
        }
        return new CheckResult("su Binary Check", true, "No 'su' binary found.");
    }
}
