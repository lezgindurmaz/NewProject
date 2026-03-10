package com.droidsu.manager;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class BinaryUtils {
    public static void extractBinaries(Context context) {
        String[] binaries = {"kpatch", "magiskpolicy"};
        String abi = getBestAbi();
        for (String bin : binaries) {
            File outFile = new File(context.getFilesDir(), bin);
            try (InputStream is = context.getAssets().open("bin/" + abi + "/" + bin);
                 OutputStream os = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                outFile.setExecutable(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static String getBestAbi() {
        // Simple logic for simulation, in real app use Build.SUPPORTED_ABIS
        return "arm64-v8a";
    }
}
