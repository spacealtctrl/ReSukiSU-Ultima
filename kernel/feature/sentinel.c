#include <linux/anon_inodes.h>
#include <linux/cache.h>
#include <linux/err.h>
#include <linux/fdtable.h>
#include <linux/file.h>
#include <linux/fs.h>
#include <linux/jiffies.h>
#include <linux/mutex.h>
#include <linux/poll.h>
#include <linux/sched.h>
#include <linux/spinlock.h>
#include <linux/string.h>
#include <linux/timekeeping.h>
#include <linux/version.h>
#include <linux/export.h>
#include <linux/cred.h>
#include <linux/kprobes.h>
#include <linux/ptrace.h>

#include "feature/sentinel.h"
#include "infra/event_queue.h"
#include "klog.h" // IWYU pragma: keep
#include "policy/feature.h"
#include "policy/allowlist.h"
#include "compat/kernel_compat.h"
#include "uapi/sentinel.h"

#define SENTINEL_MAX_QUEUED 256
#define SENTINEL_DEDUP_SLOTS 32
#define SENTINEL_DEDUP_WINDOW msecs_to_jiffies(5000)

static bool ksu_sentinel_enabled __read_mostly = false;
static struct ksu_event_queue sentinel_queue;

/* Per-uid burst coalescing so a spinning probe can't flood the queue. */
struct sentinel_dedup_slot {
    uid_t uid;
    __u16 kind;
    bool used;
    unsigned long last;
    __u32 count;
};
static struct sentinel_dedup_slot dedup[SENTINEL_DEDUP_SLOTS];
static DEFINE_SPINLOCK(dedup_lock);

/* ---- fd delivery (mirrors kernel/sulog/fd.c) ---- */
static DEFINE_MUTEX(sentinel_fd_lock);
static bool sentinel_fd_active;

bool ksu_sentinel_is_enabled(void)
{
    return ksu_sentinel_enabled;
}

/*
 * Returns 0 when the probe is coalesced into a recent event (caller should not
 * emit), otherwise the accumulated hit count to put on the emitted event.
 */
static __u32 sentinel_dedup_bump(uid_t uid, __u16 kind)
{
    int i, slot = -1, oldest = 0;
    unsigned long now = jiffies;
    __u32 emit;
    unsigned long flags;

    spin_lock_irqsave(&dedup_lock, flags);

    for (i = 0; i < SENTINEL_DEDUP_SLOTS; i++) {
        if (dedup[i].used && dedup[i].uid == uid && dedup[i].kind == kind) {
            slot = i;
            break;
        }
        if (!dedup[i].used && slot < 0)
            slot = i; /* first free, keep scanning for a real match */
        if (time_before(dedup[i].last, dedup[oldest].last))
            oldest = i;
    }

    if (slot >= 0 && dedup[slot].used && dedup[slot].uid == uid && dedup[slot].kind == kind) {
        if (time_before(now, dedup[slot].last + SENTINEL_DEDUP_WINDOW)) {
            dedup[slot].count++;
            dedup[slot].last = now;
            spin_unlock_irqrestore(&dedup_lock, flags);
            return 0; /* within window: suppress */
        }
        emit = dedup[slot].count + 1;
        dedup[slot].count = 0;
        dedup[slot].last = now;
        spin_unlock_irqrestore(&dedup_lock, flags);
        return emit;
    }

    /* no match: take the free slot, else evict the oldest */
    if (slot < 0)
        slot = oldest;
    dedup[slot].used = true;
    dedup[slot].uid = uid;
    dedup[slot].kind = kind;
    dedup[slot].last = now;
    dedup[slot].count = 0;
    spin_unlock_irqrestore(&dedup_lock, flags);
    return 1;
}

void ksu_sentinel_report(uid_t uid, const char *path, __u16 kind)
{
    struct ksu_sentinel_event ev;
    __u32 count;

    if (!READ_ONCE(ksu_sentinel_enabled))
        return;

    count = sentinel_dedup_bump(uid, kind);
    if (!count)
        return;

    pr_info_ratelimited("sentinel: probe uid=%u pid=%d path=%s x%u\n", uid, current->pid, path ? path : "?", count);

    memset(&ev, 0, sizeof(ev));
    ev.version = KSU_SENTINEL_EVENT_VERSION;
    ev.kind = kind;
    ev.uid = uid;
    ev.pid = current->pid;
    ev.count = count;
    ev.ts_ns = ktime_get_boottime_ns();
    if (path)
        strscpy(ev.path, path, sizeof(ev.path));

    ksu_event_queue_push(&sentinel_queue, kind, 0, &ev, sizeof(ev), GFP_ATOMIC);
}

/* ---- cloak set: uids we hide module mounts from ----
 * The umount path (kernel_umount.c) consults ksu_sentinel_is_cloaked() so a
 * cloaked app uid gets modules unmounted on its next spawn, regardless of the
 * global kernel_umount toggle or its app profile. (su is already hidden from
 * non-allowed apps by sucompat.) In-memory only for now; resets on reboot.
 */
