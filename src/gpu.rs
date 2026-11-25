use std::{
    collections::HashMap,
    sync::{
        Once,
        atomic::{AtomicU8, Ordering},
    },
    time::Duration,
};

use windows::{
    Win32::System::Performance::{
        PDH_CSTATUS_NEW_DATA, PDH_CSTATUS_VALID_DATA, PDH_FMT_COUNTERVALUE_ITEM_W, PDH_FMT_DOUBLE,
        PDH_HCOUNTER, PDH_HQUERY, PDH_MORE_DATA, PdhAddCounterW, PdhAddEnglishCounterW,
        PdhCloseQuery, PdhCollectQueryData, PdhGetFormattedCounterArrayW, PdhOpenQueryW,
    },
    core::{PCWSTR, w},
};

static START: Once = Once::new();
static GPU: AtomicU8 = AtomicU8::new(255);

pub(crate) fn percent() -> Option<u8> {
    START.call_once(|| {
        let _ = std::thread::Builder::new()
            .name("deck-gpu".into())
            .spawn(sample);
    });
    let value = GPU.load(Ordering::Relaxed);
    (value <= 100).then_some(value)
}

fn sample() {
    let mut query = PDH_HQUERY::default();
    let mut counter = PDH_HCOUNTER::default();
    // SAFETY: PDH initializes the handles and opens a query local to this thread.
    unsafe {
        if PdhOpenQueryW(PCWSTR::null(), 0, &mut query) != 0 {
            return;
        }
        let path = w!(r"\GPU Engine(*)\Utilization Percentage");
        if PdhAddCounterW(query, path, 0, &mut counter) != 0
            && PdhAddEnglishCounterW(query, path, 0, &mut counter) != 0
        {
            PdhCloseQuery(query);
            return;
        }
    }
    // SAFETY: The query now owns its GPU counter and needs an initial sample.
    let _ = unsafe { PdhCollectQueryData(query) };
    loop {
        std::thread::sleep(Duration::from_secs(2));
        // SAFETY: query remains open and the counter is associated with it.
        let collected = unsafe { PdhCollectQueryData(query) == 0 };
        let value = collected.then(|| busiest(counter)).flatten().unwrap_or(255);
        GPU.store(value, Ordering::Relaxed);
    }
}

fn busiest(counter: PDH_HCOUNTER) -> Option<u8> {
    let mut bytes = 0;
    let mut count = 0;
    // SAFETY: The first query requests only buffer size, the second provides aligned storage.
    unsafe {
        let state =
            PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, &mut bytes, &mut count, None);
        if state != PDH_MORE_DATA || bytes > 2 * 1024 * 1024 {
            return None;
        }
        let mut items = vec![
            PDH_FMT_COUNTERVALUE_ITEM_W::default();
            (bytes as usize).div_ceil(size_of::<PDH_FMT_COUNTERVALUE_ITEM_W>())
        ];
        if PdhGetFormattedCounterArrayW(
            counter,
            PDH_FMT_DOUBLE,
            &mut bytes,
            &mut count,
            Some(items.as_mut_ptr()),
        ) != 0
        {
            return None;
        }
        let mut engines: HashMap<String, f64> = HashMap::new();
        for item in items.iter().take(count as usize) {
            if !matches!(
                item.FmtValue.CStatus,
                PDH_CSTATUS_VALID_DATA | PDH_CSTATUS_NEW_DATA
            ) || item.szName.is_null()
            {
                continue;
            }
            let name = item.szName.to_string().ok()?;
            let Some((_, engine)) = name.split_once("_luid_") else {
                continue;
            };
            let value = item.FmtValue.Anonymous.doubleValue;
            if value.is_finite() && value >= 0.0 {
                *engines.entry(engine.to_owned()).or_default() += value;
            }
        }
        engines
            .into_values()
            .reduce(f64::max)
            .map(|value| value.clamp(0.0, 100.0).round() as u8)
    }
}
