package com.example.rootdetector;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
public class MainActivity extends AppCompatActivity {
    private TextView statusTextView;
    private ProgressBar progressBar;
    private LinearLayout resultsLinearLayout;
    private List<RootDetectorUtil.CheckResult> allResults = new ArrayList<>();
    private int totalChecks = 0;
    private int checksCompleted = 0;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusTextView = findViewById(R.id.status_textview);
        progressBar = findViewById(R.id.progress_bar);
        resultsLinearLayout = findViewById(R.id.results_linearlayout);
        startRootDetection();
    }
    private void startRootDetection() {
        progressBar.setVisibility(View.VISIBLE);
        statusTextView.setText("Running checks...");
        List<RootDetectorUtil.CheckResult> syncResults = RootDetectorUtil.performSyncChecks();
        allResults.addAll(syncResults);
        totalChecks = syncResults.size() + 1; // +1 for the async integrity check
        checksCompleted = syncResults.size();
        RootDetectorUtil.performIntegrityCheck(this, result -> {
            allResults.add(result);
            checksCompleted++;
            if (checksCompleted >= totalChecks) {
                runOnUiThread(this::updateUIWithResults);
            }
        });
    }
    private void updateUIWithResults() {
        progressBar.setVisibility(View.GONE);
        boolean isRooted = false;
        List<RootDetectorUtil.CheckResult> failedChecks = new ArrayList<>();
        for (RootDetectorUtil.CheckResult result : allResults) {
            if (!result.passed) {
                isRooted = true;
                failedChecks.add(result);
            }
        }
        if (isRooted) {
            statusTextView.setText("This Environment is Abnormal.");
            statusTextView.setTextColor(ContextCompat.getColor(this, R.color.status_red));
            for (RootDetectorUtil.CheckResult failedCheck : failedChecks) {
                TextView resultView = new TextView(this);
                resultView.setText(String.format("• %s: %s", failedCheck.checkName, failedCheck.details));
                resultView.setTextSize(16);
                resultsLinearLayout.addView(resultView);
            }
        } else {
            statusTextView.setText("This Environment is Normal.");
            statusTextView.setTextColor(ContextCompat.getColor(this, R.color.status_green));
        }
    }
}
