#[cfg(windows)]
mod audio;
#[cfg(windows)]
mod desktop;
#[cfg(windows)]
mod obs;
#[cfg(windows)]
mod protocol;
#[cfg(windows)]
mod server;
#[cfg(windows)]
mod store;
#[cfg(windows)]
mod tray;
#[cfg(windows)]
mod windows_host;

#[cfg(windows)]
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    server::start().await
}

#[cfg(not(windows))]
fn main() {
    eprintln!("KenshixDeck Windows 11 gerektirir.");
}
