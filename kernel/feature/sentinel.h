#ifndef __KSU_H_SENTINEL
#define __KSU_H_SENTINEL

#include <linux/errno.h>
#include <linux/types.h>

struct ksu_sentinel_hist_entry; /* uapi/supercall.h */

#ifdef CONFIG_KSU_SENTINEL
bool ksu_sentinel_is_enabled(void);

/* Persistent per-uid probe history (resets on reboot). */
void ksu_sentinel_history_record(uid_t uid, __u16 kind);
int ksu_sentinel_history_dump(struct ksu_sentinel_hist_entry *out, int capacity);
void ksu_sentinel_history_clear(void);
void ksu_sentinel_report(uid_t uid, const char *path, __u16 kind);
int ksu_install_sentinel_fd(void);
void ksu_sentinel_init(void);
void ksu_sentinel_exit(void);

/* Cloak set: uids we hide root (module mounts) from. Consulted by the umount
 * path so cloaked app uids get modules unmounted regardless of the global
 * kernel_umount toggle or their app profile. */
bool ksu_sentinel_is_cloaked(uid_t uid);
int ksu_sentinel_cloak_add(uid_t uid);
int ksu_sentinel_cloak_remove(uid_t uid);
void ksu_sentinel_cloak_clear(void);
int ksu_sentinel_cloak_list(uid_t *out, int capacity); /* returns total count */
void ksu_sentinel_set_auto_cloak(bool on);
bool ksu_sentinel_get_auto_cloak(void);
#else
static inline bool ksu_sentinel_is_enabled(void)
{
    return false;
}
static inline void ksu_sentinel_history_record(uid_t uid, __u16 kind)
{
}
static inline int ksu_sentinel_history_dump(struct ksu_sentinel_hist_entry *out, int capacity)
{
    return 0;
}
static inline void ksu_sentinel_history_clear(void)
{
}
static inline void ksu_sentinel_report(uid_t uid, const char *path, __u16 kind)
{
}
static inline int ksu_install_sentinel_fd(void)
{
    return -ENOSYS;
}
static inline void ksu_sentinel_init(void)
{
}
static inline void ksu_sentinel_exit(void)
{
}
static inline bool ksu_sentinel_is_cloaked(uid_t uid)
{
    return false;
}
static inline int ksu_sentinel_cloak_add(uid_t uid)
{
    return -ENOSYS;
}
static inline int ksu_sentinel_cloak_remove(uid_t uid)
{
    return -ENOSYS;
}
static inline void ksu_sentinel_cloak_clear(void)
{
}
static inline int ksu_sentinel_cloak_list(uid_t *out, int capacity)
{
    return 0;
}
static inline void ksu_sentinel_set_auto_cloak(bool on)
{
}
static inline bool ksu_sentinel_get_auto_cloak(void)
{
    return false;
}
#endif

#endif
