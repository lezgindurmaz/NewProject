package com.droidsu.manager;

import android.content.Context;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class PatchUtils {
    public static String patchBoot(Context context, String uriString, String superKey) {
        String kpatch = new File(context.getFilesDir(), "kpatch").getAbsolutePath();
        String patchedBoot = new File("/sdcard/Download", "patched_boot.img").getAbsolutePath();

        try {
            // Copy URI to internal storage for kpatch to access
            File tempInput = new File(context.getCacheDir(), "input_boot.img");
            Uri uri = Uri.parse(uriString);
            try (InputStream is = context.getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(tempInput)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }

            // kpatch patch -i temp_boot.img -o patched_boot.img -k superkey
            ProcessBuilder pb = new ProcessBuilder(kpatch, "patch", "-i", tempInput.getAbsolutePath(), "-o", patchedBoot, "-k", superKey);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = p.waitFor();

            if (exitCode == 0) {
                return "Successfully patched! Saved to Download/patched_boot.img";
            } else {
                return "Patching failed (Code " + exitCode + "):\n" + output.toString();
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
