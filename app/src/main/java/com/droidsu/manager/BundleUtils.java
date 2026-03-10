package com.droidsu.manager;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class BundleUtils {
    public static void installBundledModules(Context context) {
        String[] modules = {
            "zygisk_next.zip", "lsposed.zip", "integrity_box.zip",
            "zygisk_assistant.zip", "tricky_store.zip", "tricky_store_addon.zip"
        };

        File tempDir = new File(context.getCacheDir(), "bundled_modules");
        if (!tempDir.exists()) tempDir.mkdirs();

        for (String mod : modules) {
            File outFile = new File(tempDir, mod);
            try (InputStream is = context.getAssets().open("modules/" + mod);
                 OutputStream os = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                // Real installation logic
                ModuleInstaller.installZip(context, outFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
