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
    feature::set_feature("sentinel", 1)
}

pub fn disable() -> Result<()> {
    feature::set_feature("sentinel", 0)
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
