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
    if (unlikely(!memcmp(path, SENTINEL_SU_PATH, sizeof(SENTINEL_SU_PATH))))
        ksu_sentinel_report(uid, SENTINEL_SU_PATH, KSU_SENTINEL_KIND_SU);

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
    ksu_unregister_feature_handler(KSU_FEATURE_SENTINEL);

    mutex_lock(&sentinel_fd_lock);
    sentinel_fd_active = false;
    mutex_unlock(&sentinel_fd_lock);

    ksu_event_queue_close(&sentinel_queue);
    ksu_event_queue_destroy(&sentinel_queue);
}
