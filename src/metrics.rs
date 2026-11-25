use std::{
    ffi::OsString,
    os::windows::ffi::OsStrExt,
    sync::{
        Mutex, Once,
        atomic::{AtomicU8, Ordering},
    },
    time::Duration,
};

use serde::Serialize;
use windows::{
    Win32::{
        Foundation::FILETIME,
        Storage::FileSystem::GetDiskFreeSpaceExW,
        System::{
            SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX},
            Threading::GetSystemTimes,
        },
    },
    core::PCWSTR,
};

#[derive(Serialize)]
pub(crate) struct Metrics {
    cpu_percent: Option<u8>,
    gpu_percent: Option<u8>,
    memory: Option<Usage>,
    system_disk: Option<Usage>,
}

#[derive(Serialize)]
struct Usage {
    used: u64,
    total: u64,
}

#[derive(Clone, Copy)]
struct Cpu {
    idle: u64,
    total: u64,
}

static PREVIOUS_CPU: Mutex<Option<Cpu>> = Mutex::new(None);
static CPU: AtomicU8 = AtomicU8::new(255);
static START: Once = Once::new();

pub(crate) fn start() {
    START.call_once(|| {
        let _ = crate::gpu::percent();
        let _ = std::thread::Builder::new()
            .name("deck-cpu".into())
            .spawn(|| {
                loop {
                    if let Some(percent) = sample_cpu() {
                        CPU.store(percent, Ordering::Relaxed);
                    }
                    std::thread::sleep(Duration::from_secs(1));
                }
            });
    });
}

pub(crate) fn read() -> Metrics {
    Metrics {
        cpu_percent: {
            let value = CPU.load(Ordering::Relaxed);
            (value <= 100).then_some(value)
        },
        gpu_percent: crate::gpu::percent(),
        memory: memory(),
        system_disk: system_disk(),
    }
}

fn sample_cpu() -> Option<u8> {
    let mut idle = FILETIME::default();
    let mut kernel = FILETIME::default();
    let mut user = FILETIME::default();
    // SAFETY: Windows receives valid writable FILETIME buffers for this call.
    unsafe { GetSystemTimes(Some(&mut idle), Some(&mut kernel), Some(&mut user)) }.ok()?;
    fn ticks(time: FILETIME) -> u64 {
        u64::from(time.dwHighDateTime) << 32 | u64::from(time.dwLowDateTime)
    }
    let next = Cpu {
        idle: ticks(idle),
        total: ticks(kernel).checked_add(ticks(user))?,
    };
    let old = PREVIOUS_CPU.lock().ok()?.replace(next)?;
    let span = next.total.checked_sub(old.total)?;
    let idle = next.idle.checked_sub(old.idle)?;
    let busy = span.checked_sub(idle)?;
    u8::try_from(busy.checked_mul(100)?.checked_div(span)?).ok()
}

fn memory() -> Option<Usage> {
    let mut status = MEMORYSTATUSEX {
        dwLength: size_of::<MEMORYSTATUSEX>() as u32,
        ..Default::default()
    };
    // SAFETY: The structure is initialized with the required size and remains writable.
    unsafe { GlobalMemoryStatusEx(&mut status) }.ok()?;
    Some(Usage {
        used: status.ullTotalPhys.saturating_sub(status.ullAvailPhys),
        total: status.ullTotalPhys,
    })
}

pub(crate) fn disk_percent() -> Option<u8> {
    let disk = system_disk()?;
    u8::try_from(disk.used.checked_mul(100)?.checked_div(disk.total)?).ok()
}

fn system_disk() -> Option<Usage> {
    let mut drive = std::env::var_os("SystemDrive")?;
    drive.push(OsString::from("\\"));
    let path: Vec<_> = drive.encode_wide().chain([0]).collect();
    let mut total = 0;
    let mut free = 0;
    // SAFETY: The string is NUL-terminated and the output buffers are valid.
    unsafe {
        GetDiskFreeSpaceExW(
            PCWSTR(path.as_ptr()),
            None,
            Some(&mut total),
            Some(&mut free),
        )
    }
    .ok()?;
    Some(Usage {
        used: total.saturating_sub(free),
        total,
    })
}