#define SENTINEL_CLOAK_MAX 512

static uid_t cloak_set[SENTINEL_CLOAK_MAX];
static int cloak_count;
static DEFINE_SPINLOCK(cloak_lock);
static bool sentinel_auto_cloak __read_mostly;

bool ksu_sentinel_is_cloaked(uid_t uid)
{
    int i;
    bool res = false;
    unsigned long flags;

    spin_lock_irqsave(&cloak_lock, flags);
    for (i = 0; i < cloak_count; i++) {
        if (cloak_set[i] == uid) {
            res = true;
            break;
        }
    }
    spin_unlock_irqrestore(&cloak_lock, flags);
    return res;
}

int ksu_sentinel_cloak_add(uid_t uid)
{
    int i, ret = 0;
    unsigned long flags;

    spin_lock_irqsave(&cloak_lock, flags);
    for (i = 0; i < cloak_count; i++) {
        if (cloak_set[i] == uid)
            goto out; /* already cloaked */
    }
    if (cloak_count >= SENTINEL_CLOAK_MAX) {
        ret = -ENOSPC;
        goto out;
    }
    cloak_set[cloak_count++] = uid;
    pr_info("sentinel: cloaked uid=%u\n", uid);
out:
    spin_unlock_irqrestore(&cloak_lock, flags);
    return ret;
}

int ksu_sentinel_cloak_remove(uid_t uid)
{
    int i;
    unsigned long flags;

    spin_lock_irqsave(&cloak_lock, flags);
    for (i = 0; i < cloak_count; i++) {
        if (cloak_set[i] == uid) {
            cloak_set[i] = cloak_set[--cloak_count]; /* swap-remove */
            pr_info("sentinel: uncloaked uid=%u\n", uid);
            break;
        }
    }
    spin_unlock_irqrestore(&cloak_lock, flags);
    return 0;
}

void ksu_sentinel_cloak_clear(void)
{
    unsigned long flags;

    spin_lock_irqsave(&cloak_lock, flags);
    cloak_count = 0;
    spin_unlock_irqrestore(&cloak_lock, flags);
}

int ksu_sentinel_cloak_list(uid_t *out, int capacity)
{
    int i, total;
    unsigned long flags;

    spin_lock_irqsave(&cloak_lock, flags);
    total = cloak_count;
    for (i = 0; i < cloak_count && i < capacity; i++)
        out[i] = cloak_set[i];
    spin_unlock_irqrestore(&cloak_lock, flags);
    return total;
}

void ksu_sentinel_set_auto_cloak(bool on)
{
    WRITE_ONCE(sentinel_auto_cloak, on);
    pr_info("sentinel: auto-cloak %d\n", on);
}

bool ksu_sentinel_get_auto_cloak(void)
{
    return READ_ONCE(sentinel_auto_cloak);
}

/* ---- probe detection via kprobe on do_faccessat ----
 * The inline sucompat hook (fs/open.c) only calls ksu_handle_faccessat for
 * allowlisted uids, so it never sees a non-root app probing for su. This kprobe
 * fires for every access()/faccessat()/faccessat2() (all route through
 * do_faccessat) regardless of uid, catching the probers the inline hook skips.
 * Armed only while Sentinel is enabled, so there is zero overhead when off.
 */
#define SENTINEL_SU_PATH "/system/bin/su"

#ifdef CONFIG_KPROBES
static DEFINE_MUTEX(sentinel_kp_lock);
static bool sentinel_kp_registered;

static int sentinel_faccessat_pre(struct kprobe *p, struct pt_regs *regs)
{
    const char __user *filename;
    char path[sizeof(SENTINEL_SU_PATH) + 1] = { 0 };
    uid_t uid;

    if (!READ_ONCE(ksu_sentinel_enabled))
        return 0;

    uid = current_uid().val;
    if (uid == 0)
        return 0; /* root is not a prober */
    if (ksu_is_allow_uid_for_current(uid))
        return 0; /* granted apps go through the sucompat redirect, not a probe */

    /* do_faccessat(int dfd, const char __user *filename, int mode, int flags) */
    filename = (const char __user *)regs_get_kernel_argument(regs, 1);
    if (!filename)
        return 0;

    ksu_strncpy_from_user_nofault(path, filename, sizeof(path));
    if (unlikely(!memcmp(path, SENTINEL_SU_PATH, sizeof(SENTINEL_SU_PATH)))) {
        ksu_sentinel_report(uid, SENTINEL_SU_PATH, KSU_SENTINEL_KIND_SU);
        if (READ_ONCE(sentinel_auto_cloak))
            ksu_sentinel_cloak_add(uid);
    }

    return 0;
}

static struct kprobe sentinel_faccessat_kp = {
    .symbol_name = "do_faccessat",
    .pre_handler = sentinel_faccessat_pre,
};

