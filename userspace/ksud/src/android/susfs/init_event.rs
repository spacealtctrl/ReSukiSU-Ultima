use std::{
    path::Path,
    process::Command,
    thread,
    time::{Duration, Instant},
};

use log::{info, warn};

use crate::android::susfs::api;
use crate::android::susfs::config;
use crate::android::susfs::config::data::Data;

const USER_0_CE_AVAILABLE_PROP: &str = "sys.user.0.ce_available";
const CE_AVAILABLE_WAIT_TIMEOUT_SECS: u64 = 10 * 60;
const CE_AVAILABLE_POLL_INTERVAL_SECS: u64 = 1;
const USER_0_CE_PATH_PREFIXES: &[&str] = &[
    "/sdcard",
    "/storage/emulated/0",
    "/storage/self/primary",
    "/mnt/user/0/primary",
    "/mnt/runtime/default/emulated/0",
    "/mnt/runtime/read/emulated/0",
    "/mnt/runtime/write/emulated/0",
    "/mnt/runtime/full/emulated/0",
    "/mnt/pass_through/0/emulated/0",
    "/mnt/installer/0/emulated/0",
    "/mnt/androidwritable/0/emulated/0",
    "/mnt/media_rw/emulated/0",
    "/data/media/0",
    "/data/user/0",
    "/data/data",
    "/data/misc_ce/0",
    "/data/system_ce/0",
    "/data/vendor_ce/0",
    "/data_mirror/data_ce/null/0",
    "/data_mirror/data_ce/0",
];

enum CeAvailability {
    Available,
    Locked,
    Unknown,
}

pub fn on_boot_completed() {
    let has_ce_sensitive_entries = has_ce_sensitive_config_entries();

    if is_user_0_ce_ready() {
        apply_after_ce_available("user-0-ce-available-at-boot-completed");
    } else if has_ce_sensitive_entries {
        wait_for_user_0_ce_available();
    } else {
        info!("{USER_0_CE_AVAILABLE_PROP} is unavailable or not required");
        apply_after_ce_available("boot-completed-without-ce-property");
    }
}

pub fn on_services() {
    // let config = config::read_config();

    // apply_sus_paths(&config);
    // apply_sus_maps(&config);
}

fn apply_sus_paths(config: &Data) {
    for sus_path in &config.sus_path.sus_path {
        if sus_path.trim().is_empty() {
            continue;
        }
        apply_sus_path_entry(&api::SusPathType::Normal, "sus_path", sus_path);
    }
    for sus_path_loop in &config.sus_path.sus_path_loop {
        if sus_path_loop.trim().is_empty() {
            continue;
        }
        apply_sus_path_entry(&api::SusPathType::Loop, "sus_path_loop", sus_path_loop);
    }
}

fn apply_sus_path_entry(path_type: &api::SusPathType, label: &str, path: &str) {
    if let Err(e) = api::add_sus_path(path_type, &path) {
        warn!("failed to add {label} '{path}': {e}");
    }
}

fn apply_sus_maps(config: &Data) {
    for sus_map in &config.sus_map {
        if sus_map.trim().is_empty() {
            continue;
        }
        if let Err(e) = api::add_sus_map(sus_map.as_str()) {
            warn!("failed to add sus_map '{sus_map}': {e}");
        }
    }
}

pub fn on_post_fs_data() {
    let config = config::read_config();

    if let Err(e) = api::set_uname(&config.common.spoof_version, &config.common.spoof_release) {
        warn!("failed to set uname: {e}");
    }

    if let Err(e) = api::enable_avc_log_spoofing(config.common.avc_spoofing.into()) {
        warn!("failed to enable avc log spoofing: {e}");
    }

    if let Err(e) = api::enable_log(config.common.enable_susfs_log.into()) {
        warn!("failed to enable susfs log: {e}");
    }

    if let Err(e) =
        api::hide_sus_mnts_for_non_su_procs(config.common.hide_sus_mnts_for_non_su_procs.into())
    {
        warn!("failed to hide sus mnts for non su procs: {e}");
    }

    // apply_sus_paths(&config);

    apply_sus_kstat_additions(&config);
}

pub fn on_post_mount() {
    let config = config::read_config();

    // apply_sus_paths(&config);
    // apply_sus_maps(&config);

    apply_kstat_updates(&config);
}

fn apply_after_ce_available(reason: &str) {
    let config = config::read_config();

    info!("applying susfs CE-sensitive entries for {reason}");
    apply_sus_paths(&config);
    apply_sus_maps(&config);
    apply_sus_kstat_additions(&config);
    apply_kstat_updates(&config);
}

