package com.example.rootdetector;
import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
public class MainActivity extends AppCompatActivity {
    private TextView statusTextView;
    private ProgressBar progressBar;
    private LinearLayout resultsLinearLayout;
    private final List<RootDetectorUtil.CheckResult> allResults = new ArrayList<>();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusTextView = findViewById(R.id.status_textview);
        progressBar = findViewById(R.id.progress_bar);
        resultsLinearLayout = findViewById(R.id.results_linearlayout);
        runRootDetection();
    }
    private void runRootDetection() {
        progressBar.setVisibility(View.VISIBLE);
        statusTextView.setText("Analyzing...");
        resultsLinearLayout.removeAllViews();
        allResults.clear();
        new Thread(() -> {
            List<RootDetectorUtil.CheckResult> syncResults = RootDetectorUtil.performSyncChecks();
            allResults.addAll(syncResults);
            runOnUiThread(() -> {
                for (RootDetectorUtil.CheckResult result : syncResults) {
                    addResultView(result);
                }
            });
            RootDetectorUtil.performIntegrityCheck(this, result -> {
                allResults.add(result);
                updateUIWithResults();
            });
        }).start();
    }
    private void updateUIWithResults() {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            resultsLinearLayout.removeAllViews();
            boolean isNormal = true;
            for (RootDetectorUtil.CheckResult result : allResults) {
                if (!result.passed) {
                    isNormal = false;
                }
                addResultView(result);
            }
            if (isNormal) {
                statusTextView.setText("This Environment is Normal");
                statusTextView.setTextColor(Color.GREEN);
            } else {
                statusTextView.setText("This Environment is Abnormal");
                statusTextView.setTextColor(Color.RED);
            }
        });
    }
    private void addResultView(RootDetectorUtil.CheckResult result) {
        TextView resultTextView = new TextView(this);
        resultTextView.setText(result.checkName + ": " + (result.passed ? "Passed" : "Failed"));
        resultTextView.setTextColor(result.passed ? Color.GREEN : Color.RED);
        resultTextView.setTextSize(16);
        resultsLinearLayout.addView(resultTextView);
        TextView detailsTextView = new TextView(this);
        detailsTextView.setText(result.details);
        detailsTextView.setTextColor(Color.DKGRAY);
        detailsTextView.setTextSize(14);
        detailsTextView.setPadding(0, 0, 0, 16);
        resultsLinearLayout.addView(detailsTextView);
    }
}
