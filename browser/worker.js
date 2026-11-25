importScripts('config.js');

const tabs = new Map();
let socket;
let ready = false;
let current;
let dismissed;
let focused;
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
        if (reply.type === 'ready') { ready = true; refresh(); }
        if (reply.type === 'volume' && current) {
            current.port.postMessage({...reply,id:current.id,key:current.key});
        }
        if (reply.type === 'stop' && current) {
            dismissed = current.tab + ':' + current.key;
            stop();
        }
    };
    candidate.onclose = () => {
        if (socket !== candidate) return;
        ready = false;
        stop();
    };
}

function transmit(message) {
    if (ready && socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

function stop() {
    const previous = current;
    if (!previous) return;
    current = null;
    transmit({type:'end'});
    try { previous.port.postMessage({type:'stop', id:previous.id}); } catch {}
}

function choose() {
    if (!ready || focused === undefined) return;
    if (current) {
        const data = tabs.get(current.tab);
        if (current.tab === focused || data?.media?.key !== current.key || data.port !== current.port) {
            stop();
        }
    }
    if (focused !== null && dismissed?.startsWith(String(focused) + ':')) dismissed = undefined;
    const newest = [...tabs.entries()]
        .filter(([,entry]) => entry.media?.playing)
        .sort((a,b) => b[1].media.started - a[1].media.started)[0];
    if (!newest) return;
    const [tab, entry] = newest;
    if (tab === focused || dismissed === tab + ':' + entry.media.key ||
        (current?.tab === tab && current.key === entry.media.key)) return;
    stop();
    current = {tab, port:entry.port, key:entry.media.key, id:crypto.randomUUID(), begun:false};
    current.port.postMessage({type:'start', id:current.id, key:current.key});
}

async function refresh(windowId) {
    const revision = ++serial;
    if (windowId === chrome.windows.WINDOW_ID_NONE) {
        focused = null;
        choose();
        return;
    }
    try {
        const window = await chrome.windows.getLastFocused({populate:true});
        if (revision !== serial) return;
        focused = window.focused && window.state !== 'minimized' ? window.tabs?.find(tab => tab.active)?.id ?? null : null;
        choose();
    } catch {}
}

function sendFrame(message) {
    if (socket?.bufferedAmount > 128 * 1024 || message.data.length > 350000) {
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
    if (port.name !== 'video' || !tab || tab.incognito || port.sender.frameId !== 0) return;
    const id = tab.id;
    tabs.set(id,{port,media:null});
    connect();
    void refresh();
    port.onMessage.addListener(message => {
        const entry = tabs.get(id);
        if (entry?.port !== port) return;
        if (message.type === 'sample') {
            const previous = entry.media;
            entry.media = message.media;
            if (current?.port === port && current.begun && entry.media?.key === current.key) {
                if (previous?.playing !== entry.media.playing) transmit({type:'paused',value:!entry.media.playing});
                if (previous?.volume !== entry.media.volume) transmit({type:'volume',percent:entry.media.volume});
            }
            connect();
            choose();
        } else if (current?.port === port && current.id === message.id) {
            if (message.type === 'begin' && entry.media) {
                current.begun = true;
                current.needsKey = true;
                transmit({type:'begin',video:{id:current.id,site:entry.media.site,volume:entry.media.volume,
                    width:message.width,height:message.height}});
            } else if (message.type === 'frame' && current.begun && ready) {
                sendFrame(message);
            } else if (message.type === 'unavailable') {
                dismissed = current.tab + ':' + current.key;
                stop();
                transmit({type:'unavailable'});
            }
        }
    });
    port.onDisconnect.addListener(() => {
        if (tabs.get(id)?.port === port) tabs.delete(id);
        choose();
    });
});

chrome.windows.onFocusChanged.addListener(refresh);
chrome.tabs.onActivated.addListener(async info => {
    const revision = ++serial;
    try {
        const window = await chrome.windows.get(info.windowId);
        if (revision !== serial) return;
        if (window.focused) focused = info.tabId;
        choose();
    } catch {}
});
chrome.tabs.onRemoved.addListener(id => { tabs.delete(id); choose(); });
setInterval(() => transmit({type:'ping'}), 15000);
connect();
