let port;
let source;
let since = 0;
let lastVideo;
let wasPlaying = false;
let playback;
let retry;

function select() {
    const url = new URL(location.href);
    const video = [...document.querySelectorAll('video')]
        .filter(item => item.readyState >= 2 && !item.ended && item.videoWidth > 0)
        .sort((a,b) => b.videoWidth * b.videoHeight - a.videoWidth * a.videoHeight)[0];
    if (!video) return null;
    if (url.hostname === 'www.youtube.com') {
        const key = url.searchParams.get('v') || url.pathname.match(/^\/(?:shorts|live)\/([^/]+)\/?$/)?.[1];
        if (/^[\w-]{11}$/.test(key ?? '')) return {site:'youtube',key,video};
    } else {
        const key = url.pathname.match(/^\/([a-z0-9_-]{2,50})\/?$/i)?.[1]?.toLowerCase();
        if (key) return {site:'kick',key,video};
    }
    return null;
}

function sample() {
    const previous = source;
    source = select();
    if (source?.video !== lastVideo || source?.key !== previous?.key) {
        lastVideo = source?.video;
        since = Date.now();
        if (playback && source?.key === playback.key) {
            const request = {id:playback.id,key:playback.key};
            stop();
            if (source) void start(request);
        } else stop();
    }
    const playing = source ? !source.video.paused : false;
    if (playing && !wasPlaying && !playback) since = Date.now();
    wasPlaying = playing;
    const media = source ? {
        site:source.site,key:source.key,started:since,playing,
        volume:source.video.muted ? 0 : Math.round(source.video.volume * 100)
    } : null;
    try { port?.postMessage({type:'sample',media}); } catch {}
}

function stop() {
    const old = playback;
    playback = null;
    if (!old) return;
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
        const canvas = new OffscreenCanvas(width,height);
        const context = canvas.getContext('2d',{alpha:false});
        if (!context) throw Error('Frame scaling unavailable');
        const encoder = new VideoEncoder({
            output(chunk) {
                if (playback !== run || chunk.byteLength > 256 * 1024 - 9) return;
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
        encoder.configure({codec:'vp8',width,height,bitrate:1_550_000,framerate:30,latencyMode:'realtime'});
        const reader = new MediaStreamTrackProcessor({track,maxBufferSize:2}).readable.getReader();
        run.reader = reader;
        port.postMessage({type:'begin',id:run.id,width,height});
        let latest = -Infinity;
        let keyAt = -Infinity;
        while (playback === run) {
            const {value:frame,done} = await reader.read();
            if (done) break;
            try {
                if (playback !== run || frame.timestamp - latest < 32_000 || encoder.encodeQueueSize > 1) continue;
                context.drawImage(frame,0,0,width,height);
                const scaled = new VideoFrame(canvas,{timestamp:frame.timestamp});
                try {
                    const keyFrame = frame.timestamp - keyAt >= 500_000;
                    if (keyFrame) keyAt = frame.timestamp;
                    latest = frame.timestamp;
                    encoder.encode(scaled,{keyFrame});
                } finally { scaled.close(); }
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
        sample();
    } catch {}
}

for (const event of ['playing','pause','ended','volumechange','loadeddata','visibilitychange']) {
    document.addEventListener(event,sample,true);
}
window.setInterval(sample,900);
connect();
