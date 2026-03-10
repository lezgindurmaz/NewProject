package com.droidsu.manager;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.util.Scanner;

public class WhitelistUtils {
    private static final String WHITELIST_FILE = "/data/adb/droidsu/whitelist";

    public static void addApp(String packageName) {
        try {
            File dir = new File("/data/adb/droidsu");
            if (!dir.exists()) dir.mkdirs();
            FileWriter fw = new FileWriter(WHITELIST_FILE, true);
            fw.write(packageName + "\n");
            fw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isAllowed(String packageName) {
        try {
            File f = new File(WHITELIST_FILE);
            if (!f.exists()) return false;
            Scanner scanner = new Scanner(f);
            while (scanner.hasNextLine()) {
                if (scanner.nextLine().trim().equals(packageName)) {
                    scanner.close();
                    return true;
                }
            }
            scanner.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
