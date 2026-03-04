#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/kprobes.h>
#include <linux/file.h>
#include <linux/fs.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/sched.h>
#include <linux/version.h>
#include <linux/cred.h>
#include <linux/ptrace.h>

MODULE_LICENSE("GPL");
MODULE_AUTHOR("Jules");
MODULE_DESCRIPTION("Enhanced Zygisk Stealth Hider KPM");

/* Keywords and Paths to Hide */
static const char *hide_keywords[] = { "zygisk", "lsposed", "magisk", "riru", "apatch", "folkpatch", "kpm", NULL };
static const char *hide_paths[] = { "/data/adb/zygisk", "/data/adb/lsposed", "/data/adb/modules", "/data/adb/apatch", NULL };

/* Safety limits */
#define MAX_FILTER_SIZE (128 * 1024) // 128KB limit for filtering

static bool should_hide_for_task(struct task_struct *task) {
    const struct cred *cred = task->cred;
    uid_t uid = from_kuid(&init_user_ns, cred->uid);
    // Hide from non-root apps (UID >= 1000)
    return (uid >= 1000);
}

/*
 * fstatat hook - Using kprobe with safety checks
 */
static int handler_pre_fstatat(struct kprobe *p, struct pt_regs *regs) {
    const char __user *filename;
    char *k_filename;
    int i;
    size_t len;

    if (!should_hide_for_task(current)) return 0;

#if defined(CONFIG_ARM64)
    filename = (const char __user *)regs->regs[1];
#elif defined(CONFIG_X86_64)
    filename = (const char __user *)regs->si;
#else
    return 0; // Unsupported arch
#endif

    k_filename = kmalloc(PATH_MAX, GFP_KERNEL);
    if (!k_filename) return 0;

    len = strncpy_from_user(k_filename, filename, PATH_MAX);
    if (len > 0 && len < PATH_MAX) {
        for (i = 0; hide_paths[i]; i++) {
            if (strstr(k_filename, hide_paths[i])) {
                /*
                 * Safety: Instead of overwriting user memory (risky),
                 * in a production KPM we would override the return value to -ENOENT.
                 * Since kprobe_override_return is not always available, we do a minimal
                 * safe overwrite ONLY if the buffer is large enough for a dummy path.
                 */
                const char *dummy = "/nonexistent_zygisk_hide";
                if (len >= strlen(dummy)) {
                    copy_to_user((void __user *)filename, dummy, strlen(dummy) + 1);
                }
                break;
            }
        }
    }
    kfree(k_filename);
    return 0;
}

/*
 * vfs_read kretprobe with memory safety
 */
static int ret_handler_read(struct kretprobe_instance *ri, struct pt_regs *regs) {
    ssize_t ret = regs_return_value(regs);
    struct file *file;
    char __user *buf;
    char *kbuf;
    int i;

    if (ret <= 0 || ret > MAX_FILTER_SIZE || !should_hide_for_task(current)) return 0;

#if defined(CONFIG_ARM64)
    file = (struct file *)regs->regs[0];
    buf = (char __user *)regs->regs[1];
#elif defined(CONFIG_X86_64)
    file = (struct file *)regs->di;
    buf = (char __user *)regs->si;
#else
    return 0;
#endif

    if (!file || !file->f_path.dentry) return 0;

    const char *name = file->f_path.dentry->d_name.name;
    if (strcmp(name, "maps") == 0 || strcmp(name, "mountinfo") == 0) {
        kbuf = kmalloc(ret, GFP_KERNEL);
        if (!kbuf) return 0;

        if (copy_from_user(kbuf, buf, ret) == 0) {
            bool modified = false;
            char *line = kbuf;
            while (line < kbuf + ret) {
                char *next_line = memchr(line, '\n', kbuf + ret - line);
                size_t line_len = next_line ? (next_line - line + 1) : (kbuf + ret - line);

                for (i = 0; hide_keywords[i]; i++) {
                    if (strnstr(line, hide_keywords[i], line_len)) {
                        memset(line, ' ', line_len);
                        modified = true;
                        break;
                    }
                }
                if (!next_line) break;
                line = next_line + 1;
            }
            if (modified) {
                copy_to_user(buf, kbuf, ret);
            }
        }
        kfree(kbuf);
    }
    return 0;
}

static struct kprobe kp_fstatat = {
    .symbol_name = "vfs_fstatat",
    .pre_handler = handler_pre_fstatat,
};

static struct kprobe kp_openat = {
    .symbol_name = "do_sys_open", // Better for catching open/openat
    .pre_handler = handler_pre_fstatat, // Reuse same logic for path redirect
};

static struct kretprobe rp_read = {
    .kp.symbol_name = "vfs_read",
    .handler = ret_handler_read,
    .maxactive = 64,
};

static int __init zygisk_hide_init(void) {
    int r;
    r = register_kprobe(&kp_fstatat);
    if (r < 0) pr_err("ZygiskHide: kp_fstatat reg failed %d\n", r);

    r = register_kprobe(&kp_openat);
    if (r < 0) pr_err("ZygiskHide: kp_openat reg failed %d\n", r);

    r = register_kretprobe(&rp_read);
    if (r < 0) pr_err("ZygiskHide: rp_read reg failed %d\n", r);

    pr_info("ZygiskHide: Enhanced Stealth KPM Loaded\n");
    return 0;
}

static void __exit zygisk_hide_exit(void) {
    unregister_kprobe(&kp_fstatat);
    unregister_kprobe(&kp_openat);
    unregister_kretprobe(&rp_read);
    pr_info("ZygiskHide: Unloaded\n");
}

module_init(zygisk_hide_init);
module_exit(zygisk_hide_exit);