static void sentinel_kprobe_arm(void)
{
    int ret;

    mutex_lock(&sentinel_kp_lock);
    if (!sentinel_kp_registered) {
        ret = register_kprobe(&sentinel_faccessat_kp);
        if (ret) {
            pr_err("sentinel: register_kprobe(do_faccessat) failed: %d\n", ret);
        } else {
            sentinel_kp_registered = true;
            pr_info("sentinel: kprobe armed on do_faccessat\n");
        }
    }
    mutex_unlock(&sentinel_kp_lock);
}

static void sentinel_kprobe_disarm(void)
{
    mutex_lock(&sentinel_kp_lock);
    if (sentinel_kp_registered) {
        unregister_kprobe(&sentinel_faccessat_kp);
        sentinel_kp_registered = false;
        pr_info("sentinel: kprobe disarmed\n");
    }
    mutex_unlock(&sentinel_kp_lock);
}
#else
static void sentinel_kprobe_arm(void)
{
    pr_warn("sentinel: CONFIG_KPROBES disabled; probe detection unavailable\n");
}
static void sentinel_kprobe_disarm(void)
{
}
#endif

static ssize_t sentinel_read(struct file *file, char __user *buf, size_t count, loff_t *ppos)
{
    return ksu_event_queue_read(&sentinel_queue, buf, count, file->f_flags);
}

static unsigned __bitwise sentinel_poll(struct file *file, poll_table *wait)
{
    return ksu_event_queue_poll(&sentinel_queue, file, wait);
}

static int sentinel_release(struct inode *inode, struct file *file)
{
    mutex_lock(&sentinel_fd_lock);
    sentinel_fd_active = false;
    mutex_unlock(&sentinel_fd_lock);
    pr_info("sentinel: fd released\n");
    return 0;
}

static const struct file_operations sentinel_fops = {
    .owner = THIS_MODULE,
    .read = sentinel_read,
    .poll = sentinel_poll,
    .release = sentinel_release,
    .llseek = noop_llseek,
};

int ksu_install_sentinel_fd(void)
{
    struct file *filp;
    int fd;

    mutex_lock(&sentinel_fd_lock);

    if (sentinel_fd_active) {
        fd = -EBUSY;
        goto out_unlock;
    }

    if (READ_ONCE(sentinel_queue.closed)) {
        fd = -EPIPE;
        goto out_unlock;
    }

    fd = get_unused_fd_flags(O_CLOEXEC);
    if (fd < 0)
        goto out_unlock;

    filp = anon_inode_getfile("[ksu_sentinel]", &sentinel_fops, NULL, O_RDONLY | O_CLOEXEC);
    if (IS_ERR(filp)) {
        put_unused_fd(fd);
        fd = PTR_ERR(filp);
        goto out_unlock;
    }

    sentinel_fd_active = true;
    fd_install(fd, filp);
    pr_info("sentinel: fd installed %d for pid %d\n", fd, current->pid);

out_unlock:
    mutex_unlock(&sentinel_fd_lock);
    return fd;
}

/* ---- enable/disable via the feature framework (get/set_feature ioctls) ---- */
static int sentinel_feature_get(u64 *value)
{
    *value = ksu_sentinel_enabled ? 1 : 0;
    return 0;
}

static int sentinel_feature_set(u64 value)
{
    bool enable = value != 0;

    ksu_sentinel_enabled = enable;
    if (enable)
        sentinel_kprobe_arm();
    else
        sentinel_kprobe_disarm();
    pr_info("sentinel: set to %d\n", enable);
    return 0;
}

static const struct ksu_feature_handler sentinel_handler = {
    .feature_id = KSU_FEATURE_SENTINEL,
    .name = "sentinel",
    .get_handler = sentinel_feature_get,
    .set_handler = sentinel_feature_set,
};

void __init ksu_sentinel_init(void)
{
    int ret;

    ksu_sentinel_enabled = false;
    sentinel_auto_cloak = false;
    cloak_count = 0;
    memset(dedup, 0, sizeof(dedup));

    ksu_event_queue_init(&sentinel_queue, SENTINEL_MAX_QUEUED, sizeof(struct ksu_sentinel_event));

    ret = ksu_register_feature_handler(&sentinel_handler);
    if (ret)
        pr_err("sentinel: failed to register feature handler: %d\n", ret);

    mutex_lock(&sentinel_fd_lock);
    sentinel_fd_active = false;
    mutex_unlock(&sentinel_fd_lock);

    pr_info("sentinel: initialized\n");
}

void __exit ksu_sentinel_exit(void)
{
    sentinel_kprobe_disarm();
    ksu_sentinel_cloak_clear();
    WRITE_ONCE(sentinel_auto_cloak, false);
    ksu_unregister_feature_handler(KSU_FEATURE_SENTINEL);

    mutex_lock(&sentinel_fd_lock);
    sentinel_fd_active = false;
    mutex_unlock(&sentinel_fd_lock);

    ksu_event_queue_close(&sentinel_queue);
    ksu_event_queue_destroy(&sentinel_queue);
}
