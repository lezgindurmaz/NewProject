package com.droidsu.manager;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        startPatching(uri);
                    }
                }
            }
        );

        BottomNavigationView navView = findViewById(R.id.bottom_nav);
        navView.setOnItemSelectedListener(item -> {
            Fragment selected = null;
            int id = item.getItemId();
            if (id == R.id.nav_home) selected = new HomeFragment();
            else if (id == R.id.nav_superuser) selected = new SuperUserFragment();
            else if (id == R.id.nav_modules) selected = new ModulesFragment();
            else if (id == R.id.nav_settings) selected = new SettingsFragment();

            if (selected != null) {
                getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, selected)
                    .commit();
            }
            return true;
        });

        getSupportFragmentManager().beginTransaction()
            .replace(R.id.container, new HomeFragment())
            .commit();
    }

    public void pickBootImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void startPatching(Uri uri) {
        Toast.makeText(this, "Patching " + uri.getPath(), Toast.LENGTH_SHORT).show();
        // Extract binaries first
        BinaryUtils.extractBinaries(this);
        // Copy URI to internal file if necessary, then call PatchUtils
        new Thread(() -> {
            String result = PatchUtils.patchBoot(this, uri.toString(), "DROIDSU_KEY");
            runOnUiThread(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
        }).start();
    }
}
