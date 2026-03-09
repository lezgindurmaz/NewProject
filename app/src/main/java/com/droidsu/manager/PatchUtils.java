package com.droidsu.manager;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class PatchUtils {
    public static String patchBoot(Context context, String bootPath, String superKey) {
        String kpatch = new File(context.getFilesDir(), "kpatch").getAbsolutePath();
        String patchedBoot = new File("/sdcard/Download", "patched_boot.img").getAbsolutePath();

        try {
            // kpatch patch -i boot.img -o patched_boot.img -k superkey
            ProcessBuilder pb = new ProcessBuilder(kpatch, "patch", "-i", bootPath, "-o", patchedBoot, "-k", superKey);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            p.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
