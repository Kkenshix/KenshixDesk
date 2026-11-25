let port;
let source;
let since = 0;
let lastVideo;
let wasPlaying = false;
let playback;
let retry;
let lastShadowScan = -Infinity;
let shadowRoots = [];
const videoKeys = new WeakMap();
const hostname = location.hostname;
const site = /(^|\.)youtube(?:-nocookie)?\.com$/.test(hostname) ? 'youtube'
    : /(^|\.)kick\.com$/.test(hostname) ? 'kick' : 'opera';

function select() {
    const candidates = [...document.querySelectorAll('video')];
    if (!candidates.length && performance.now() - lastShadowScan >= 4000) {
        lastShadowScan = performance.now();
        const roots = [document];
        for (const root of roots) {
            for (const node of root.querySelectorAll('*')) {
                if (node.shadowRoot) roots.push(node.shadowRoot);
            }
        }
        shadowRoots = roots.slice(1);
    }
    shadowRoots = shadowRoots.filter(root => root.host.isConnected);
    for (const root of shadowRoots) candidates.push(...root.querySelectorAll('video'));
    let best = null;
    for (const video of candidates) {
        if (video.readyState < 2 || video.ended || !video.videoWidth || video.mediaKeys) continue;
        const rect = video.getBoundingClientRect();
        const area = Math.round(rect.width * rect.height);
        if (area < 1024 || (best && (best.area > area ||
            (best.area === area && (video.paused || !best.video.paused))))) continue;
        if (!videoKeys.has(video)) videoKeys.set(video, crypto.randomUUID());
        best = {site,key:videoKeys.get(video),video,area};
    }
    return best;
}

function sample() {
    source = select();
    if (source?.video !== lastVideo) {
        lastVideo = source?.video;
        since = Date.now();
        stop();
    }
    const playing = source ? !source.video.paused : false;
    if (playing && !wasPlaying && !playback) since = Date.now();
    wasPlaying = playing;
    const media = source ? {
        site:source.site,key:source.key,started:since,playing,area:source.area,
        volume:source.video.muted ? 0 : Math.round(source.video.volume * 100)
    } : null;
    if (!port && media) connect();
    try { port?.postMessage({type:'sample',media}); } catch {}
}

function stop() {
    const old = playback;
    playback = null;
    if (!old) return;
    clearTimeout(old.noFrames);
    old.reader?.cancel().catch(() => {});
    if (old.encoder?.state !== 'closed') old.encoder?.close();
    old.track?.stop();
}

function encodeBase64(packet) {
    let bytes = '';
    for (let i = 0; i < packet.length; i += 8192) {
        bytes += String.fromCharCode(...packet.subarray(i, i + 8192));
    }
    return btoa(bytes);
}

async function start(message) {
    stop();
    const selected = source;
    if (selected?.key !== message.key) return;
    const run = {id:message.id,key:message.key};
    playback = run;
    try {
        if (selected.video.mediaKeys) throw Error('Encrypted video');
        const stream = selected.video.captureStream();
        for (const track of stream.getAudioTracks()) track.stop();
        const track = stream.getVideoTracks()[0];
        if (!track) throw Error('Video capture not available');
        run.track = track;
        const ratio = Math.min(1, 960 / selected.video.videoWidth, 540 / selected.video.videoHeight);
        const width = 2 * Math.max(1, Math.floor(selected.video.videoWidth * ratio / 2));
        const height = 2 * Math.max(1, Math.floor(selected.video.videoHeight * ratio / 2));
        const encoder = new VideoEncoder({
            output(chunk) {
                if (playback !== run || chunk.byteLength > 256 * 1024 - 9) return;
                clearTimeout(run.noFrames);
                const packet = new Uint8Array(9 + chunk.byteLength);
                packet[0] = chunk.type === 'key' ? 1 : 0;
                new DataView(packet.buffer).setBigUint64(1,BigInt(Math.max(0,chunk.timestamp)));
                chunk.copyTo(packet.subarray(9));
                const data = packet.toBase64 ? packet.toBase64() : encodeBase64(packet);
                port.postMessage({type:'frame',id:run.id,keyframe:packet[0] === 1,data});
            },
            error() {
                if (playback === run) {
                    port.postMessage({type:'unavailable',id:run.id});
                    stop();
                }
            }
        });
        run.encoder = encoder;
        encoder.configure({codec:'vp8',width,height,bitrate:3_600_000,framerate:60,
            latencyMode:'realtime',hardwareAcceleration:'prefer-software'});
        const reader = new MediaStreamTrackProcessor({track,maxBufferSize:3}).readable.getReader();
        run.reader = reader;
        port.postMessage({type:'begin',id:run.id,width,height});
        run.noFrames = setTimeout(() => {
            if (playback === run && !selected.video.paused) {
                port.postMessage({type:'unavailable',id:run.id});
                stop();
            }
        },2400);
        let keyAt = -Infinity;
        while (playback === run) {
            const {value:frame,done} = await reader.read();
            if (done) break;
            try {
                if (playback !== run || encoder.encodeQueueSize > 2) continue;
                const keyFrame = frame.timestamp - keyAt >= 500_000;
                if (keyFrame) keyAt = frame.timestamp;
                encoder.encode(frame,{keyFrame});
            } finally { frame.close(); }
        }
        if (playback === run) throw Error('Video ended');
    } catch {
        if (playback === run) {
            port.postMessage({type:'unavailable',id:run.id});
            stop();
        }
    }
}

function connect() {
    clearTimeout(retry);
    try {
        const next = chrome.runtime.connect({name:'video'});
        port = next;
        next.onMessage.addListener(message => {
            if (message.type === 'start') void start(message);
            if (message.type === 'stop' && playback?.id === message.id) stop();
            if (message.type === 'volume' && playback?.id === message.id && source?.key === message.key &&
                Number.isInteger(message.percent) && message.percent >= 0 && message.percent <= 100) {
                source.video.volume = message.percent / 100;
                if (message.percent > 0) source.video.muted = false;
                sample();
            }
        });
        next.onDisconnect.addListener(() => {
            if (port !== next) return;
            stop();
            port = undefined;
            retry = setTimeout(connect,900);
        });
    } catch {}
}

if (globalThis.VideoEncoder && globalThis.MediaStreamTrackProcessor) {
    for (const event of ['playing','pause','ended','volumechange','loadeddata','visibilitychange']) {
        document.addEventListener(event,() => {
            if (source || event !== 'visibilitychange') sample();
        },true);
    }
    window.setInterval(sample,1200);
    sample();
}
