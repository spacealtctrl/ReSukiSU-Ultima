#ifndef __KSU_H_SENTINEL
#define __KSU_H_SENTINEL

#include <linux/errno.h>
#include <linux/types.h>

#ifdef CONFIG_KSU_SENTINEL
bool ksu_sentinel_is_enabled(void);
void ksu_sentinel_report(uid_t uid, const char *path, __u16 kind);
int ksu_install_sentinel_fd(void);
void ksu_sentinel_init(void);
void ksu_sentinel_exit(void);
#else
static inline bool ksu_sentinel_is_enabled(void)
{
    return false;
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
#endif

#endif
