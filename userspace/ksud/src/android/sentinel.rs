use std::io;
use std::mem::size_of;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

use anyhow::{Context, Result, bail};

use crate::android::{feature, ksucalls};

const KSU_EVENT_QUEUE_TYPE_DROPPED: u16 = u16::MAX;
const READ_BUF_SIZE: usize = 8192;
const SENTINEL_PATH_LEN: usize = 128;

/* Mirror of the kernel's generic event_queue record header (see
 * kernel/infra/event_queue.h). Same wire format the sulog stream uses. */
#[repr(C, packed)]
#[derive(Clone, Copy, Debug)]
struct EventRecordHeader {
    record_type: u16,
    flags: u16,
    payload_len: u32,
    seq: u64,
    ts_ns: u64,
}

/* Mirror of struct ksu_sentinel_event (uapi/sentinel.h). Defined here (rather
 * than via bindgen) so the path field is plain [u8; N] instead of c_char. */
#[repr(C)]
#[derive(Clone, Copy, Debug)]
struct SentinelEvent {
    version: u16,
    kind: u16,
    uid: u32,
    pid: u32,
    count: u32,
    ts_ns: u64,
    path: [u8; SENTINEL_PATH_LEN],
}

pub fn enable() -> Result<()> {
    feature::set_feature("sentinel", 1)?;
    save_config();
    Ok(())
}

pub fn disable() -> Result<()> {
    feature::set_feature("sentinel", 0)?;
    save_config();
    Ok(())
}

/* cloak ops mirror enum ksu_sentinel_cloak_op: 0=add 1=remove 5=set_auto */
pub fn cloak(uid: u32) -> Result<()> {
    ksucalls::sentinel_cloak_op(0, uid, 0).context("cloak failed")?;
    println!("sentinel: cloaked uid {uid}");
    save_config();
    Ok(())
}

pub fn uncloak(uid: u32) -> Result<()> {
    ksucalls::sentinel_cloak_op(1, uid, 0).context("uncloak failed")?;
    println!("sentinel: uncloaked uid {uid}");
    save_config();
    Ok(())
}

pub fn cloaked() -> Result<()> {
    let list = ksucalls::sentinel_cloak_list().context("failed to list cloaked uids")?;
    if list.is_empty() {
        println!("sentinel: no cloaked uids");
    } else {
        println!("sentinel: cloaked uids:");
        for uid in &list {
            println!("  {uid}");
        }
    }
    // persist the live set (captures auto-cloaks too) whenever it's read
    save_config();
    Ok(())
}

pub fn set_auto(on: bool) -> Result<()> {
    ksucalls::sentinel_cloak_op(5, 0, u32::from(on)).context("failed to set auto-cloak")?;
    println!("sentinel: auto-cloak {}", if on { "on" } else { "off" });
    save_config();
    Ok(())
}

const SENTINEL_CONF: &str = "/data/adb/ksu/sentinel.json";

#[derive(serde::Serialize, serde::Deserialize, Default)]
struct SentinelConf {
    enabled: bool,
    auto: bool,
    cloaked: Vec<u32>,
}

/// Snapshot current kernel state to disk so it survives reboot.
fn save_config() {
    let enabled = ksucalls::get_feature(5)
        .map(|(v, _)| v != 0)
        .unwrap_or(false);
    let auto = ksucalls::sentinel_cloak_op(6, 0, 0)
        .map(|v| v != 0)
        .unwrap_or(false);
    let cloaked = ksucalls::sentinel_cloak_list().unwrap_or_default();
    let conf = SentinelConf {
        enabled,
        auto,
        cloaked,
    };
    if let Ok(json) = serde_json::to_string(&conf) {
        let _ = std::fs::write(SENTINEL_CONF, json);
    }
}

/// Re-apply saved Sentinel state at boot (the kernel state resets on reboot).
pub fn restore_config() {
    let data = match std::fs::read_to_string(SENTINEL_CONF) {
        Ok(d) => d,
        Err(_) => return,
    };
    let conf: SentinelConf = match serde_json::from_str(&data) {
        Ok(c) => c,
        Err(_) => return,
    };
    let _ = ksucalls::sentinel_cloak_op(5, 0, u32::from(conf.auto));
    for uid in conf.cloaked {
        let _ = ksucalls::sentinel_cloak_op(0, uid, 0);
    }
    if conf.enabled {
        let _ = feature::set_feature("sentinel", 1);
    }
}

