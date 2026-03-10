package com.droidsu.manager;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModuleInstaller {
    public static String installZip(Context context, File zipFile) {
        File modulesDir = new File("/data/adb/droidsu/modules");
        if (!modulesDir.exists()) modulesDir.mkdirs();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            String moduleId = null;

            // First pass to find module ID
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("module.prop")) {
                    // Extract module.prop and parse ID
                    //moduleId = parseId(is);
                    moduleId = zipFile.getName().replace(".zip", "");
                }
            }

            if (moduleId == null) moduleId = zipFile.getName().replace(".zip", "");

            File targetDir = new File(modulesDir, moduleId);
            if (!targetDir.exists()) targetDir.mkdirs();

            // Reset ZIP stream for second pass (extraction)
            try (ZipInputStream zis2 = new ZipInputStream(new FileInputStream(zipFile))) {
                while ((entry = zis2.getNextEntry()) != null) {
                    File outFile = new File(targetDir, entry.getName());
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = zis2.read(buffer)) != -1) {
                                fos.write(buffer, 0, read);
                            }
                        }
                    }
                }
            }
            return "Module installed: " + moduleId;
        } catch (Exception e) {
            return "Install failed: " + e.getMessage();
        }
    }
}
