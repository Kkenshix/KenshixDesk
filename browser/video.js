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
const FRAME_LIMIT = 512 * 1024;
const TRANSFER_HEIGHTS = [1080, 900, 720, 540, 360];
const hostname = location.hostname;
const site = /(^|\.)youtube(?:-nocookie)?\.com$/.test(hostname) ? 'youtube'
    : /(^|\.)kick\.com$/.test(hostname) ? 'kick' : 'opera';

function select() {
    const active = source?.video;
    if (playback && active?.isConnected && !active.ended && !active.mediaKeys) return source;
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
    if (playback && source?.video.videoWidth && source.video.videoHeight) {
        const {id,key,level,inputWidth,inputHeight} = playback;
        if (source.video.videoWidth !== inputWidth || source.video.videoHeight !== inputHeight) {
            void start({id,key},level);
        }
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

function transferSize(video, level) {
    const portrait = video.videoHeight > video.videoWidth;
    const short = TRANSFER_HEIGHTS[level];
    const long = short * 16 / 9;
    const ratio = Math.min(1, (portrait ? short : long) / video.videoWidth,
        (portrait ? long : short) / video.videoHeight);
    return {
        width:2 * Math.max(1, Math.floor(video.videoWidth * ratio / 2)),
        height:2 * Math.max(1, Math.floor(video.videoHeight * ratio / 2))
    };
}

async function start(message, level = 0, preferAvc = true) {
    stop();
    const selected = source;
    if (selected?.key !== message.key) return;
    const {width,height} = transferSize(selected.video,level);
    const run = {id:message.id,key:message.key,level,inputWidth:selected.video.videoWidth,
        inputHeight:selected.video.videoHeight,needsKey:true,forceKey:false};
    playback = run;
    let codec = 'vp8';
    function failed() {
        if (playback !== run) return;
        if (codec === 'h264') {
            void start(message,level,false);
        } else {
            port.postMessage({type:'unavailable',id:run.id});
            stop();
        }
    }
    try {
        const bitrate = Math.max(3_600_000, Math.round(11_000_000 * width * height / (1920 * 1080)));
        const framerate = width * height <= 1280 * 720 ? 90 : 60;
        let config = {codec:'vp8',width,height,bitrate,framerate,latencyMode:'realtime',
            hardwareAcceleration:'prefer-software'};
        if (preferAvc && width * height >= 640 * 360) {
            const avc = {...config,codec:'avc1.42E02A',avc:{format:'annexb'}};
            const support = await VideoEncoder.isConfigSupported(avc).catch(() => null);
            if (playback !== run) return;
            if (support?.supported) {
                config = avc;
                codec = 'h264';
            }
        }
        if (selected.video.mediaKeys) throw Error('Encrypted video');
        const stream = selected.video.captureStream();
        for (const track of stream.getAudioTracks()) track.stop();
        const track = stream.getVideoTracks()[0];
        if (!track) throw Error('Video capture not available');
        run.track = track;
        let begun = false;
        let published = 0;
        const encoder = new VideoEncoder({
            output(chunk) {
                if (playback !== run) return;
                const keyframe = chunk.type === 'key';
                if (chunk.byteLength > FRAME_LIMIT - 9) {
                    run.needsKey = true;
                    return;
                }
                if (run.needsKey && !keyframe) return;
                run.needsKey = false;
                clearTimeout(run.noFrames);
                published++;
                if (!begun) {
                    begun = true;
                    port.postMessage({type:'begin',id:run.id,width,height,codec});
                }
                const packet = new Uint8Array(9 + chunk.byteLength);
                packet[0] = keyframe ? 1 : 0;
                new DataView(packet.buffer).setBigUint64(1,BigInt(Math.max(0,chunk.timestamp)));
                chunk.copyTo(packet.subarray(9));
                const data = packet.toBase64 ? packet.toBase64() : encodeBase64(packet);
                port.postMessage({type:'frame',id:run.id,keyframe,data});
            },
            error: failed
        });
        run.encoder = encoder;
        encoder.configure(config);
        const reader = new MediaStreamTrackProcessor({track,maxBufferSize:3}).readable.getReader();
        run.reader = reader;
        run.noFrames = setTimeout(() => {
            if (playback === run && !selected.video.paused) {
                failed();
            }
        },2400);
        let keyAt = -Infinity;
        let windowStart;
        let received = 0;
        let slowWindows = 0;
        while (playback === run) {
            const {value:frame,done} = await reader.read();
            if (done) break;
            try {
                if (playback !== run) continue;
                windowStart ??= frame.timestamp;
                if (frame.timestamp - windowStart >= 1_100_000) {
                    if (framerate === 60 && received * 1_000_000 > (frame.timestamp - windowStart) * 75) {
                        void start(message,2,preferAvc);
                        return;
                    }
                    slowWindows = received >= 22 && published < received * .87 ? slowWindows + 1 : 0;
                    if (slowWindows >= 2) {
                        for (let next = level + 1; next < TRANSFER_HEIGHTS.length; next++) {
                            const smaller = transferSize(selected.video,next);
                            if (smaller.width === width && smaller.height === height) continue;
                            void start(message,next,preferAvc);
                            return;
                        }
                    }
                    windowStart = frame.timestamp;
                    received = 0;
                    published = 0;
                }
                received++;
                if (encoder.encodeQueueSize > 2) continue;
                const keyFrame = run.forceKey || frame.timestamp - keyAt >= 500_000;
                if (keyFrame) keyAt = frame.timestamp;
                run.forceKey = false;
                encoder.encode(frame,{keyFrame});
            } finally { frame.close(); }
        }
        if (playback === run) throw Error('Video ended');
    } catch {
        failed();
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
            if (message.type === 'keyframe' && playback?.id === message.id) playback.forceKey = true;
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
    for (const event of ['playing','pause','ended','volumechange','loadeddata','visibilitychange','resize']) {
        document.addEventListener(event,() => {
            if (source || event !== 'visibilitychange') sample();
        },true);
    }
    window.setInterval(sample,1200);
    sample();
}