/// Stream root-probe events from the kernel to stdout (blocks until the fd
/// closes or the process is interrupted). Handy for `adb shell` testing.
pub fn watch() -> Result<()> {
    let raw = ksucalls::get_sentinel_fd()
        .context("failed to open sentinel fd (enable it first: ksud sentinel on)")?;
    // SAFETY: get_sentinel_fd returns a freshly installed, owned fd.
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };
    println!("sentinel: watching for root probes (Ctrl-C to stop)");

    let mut buf = [0u8; READ_BUF_SIZE];
    loop {
        let n = unsafe {
            libc::read(
                fd.as_raw_fd(),
                buf.as_mut_ptr().cast::<libc::c_void>(),
                buf.len(),
            )
        };
        if n < 0 {
            let err = io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            return Err(err).context("read sentinel fd");
        }
        if n == 0 {
            bail!("sentinel fd closed");
        }

        let n = n as usize;
        let mut off = 0usize;
        while off + size_of::<EventRecordHeader>() <= n {
            // SAFETY: bounds checked above; packed header read.
            let hdr: EventRecordHeader =
                unsafe { std::ptr::read_unaligned(buf[off..].as_ptr().cast()) };
            let payload_len = hdr.payload_len as usize;
            let frame = size_of::<EventRecordHeader>() + payload_len;
            if off + frame > n {
                break;
            }
            let record_type = hdr.record_type;
            let payload = &buf[off + size_of::<EventRecordHeader>()..off + frame];

            if record_type == KSU_EVENT_QUEUE_TYPE_DROPPED {
                println!("[sentinel] some probe events were dropped (queue full)");
            } else if payload_len >= size_of::<SentinelEvent>() {
                // SAFETY: payload large enough; unaligned read into an owned value.
                let ev: SentinelEvent =
                    unsafe { std::ptr::read_unaligned(payload.as_ptr().cast()) };
                let uid = ev.uid;
                let pid = ev.pid;
                let count = ev.count;
                let kind = ev.kind;
                let path_arr = ev.path;
                let end = path_arr
                    .iter()
                    .position(|&b| b == 0)
                    .unwrap_or(path_arr.len());
                let path = String::from_utf8_lossy(&path_arr[..end]).into_owned();
                let kind_str = if kind == 1 { "su" } else { "other" };
                println!("[sentinel] uid={uid} pid={pid} probed {path} ({kind_str}) x{count}");
            }
            off += frame;
        }
    }
}

#[derive(serde::Serialize)]
struct Probe {
    uid: u32,
    pid: u32,
    count: u32,
    kind: u32,
    path: String,
}

/// Non-blocking drain of queued probe events; prints them as a JSON array and
/// returns. The manager polls this for the Shield feed.
pub fn drain() -> Result<()> {
    let raw = ksucalls::get_sentinel_fd()
        .context("failed to open sentinel fd (enable it first: ksud sentinel on)")?;
    // SAFETY: get_sentinel_fd returns a freshly installed, owned fd.
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };

    // Make the read non-blocking so we return after draining what's queued.
    unsafe {
        let flags = libc::fcntl(fd.as_raw_fd(), libc::F_GETFL);
        if flags >= 0 {
            libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
    }

    let mut probes: Vec<Probe> = Vec::new();
    let mut buf = [0u8; READ_BUF_SIZE];
    loop {
        let n = unsafe {
            libc::read(
                fd.as_raw_fd(),
                buf.as_mut_ptr().cast::<libc::c_void>(),
                buf.len(),
            )
        };
        if n < 0 {
            let err = io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            if matches!(err.kind(), io::ErrorKind::WouldBlock)
                || err.raw_os_error() == Some(libc::EAGAIN)
            {
                break;
            }
            return Err(err).context("read sentinel fd");
        }
        if n == 0 {
            break;
        }

        let n = n as usize;
        let mut off = 0usize;
        while off + size_of::<EventRecordHeader>() <= n {
            let hdr: EventRecordHeader =
                unsafe { std::ptr::read_unaligned(buf[off..].as_ptr().cast()) };
            let payload_len = hdr.payload_len as usize;
            let frame = size_of::<EventRecordHeader>() + payload_len;
            if off + frame > n {
                break;
            }
            let record_type = hdr.record_type;
            let payload = &buf[off + size_of::<EventRecordHeader>()..off + frame];

            if record_type != KSU_EVENT_QUEUE_TYPE_DROPPED
                && payload_len >= size_of::<SentinelEvent>()
            {
                let ev: SentinelEvent =
                    unsafe { std::ptr::read_unaligned(payload.as_ptr().cast()) };
                let path_arr = ev.path;
                let end = path_arr
                    .iter()
                    .position(|&b| b == 0)
                    .unwrap_or(path_arr.len());
                probes.push(Probe {
                    uid: ev.uid,
                    pid: ev.pid,
                    count: ev.count,
                    kind: u32::from(ev.kind),
                    path: String::from_utf8_lossy(&path_arr[..end]).into_owned(),
                });
            }
            off += frame;
        }
    }

    println!(
        "{}",
        serde_json::to_string(&probes).unwrap_or_else(|_| "[]".to_string())
    );
    Ok(())
}

/// Print "{"enabled":bool,"auto":bool}" for the manager to read current state.
pub fn status() -> Result<()> {
    // feature id 5 = KSU_FEATURE_SENTINEL
    let enabled = ksucalls::get_feature(5)
        .map(|(v, _)| v != 0)
        .unwrap_or(false);
    // cloak op 6 = GET_AUTO
    let auto = ksucalls::sentinel_cloak_op(6, 0, 0)
        .map(|v| v != 0)
        .unwrap_or(false);
    println!("{{\"enabled\":{enabled},\"auto\":{auto}}}");
    Ok(())
}

#[derive(serde::Serialize)]
struct HistEntry {
    uid: u32,
    count: u32,
    kinds: u32,
    last_ns: u64,
}

/// Print the persistent per-uid probe history (survives until reboot) as JSON.
pub fn history() -> Result<()> {
    let entries = ksucalls::sentinel_history().context("failed to read sentinel history")?;
    let list: Vec<HistEntry> = entries
        .iter()
        .map(|e| HistEntry {
            uid: e.uid,
            count: e.count,
            kinds: e.kinds,
            last_ns: e.last_ns,
        })
        .collect();
    println!(
        "{}",
        serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string())
    );
    Ok(())
}

/// Clear the persistent probe history.
pub fn clear() -> Result<()> {
    ksucalls::sentinel_history_clear().context("failed to clear sentinel history")?;
    println!("sentinel: history cleared");
    Ok(())
}
