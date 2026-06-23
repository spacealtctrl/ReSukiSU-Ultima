// Root-request notifier daemon. Driven entirely by Sentinel (NEVER SU Log): it
// polls the kernel's Sentinel su-probe history and broadcasts a root request to
// the manager for each non-allowlisted, non-cloaked app that probes for su. It
// is a plain native daemon (like sulogd) - NOT an Android foreground service -
// so there is no persistent "monitoring" notification.

use std::collections::HashSet;
use std::fs::OpenOptions;
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Stdio};
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result};

use crate::android::{ksucalls, utils};

const ENABLED_FLAG: &str = "/data/adb/ksu/su_notify_enabled";
const MANAGER_PKG: &str = "/data/adb/ksu/manager_package";
const LOCK_PATH: &str = "/data/adb/ksu/su_notifyd.lock";
const KIND_SU: u32 = 1; // KSU_SENTINEL_KIND_SU -> bit 0
const POLL: Duration = Duration::from_secs(5);

fn enabled() -> bool {
    Path::new(ENABLED_FLAG).exists()
}

fn broadcast(pkg: &str, uid: u32) {
    let _ = Command::new("am")
        .arg("broadcast")
        .arg("-n")
        .arg(format!("{pkg}/.RootRequestReceiver"))
        .arg("--ei")
        .arg("uid")
        .arg(uid.to_string())
        .arg("-f")
        .arg("32") // FLAG_INCLUDE_STOPPED_PACKAGES
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

/// Foreground daemon loop. Exits when the feature is disabled.
pub fn run_su_notifyd() -> Result<()> {
    // Single instance.
    let lock = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .mode(0o600)
        .open(LOCK_PATH)?;
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Ok(()); // another instance owns it
    }

    let mut notified: HashSet<u32> = HashSet::new();
    while enabled() {
        let pkg = std::fs::read_to_string(MANAGER_PKG)
            .unwrap_or_default()
            .trim()
            .to_string();
        if !pkg.is_empty() {
            let cloaked: HashSet<u32> = ksucalls::sentinel_cloak_list()
                .unwrap_or_default()
                .into_iter()
                .collect();
            if let Ok(entries) = ksucalls::sentinel_history() {
                for e in entries {
                    if (e.kinds & KIND_SU) == 0 || e.uid < 10000 {
                        continue;
                    }
                    if cloaked.contains(&e.uid) {
                        notified.remove(&e.uid); // uncloaking lets it notify again
                        continue;
                    }
                    if notified.insert(e.uid) {
                        broadcast(&pkg, e.uid);
                    }
                }
            }
        }
        thread::sleep(POLL);
    }
    Ok(())
}

/// Daemonize + start the daemon if the feature is enabled. Idempotent (flock).
pub fn ensure_su_notifyd_running() -> Result<()> {
    if !enabled() {
        return Ok(());
    }
    if utils::create_daemon(true)? {
        let exe = std::env::current_exe().context("resolve ksud path")?;
        let mut cmd = Command::new(exe);
        cmd.arg("su-notifyd")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .current_dir("/");
        Err(cmd.exec()).context("failed to exec su-notifyd")
    } else {
        Ok(())
    }
}
