package com.droidsu.manager;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView statusText = findViewById(R.id.statusText);
        Button patchButton = findViewById(R.id.patchButton);
        Button installModulesButton = findViewById(R.id.installModulesButton);

        statusText.setText("DroidSU Manager Ready");

        patchButton.setOnClickListener(v -> {
            // Simulated patching
            statusText.setText("Patching boot.img...");
            BinaryUtils.extractBinaries(this);
            String result = PatchUtils.patchBoot(this, "/sdcard/boot.img", "DROIDSU_KEY");
            statusText.setText("Result: " + result);
        });

        installModulesButton.setOnClickListener(v -> {
            statusText.setText("Installing bundled modules...");
            BundleUtils.installBundledModules(this);
            statusText.setText("Modules installed successfully.");
        });
    }
}