fn apply_sus_kstat_additions(config: &Data) {
    for sus_kstat in &config.kstat.sus_kstat {
        if sus_kstat.trim().is_empty() {
            continue;
        }
        if let Err(e) = api::add_sus_kstat(sus_kstat.as_str()) {
            warn!("failed to add sus_kstat '{sus_kstat}': {e}");
        }
    }
    for statically in &config.kstat.statically {
        if statically.path.trim().is_empty() {
            continue;
        }
        if let Err(e) = api::add_sus_kstat_statically(
            &statically.path,
            &statically.ino,
            &statically.dev,
            &statically.nlink,
            &statically.size,
            &statically.atime,
            &statically.atime_nsec,
            &statically.mtime,
            &statically.mtime_nsec,
            &statically.ctime,
            &statically.ctime_nsec,
            &statically.blocks,
            &statically.blksize,
        ) {
            warn!(
                "failed to add sus_kstat_statically '{}': {}",
                statically.path, e
            );
        }
    }
}

fn apply_kstat_updates(config: &Data) {
    for update_kstat in &config.kstat.update_kstat {
        if update_kstat.trim().is_empty() {
            continue;
        }
        if let Err(e) = api::update_sus_kstat(update_kstat.as_str()) {
            warn!("failed to update sus_kstat '{update_kstat}': {e}");
        }
    }
    for full_clone in &config.kstat.full_clone {
        if full_clone.trim().is_empty() {
            continue;
        }
        if let Err(e) = api::update_sus_kstat_full_clone(full_clone.as_str()) {
            warn!("failed to update sus_kstat_full_clone '{full_clone}': {e}");
        }
    }
}

fn user_0_ce_availability() -> CeAvailability {
    match crate::android::utils::getprop(USER_0_CE_AVAILABLE_PROP)
        .as_deref()
        .map(str::trim)
    {
        Some(value) if is_true_property_value(value) => CeAvailability::Available,
        Some(value) if is_false_property_value(value) => CeAvailability::Locked,
        _ => CeAvailability::Unknown,
    }
}

fn is_true_property_value(value: &str) -> bool {
    value == "1" || value.eq_ignore_ascii_case("true")
}

fn is_false_property_value(value: &str) -> bool {
    value == "0" || value.eq_ignore_ascii_case("false")
}

fn has_ce_sensitive_config_entries() -> bool {
    let config = config::read_config();
    any_config_path(&config, is_user_ce_path)
}

fn is_configured_ce_path_available() -> bool {
    let config = config::read_config();
    any_config_path(&config, |path| is_user_ce_path(path) && Path::new(path).exists())
}

fn is_user_0_ce_ready() -> bool {
    matches!(user_0_ce_availability(), CeAvailability::Available)
        || is_configured_ce_path_available()
        || is_user_0_unlocked_by_cmd()
}

fn is_user_0_unlocked_by_cmd() -> bool {
    let Ok(output) = Command::new("cmd")
        .args(["user", "is-user-unlocked", "0"])
        .output()
    else {
        return false;
    };

    if !output.status.success() {
        return false;
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    stdout
        .split_whitespace()
        .any(|token| token.eq_ignore_ascii_case("true"))
}

fn any_config_path<F>(config: &Data, mut predicate: F) -> bool
where
    F: FnMut(&str) -> bool,
{
    config
        .sus_path
        .sus_path
        .iter()
        .chain(config.sus_path.sus_path_loop.iter())
        .chain(config.sus_map.iter())
        .chain(config.kstat.sus_kstat.iter())
        .chain(config.kstat.update_kstat.iter())
        .chain(config.kstat.full_clone.iter())
        .any(|path| predicate(path.trim()))
        || config
            .kstat
            .statically
            .iter()
            .any(|entry| predicate(entry.path.trim()))
}

fn is_user_ce_path(path: &str) -> bool {
    let path = path.trim_end_matches('/');
    USER_0_CE_PATH_PREFIXES
        .iter()
        .any(|prefix| is_path_or_child(path, prefix))
}

fn is_path_or_child(path: &str, prefix: &str) -> bool {
    if path == prefix {
        return true;
    }

    matches!(path.strip_prefix(prefix), Some(rest) if rest.starts_with('/'))
}

fn wait_for_user_0_ce_available() {
    match crate::android::utils::create_daemon(false) {
        Ok(true) => {}
        Ok(false) => return,
        Err(e) => {
            warn!("failed to daemonize susfs CE availability watcher: {e}");
            return;
        }
    }

    let exit_code = if wait_for_user_0_ce_available_inner() {
        apply_after_ce_available("user-0-ce-available");
        0
    } else {
        0
    };
    unsafe {
        libc::_exit(exit_code);
    }
}

fn wait_for_user_0_ce_available_inner() -> bool {
    let started_at = Instant::now();

    info!("waiting for {USER_0_CE_AVAILABLE_PROP}, user unlock state, or configured CE paths");
    loop {
        if is_user_0_ce_ready() {
            return true;
        }

        let elapsed = started_at.elapsed();
        if elapsed >= Duration::from_secs(CE_AVAILABLE_WAIT_TIMEOUT_SECS) {
            warn!("timed out waiting for user 0 CE availability");
            return false;
        }

        let remaining = Duration::from_secs(CE_AVAILABLE_WAIT_TIMEOUT_SECS).saturating_sub(elapsed);
        thread::sleep(remaining.min(Duration::from_secs(CE_AVAILABLE_POLL_INTERVAL_SECS)));
    }
}
