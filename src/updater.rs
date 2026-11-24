use std::{
    ffi::OsStr,
    fs::{self, File},
    io::{Read, Write},
    os::windows::ffi::OsStrExt,
    path::{Path, PathBuf},
    process::Command,
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use ring::signature::{ED25519, UnparsedPublicKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use windows::{
    Win32::{
        Foundation::CloseHandle,
        Storage::FileSystem::{MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH, MoveFileExW},
        System::Threading::{OpenProcess, PROCESS_SYNCHRONIZE, WaitForSingleObject},
    },
    core::PCWSTR,
};

const REPO: &str = "https://github.com/Kkenshix/KenshixDesk/releases/";
const APK: &str = "KenshixDeck-Android.apk";
const EXE: &str = "KenshixDeck-Windows-x64.exe";
const PUBLIC_KEY: Option<&str> = option_env!("DECK_UPDATE_PUBLIC_KEY");
static CHECK_NOW: AtomicBool = AtomicBool::new(false);

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Manifest {
    product: String,
    channel: String,
    version: String,
    minimum_build: u32,
    build: u32,
    commit: String,
    published: String,
    protocol: u8,
    windows: Asset,
    android: Asset,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Asset {
    bytes: u64,
    sha256: String,
}

#[derive(Serialize)]
struct Status {
    message: String,
    checked_unix: u64,
    version: &'static str,
    commit: &'static str,
    build: u32,
}

fn build() -> u32 {
    option_env!("DECK_BUILD")
        .and_then(|value| value.parse().ok())
        .unwrap_or(0)
}

fn status_path(directory: &Path) -> PathBuf {
    directory.join("update-status.json")
}

fn status(directory: &Path, message: &str) {
    let state = Status {
        message: message.into(),
        checked_unix: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        version: env!("CARGO_PKG_VERSION"),
        commit: option_env!("DECK_SHA").unwrap_or("local"),
        build: build(),
    };
    if let Ok(value) = serde_json::to_vec(&state) {
        let staged = directory.join("update-status.tmp");
        if fs::write(&staged, value).is_ok() {
            let _ = replace(&staged, &status_path(directory));
        }
    }
}

pub(crate) fn current(directory: &Path) -> String {
    let Some(state) = read_status(directory) else {
        return "Güncelleme durumu bekleniyor".into();
    };
    format!(
        "{} · Son kontrol: Unix {}",
        state["message"].as_str().unwrap_or("Durum bilinmiyor"),
        state["checked_unix"].as_u64().unwrap_or(0)
    )
}

pub(crate) fn read_status(directory: &Path) -> Option<serde_json::Value> {
    serde_json::from_slice(&fs::read(status_path(directory)).ok()?).ok()
}

pub(crate) fn request_check() {
    CHECK_NOW.store(true, Ordering::Relaxed);
}

fn verify(data: &[u8], signature: &[u8], key: &[u8]) -> Result<Manifest> {
    UnparsedPublicKey::new(&ED25519, key)
        .verify(data, signature)
        .map_err(|_| anyhow::anyhow!("Yayın imzası geçersiz"))?;
    let manifest: Manifest = serde_json::from_slice(data)?;
    ensure!(
        manifest.product == "KenshixDeck"
            && manifest.channel == "stable"
            && manifest.protocol == crate::protocol::VERSION
            && manifest.build > 0
            && manifest.build <= i32::MAX as u32
            && manifest.minimum_build <= manifest.build
            && manifest.commit.len() == 40
            && manifest.commit.bytes().all(|b| b.is_ascii_hexdigit())
            && manifest.version.split('.').count() == 3
            && manifest
                .version
                .split('.')
                .all(|part| !part.is_empty() && part.parse::<u16>().is_ok())
            && !manifest.published.is_empty()
            && manifest.published.len() <= 32,
        "Yayın manifesti uyumsuz"
    );
    for asset in [&manifest.windows, &manifest.android] {
        ensure!(
            (1..=96 * 1024 * 1024).contains(&asset.bytes) && crate::art::valid_id(&asset.sha256),
            "Sürüm dosyası metadatası geçersiz"
        );
    }
    Ok(manifest)
}

struct Checks {
    agent: ureq::Agent,
    etag: Option<String>,
    latest: Option<(Manifest, Vec<u8>, Vec<u8>)>,
    key: Vec<u8>,
}

impl Checks {
    fn refresh(&mut self) -> Result<()> {
        let mut request = self
            .agent
            .get(format!("{REPO}latest/download/manifest.json"));
        if let Some(etag) = &self.etag {
            request = request.header("If-None-Match", etag.as_str());
        }
        let mut response = request.call()?;
        if response.status() == 304 {
            return Ok(());
        }
        ensure!(response.status() == 200, "Yayın manifesti alınamadı");
        let etag = response
            .headers()
            .get("etag")
            .and_then(|header| header.to_str().ok())
            .map(str::to_owned);
        let bytes = read_limited(response.body_mut().as_reader(), 8192)?;
        let unsigned: Manifest = serde_json::from_slice(&bytes)?;
        let url = asset_url(unsigned.build, "manifest.sig");
        let mut response = self.agent.get(url).call()?;
        let signature = read_limited(response.body_mut().as_reader(), 64)?;
        let manifest = verify(&bytes, &signature, &self.key)?;
        self.etag = etag;
        self.latest = Some((manifest, bytes, signature));
        Ok(())
    }
}

fn asset_url(build: u32, name: &str) -> String {
    format!("{REPO}download/build-{build}/{name}")
}

fn read_limited(input: impl Read, maximum: u64) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    input.take(maximum + 1).read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() as u64 <= maximum,
        "Yayın dosyası limit üzerinde"
    );
    Ok(bytes)
}

fn verify_file(path: &Path, asset: &Asset) -> Result<()> {
    let mut file = File::open(path)?;
    ensure!(
        file.metadata()?.len() == asset.bytes,
        "Dosya boyutu uyuşmuyor"
    );
    let mut digest = Sha256::new();
    std::io::copy(&mut file, &mut DigestWriter(&mut digest))?;
    ensure!(
        format!("{:x}", digest.finalize()) == asset.sha256,
        "Dosya SHA-256 uyuşmuyor"
    );
    Ok(())
}

struct DigestWriter<'a>(&'a mut Sha256);
impl Write for DigestWriter<'_> {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.0.update(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn download(
    agent: &ureq::Agent,
    manifest: &Manifest,
    name: &str,
    asset: &Asset,
    output: &Path,
) -> Result<()> {
    if verify_file(output, asset).is_ok() {
        return Ok(());
    }
    let temporary = output.with_extension("part");
    let result = (|| {
        let mut response = agent.get(asset_url(manifest.build, name)).call()?;
        ensure!(response.status() == 200, "Yayın dosyası alınamadı");
        let mut file = File::create(&temporary)?;
        let read = std::io::copy(
            &mut response.body_mut().as_reader().take(asset.bytes + 1),
            &mut file,
        )?;
        ensure!(read == asset.bytes, "İndirme boyutu uyuşmuyor");
        file.sync_all()?;
        drop(file);
        verify_file(&temporary, asset)?;
        replace(&temporary, output)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn replace(source: &Path, target: &Path) -> Result<()> {
    let source: Vec<_> = source.as_os_str().encode_wide().chain([0]).collect();
    let target: Vec<_> = target.as_os_str().encode_wide().chain([0]).collect();
    // SAFETY: NUL-terminated same-volume paths remain live for the atomic NTFS rename.
    unsafe {
        MoveFileExW(
            PCWSTR(source.as_ptr()),
            PCWSTR(target.as_ptr()),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    }?;
    Ok(())
}

pub(crate) fn launch(directory: PathBuf, adb: Option<(String, String)>) {
    let _ = std::thread::Builder::new()
        .name("deck-updates".into())
        .spawn(move || {
            let Some(key) = PUBLIC_KEY
                .and_then(|key| STANDARD.decode(key).ok())
                .filter(|key| key.len() == 32)
            else {
                status(&directory, "Bu derlemede yayın anahtarı ayarlanmadı");
                return;
            };
            status(&directory, "İlk sürüm kontrolü bekleniyor");
            let mut state = Checks {
                agent: ureq::Agent::config_builder()
                    .timeout_global(Some(Duration::from_secs(100)))
                    .https_only(true)
                    .build()
                    .into(),
                etag: None,
                latest: None,
                key,
            };
            let mut delay = 1;
            loop {
                for _ in 0..delay {
                    if CHECK_NOW.swap(false, Ordering::Relaxed) {
                        break;
                    }
                    std::thread::sleep(Duration::from_secs(1));
                }
                match cycle(&directory, &mut state, adb.as_ref()) {
                    Ok(message) => {
                        status(&directory, message);
                        delay = 30;
                    }
                    Err(_) => {
                        status(&directory, "Sürüm doğrulanamadı veya yükleme ertelendi");
                        delay = (delay * 2).clamp(30, 300);
                    }
                }
            }
        });
}

fn cycle(
    directory: &Path,
    state: &mut Checks,
    adb: Option<&(String, String)>,
) -> Result<&'static str> {
    state.refresh()?;
    let (manifest, bytes, signature) = state.latest.as_ref().context("Yayın yok")?;
    ensure!(
        manifest.build >= build() && build() >= manifest.minimum_build,
        "Kurulum sürümü uyumsuz"
    );
    let exe = std::env::current_exe()?;
    let folder = exe.parent().context("Uygulama klasörü yok")?;
    let staged = folder.join(".kenshixdeck-next.exe");
    let newer = manifest.build > build();
    if newer {
        download(&state.agent, manifest, EXE, &manifest.windows, &staged)?;
    }
    let phone = adb
        .map(|(adb, serial)| update_phone(directory, state, manifest, adb, serial))
        .transpose();
    if newer && !crate::routine::busy() {
        let helper = folder.join(format!(".kenshixdeck-updater-{}.exe", std::process::id()));
        let manifest_path = folder.join(".kenshixdeck-manifest.json");
        let signature_path = folder.join(".kenshixdeck-manifest.sig");
        fs::write(&manifest_path, bytes)?;
        fs::write(&signature_path, signature)?;
        fs::copy(&exe, &helper)?;
        let args: Vec<_> = std::env::args_os()
            .skip(1)
            .filter(|arg| arg != "--pair")
            .collect();
        status(directory, "Windows güncellemesi kuruluyor");
        if !crate::routine::prepare_exit() {
            return Ok("Makro bittiğinde güncelleme kurulacak");
        }
        if let Err(error) = Command::new(&helper)
            .arg("--apply-release")
            .arg(std::process::id().to_string())
            .arg(&exe)
            .args(args)
            .spawn()
        {
            crate::routine::resume_after_update_error();
            return Err(error.into());
        }
        std::process::exit(0);
    }
    if matches!(phone?, Some(false)) {
        Ok("Android USB bağlantısı bekleniyor")
    } else if newer {
        Ok("Makro bittiğinde güncelleme kurulacak")
    } else {
        Ok("Sürüm güncel")
    }
}

fn update_phone(
    directory: &Path,
    state: &Checks,
    manifest: &Manifest,
    adb: &str,
    serial: &str,
) -> Result<bool> {
    let adb = |args: &[&str]| Command::new(adb).args(["-s", serial]).args(args).output();
    let devices = adb(&["get-state"])?;
    if !devices.status.success() || !String::from_utf8_lossy(&devices.stdout).trim().eq("device") {
        return Ok(false);
    }
    let package = || adb(&["shell", "dumpsys", "package", "com.kenshix.deck"]);
    let version = |data: &[u8]| -> Option<u32> {
        String::from_utf8_lossy(data)
            .split_whitespace()
            .find_map(|word| word.strip_prefix("versionCode=")?.parse().ok())
    };
    let before = version(&package()?.stdout).context("KenshixDeck Android kurulu değil")?;
    if before >= manifest.build {
        return Ok(true);
    }
    let apk = directory.join("pending-update.apk");
    download(&state.agent, manifest, APK, &manifest.android, &apk)?;
    let path = apk.to_str().context("APK yolu geçersiz")?;
    let result = adb(&["install", "-r", path])?;
    ensure!(
        result.status.success() && version(&package()?.stdout) == Some(manifest.build),
        "Android güncellenemedi"
    );
    let _ = fs::remove_file(apk);
    let owner = adb(&["shell", "dpm", "list-owners"])?;
    let activity =
        if String::from_utf8_lossy(&owner.stdout).contains("com.kenshix.deck/.DeckAdminReceiver") {
            ".DeckHome"
        } else {
            ".MainActivity"
        };
    let component = format!("com.kenshix.deck/{activity}");
    let result = adb(&["shell", "am", "start", "-n", &component])?;
    ensure!(
        result.status.success(),
        "Android uygulaması yeniden açılamadı"
    );
    Ok(true)
}

pub(crate) fn apply(mut args: impl Iterator<Item = std::ffi::OsString>) -> Result<()> {
    let pid: u32 = args
        .next()
        .and_then(|arg| arg.to_str()?.parse().ok())
        .context("Host PID bulunamadı")?;
    let exe = PathBuf::from(args.next().context("Kurulum yolu yok")?);
    let own = std::env::current_exe()?;
    let folder = own.parent().context("Güncelleyici yolu yok")?;
    ensure!(
        own.file_name()
            .is_some_and(|name| name == OsStr::new(&format!(".kenshixdeck-updater-{pid}.exe")))
            && exe.parent() == Some(folder),
        "Güncelleme konumu uyumsuz"
    );
    let staged = folder.join(".kenshixdeck-next.exe");
    let manifest = folder.join(".kenshixdeck-manifest.json");
    let signature = folder.join(".kenshixdeck-manifest.sig");
    let key = STANDARD.decode(PUBLIC_KEY.context("Yayın anahtarı yok")?)?;
    let result = (|| {
        let verified = verify(
            &read_limited(File::open(&manifest)?, 8192)?,
            &read_limited(File::open(&signature)?, 64)?,
            &key,
        )?;
        ensure!(
            verified.build > build() && build() >= verified.minimum_build,
            "Sürüm geriye alınamaz"
        );
        verify_file(&staged, &verified.windows)?;
        // SAFETY: The process handle is waited on for 20 seconds and closed on all paths.
        unsafe {
            if let Ok(handle) = OpenProcess(PROCESS_SYNCHRONIZE, false, pid) {
                let wait = WaitForSingleObject(handle, 20_000);
                CloseHandle(handle)?;
                ensure!(wait.0 == 0, "Host kapanmadı");
            }
        }
        replace(&staged, &exe)?;
        Ok(())
    })();
    let _ = fs::remove_file(manifest);
    let _ = fs::remove_file(signature);
    Command::new(&exe).args(args).spawn()?;
    result
}
#[cfg(test)]
mod tests {
    use super::*;
    use ring::{
        rand::SystemRandom,
        signature::{Ed25519KeyPair, KeyPair},
    };
    use serde_json::json;

    #[test]
    fn signed_manifest_rejects_tampering_unknown_versions_and_sizes() -> Result<()> {
        let random = SystemRandom::new();
        let raw = Ed25519KeyPair::generate_pkcs8(&random)
            .map_err(|_| anyhow::anyhow!("key generation failed"))?;
        let key = Ed25519KeyPair::from_pkcs8(raw.as_ref())
            .map_err(|_| anyhow::anyhow!("key decode failed"))?;
        let asset = json!({"bytes":123,"sha256":"a".repeat(64)});
        let data = json!({
            "product":"KenshixDeck", "channel":"stable", "version":"0.3.0", "build":1234,
            "minimum_build":0, "commit":"a".repeat(40), "published":"2026-11-25T00:00:00Z",
            "protocol":crate::protocol::VERSION, "windows":asset, "android":asset
        });
        let encode = |value: &serde_json::Value| serde_json::to_vec(value).expect("JSON");
        let bytes = encode(&data);
        let signature = key.sign(&bytes);
        assert_eq!(
            verify(&bytes, signature.as_ref(), key.public_key().as_ref())?.build,
            1234
        );
        let mut changed = data.clone();
        changed["build"] = json!(1233);
        assert!(
            verify(
                &encode(&changed),
                signature.as_ref(),
                key.public_key().as_ref()
            )
            .is_err()
        );
        changed["protocol"] = json!(99);
        let bytes = encode(&changed);
        let signature = key.sign(&bytes);
        assert!(verify(&bytes, signature.as_ref(), key.public_key().as_ref()).is_err());
        changed["protocol"] = json!(crate::protocol::VERSION);
        changed["android"]["bytes"] = json!(500_000_000_u64);
        let bytes = encode(&changed);
        assert!(verify(&bytes, key.sign(&bytes).as_ref(), key.public_key().as_ref()).is_err());
        Ok(())
    }
}
