#[cfg(windows)]
mod art;
#[cfg(windows)]
mod audio;
#[cfg(windows)]
mod automation;
#[cfg(windows)]
mod desktop;
#[cfg(windows)]
mod editor;
#[cfg(windows)]
mod gpu;
#[cfg(windows)]
mod layout;
#[cfg(windows)]
mod media;
#[cfg(windows)]
mod metrics;
#[cfg(windows)]
mod obs;
#[cfg(windows)]
mod protocol;
#[cfg(windows)]
mod routine;
#[cfg(windows)]
mod server;
#[cfg(windows)]
mod store;
#[cfg(windows)]
mod tray;
#[cfg(windows)]
mod updater;
#[cfg(windows)]
mod window_control;
#[cfg(windows)]
mod windows_host;

#[cfg(windows)]
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let mut flags = std::env::args_os().skip(1);
    if flags.next().is_some_and(|flag| flag == "--apply-release") {
        return updater::apply(flags);
    }
    server::start().await
}

#[cfg(not(windows))]
fn main() {
    eprintln!("KenshixDeck Windows 11 gerektirir.");
}
