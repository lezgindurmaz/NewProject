package com.droidsu.manager;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModuleManager {
    public static class ModuleInfo {
        public String id;
        public String name;
        public String version;
        public String author;
        public String description;
        public boolean isEnabled;
        public boolean hasWebUI;
    }

    public static List<ModuleInfo> getInstalledModules() {
        List<ModuleInfo> modules = new ArrayList<>();
        File moduleDir = new File("/data/adb/droidsu/modules");
        if (moduleDir.exists() && moduleDir.isDirectory()) {
            File[] files = moduleDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        modules.add(parseModule(f));
                    }
                }
            }
        }
        return modules;
    }

    private static ModuleInfo parseModule(File dir) {
        ModuleInfo info = new ModuleInfo();
        info.id = dir.getName();
        // In real app, parse module.prop
        info.name = info.id;
        info.version = "1.0";
        info.isEnabled = !new File(dir, "disable").exists();
        info.hasWebUI = new File(dir, "webui").exists();
        return info;
    }
}
