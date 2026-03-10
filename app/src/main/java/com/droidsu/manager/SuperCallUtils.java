package com.droidsu.manager;

public class SuperCallUtils {
    static {
        System.loadLibrary("droidsu");
    }

    public static native int nativeSuperCall(long key, int cmd, long arg1, long arg2);
    public static native boolean isKernelPatched();

    // KP Commands
    public static final int KP_CMD_GET_VERSION = 0;
    public static final int KP_CMD_GET_ROOT = 1;
    public static final int KP_CMD_SET_KEY = 2;

    public static boolean requestRoot(long superKey) {
        if (!isKernelPatched()) return false;
        int result = nativeSuperCall(superKey, KP_CMD_GET_ROOT, 0, 0);
        return result == 0;
    }
}
