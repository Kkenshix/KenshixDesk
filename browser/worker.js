importScripts('config.js');

const frames = new Map();
const unavailable = new Map();
let socket;
let ready = false;
let current;
let dismissed;
let focused;
let nativeFocused;
let lastActive;
let status;
let serial = 0;

function connect() {
    if (socket && socket.readyState < WebSocket.CLOSING) return;
    const candidate = new WebSocket('ws://127.0.0.1:43852/capture');
    socket = candidate;
    candidate.onopen = () => candidate.send(JSON.stringify({key: bridgeKey}));
    candidate.onmessage = event => {
        if (socket !== candidate) return;
        let reply;
        try { reply = JSON.parse(event.data); } catch { return; }
        if (reply.type === 'ready') {
            ready = true;
            status = undefined;
            trace('ready');
            void refresh();
        }
        if (reply.type === 'foreground' && typeof reply.value === 'boolean') {
            nativeFocused = reply.value;
            if (nativeFocused && lastActive !== undefined) focused = lastActive;
            choose();
            if (nativeFocused) void refresh();
        }
        if (reply.type === 'volume' && current) {
            current.port.postMessage({...reply,id:current.id,key:current.key});
        }
        if (reply.type === 'stop' && current) {
            dismissed = current.tab;
            stop();
        }
    };
    candidate.onclose = () => {
        if (socket !== candidate) return;
        ready = false;
        nativeFocused = undefined;
        stop();
    };
}

function transmit(message) {
    if (ready && socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

function trace(stage) {
    if (stage !== status) {
        status = stage;
        transmit({type:'status',stage});
    }
}

function stop() {
    const previous = current;
    if (!previous) return;
    current = null;
    transmit({type:'end'});
    try { previous.port.postMessage({type:'stop', id:previous.id}); } catch {}
}

function choose() {
    if (!ready) return;
    const foreground = nativeFocused === false ? null : focused;
    if (foreground === undefined) return;
    if (current) {
        const data = frames.get(current.frame);
        if (current.tab === foreground || data?.media?.key !== current.key || data.port !== current.port) {
            stop();
        }
    }
    if (foreground !== null && dismissed === foreground) dismissed = undefined;
    const players = new Map();
    for (const entry of frames.values()) {
        const media = entry.media;
        if (!media?.playing || entry.tab === dismissed || unavailable.get(entry.frame) === media.key) continue;
        const best = players.get(entry.tab)?.media;
        if (!best || media.area > best.area || (media.area === best.area && media.started > best.started)) {
            players.set(entry.tab,entry);
        }
    }
    const newest = [...players.values()].sort((a,b) => b.media.started - a.media.started)[0];
    if (!newest) {
        trace(current ? 'paused' : 'no_media');
        return;
    }
    const {tab,frame,port,media} = newest;
    trace(current?.begun ? 'capturing' : 'playing');
    if (tab === foreground) {
        stop();
        return;
    }
    if (current?.port === port && current.key === media.key) return;
    const old = current && frames.get(current.frame)?.media;
    if (old && !old.playing && media.started <= old.started) return;
    stop();
    current = {tab,frame,port,key:media.key,id:crypto.randomUUID(),begun:false};
    try { port.postMessage({type:'start',id:current.id,key:current.key}); }
    catch { frames.delete(frame); stop(); }
}

async function refresh(windowId) {
    const revision = ++serial;
    if (windowId === chrome.windows.WINDOW_ID_NONE && nativeFocused !== true) {
        focused = null;
        choose();
        return;
    }
    try {
        const window = await chrome.windows.getLastFocused({populate:true});
        if (revision !== serial) return;
        const active = window.tabs?.find(tab => tab.active)?.id ??
            (await chrome.tabs.query({windowId:window.id,active:true}))[0]?.id;
        if (revision !== serial) return;
        if (active !== undefined) lastActive = active;
        focused = (nativeFocused === true || window.focused) && window.state !== 'minimized'
            ? lastActive ?? null : null;
        choose();
    } catch {}
}

function sendFrame(message) {
    if (socket?.bufferedAmount > 192 * 1024 || message.data.length > 350000) {
        current.needsKey = true;
        return;
    }
    if (current.needsKey && !message.keyframe) return;
    try {
        const bytes = Uint8Array.fromBase64 ? Uint8Array.fromBase64(message.data)
            : Uint8Array.from(atob(message.data), value => value.charCodeAt(0));
        if (bytes.length < 10 || bytes.length > 256 * 1024) return;
        socket.send(bytes);
        current.needsKey = false;
    } catch {
        current.needsKey = true;
    }
}

chrome.runtime.onConnect.addListener(port => {
    const tab = port.sender?.tab;
    if (port.name !== 'video' || !Number.isInteger(tab?.id) || tab.incognito) return;
    const frame = tab.id + ':' + (port.sender.frameId ?? 0);
    frames.set(frame,{tab:tab.id,frame,port,media:null});
    unavailable.delete(frame);
    connect();
    void refresh();
    port.onMessage.addListener(message => {
        const entry = frames.get(frame);
        if (entry?.port !== port) return;
        if (message.type === 'sample') {
            const previous = entry.media;
            entry.media = message.media;
            if (previous?.key !== entry.media?.key || (!previous?.playing && entry.media?.playing)) {
                unavailable.delete(frame);
            }
            if (current?.port === port && current.begun && entry.media?.key === current.key) {
                if (previous?.playing !== entry.media.playing) transmit({type:'paused',value:!entry.media.playing});
                if (previous?.volume !== entry.media.volume) transmit({type:'volume',percent:entry.media.volume});
            }
            connect();
            choose();
        } else if (current?.port === port && current.id === message.id) {
            if (message.type === 'begin' && entry.media) {
                current.begun = true;
                trace('capturing');
                current.needsKey = true;
                transmit({type:'begin',video:{id:current.id,site:entry.media.site,volume:entry.media.volume,
                    width:message.width,height:message.height}});
            } else if (message.type === 'frame' && current.begun && ready) {
                sendFrame(message);
            } else if (message.type === 'unavailable') {
                unavailable.set(frame,current.key);
                stop();
                trace('no_frames');
                transmit({type:'unavailable'});
                choose();
            }
        }
    });
    port.onDisconnect.addListener(() => {
        if (frames.get(frame)?.port === port) {
            frames.delete(frame);
            unavailable.delete(frame);
        }
        choose();
    });
});

chrome.windows.onFocusChanged.addListener(refresh);
chrome.tabs.onActivated.addListener(async info => {
    const revision = ++serial;
    try {
        const window = await chrome.windows.get(info.windowId);
        if (revision !== serial) return;
        if (window.focused || nativeFocused === true) {
            lastActive = info.tabId;
            focused = info.tabId;
        }
        choose();
    } catch {}
});
chrome.tabs.onRemoved.addListener(id => {
    for (const [frame, entry] of frames) {
        if (entry.tab === id) {
            frames.delete(frame);
            unavailable.delete(frame);
        }
    }
    choose();
});
setInterval(() => transmit({type:'ping'}), 15000);
connect();
