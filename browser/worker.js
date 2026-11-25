importScripts('config.js');

const reports = new Map();
let pending = null;
let sent = '';
let busy = false;
let queued = false;
let activeVideo = null;

chrome.storage.session.get(['sent', 'pending', 'activeVideo']).then(state => {
    sent = state.sent ?? '';
    pending = state.pending ?? null;
    activeVideo = state.activeVideo ?? null;
}).finally(check);

function stateChanged() {
    return chrome.storage.session.set({sent, pending, activeVideo});
}

function check() {
    queued = true;
    if (!busy) void work();
}

async function exchange(packet) {
    try {
        const response = await fetch('http://127.0.0.1:43852/handoff', {
            method: 'POST', headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + bridgeKey},
            body: JSON.stringify(packet), signal: AbortSignal.timeout(1500)
        });
        return response.ok ? await response.json() : null;
    } catch {
        return null;
    }
}

async function work() {
    busy = true;
    try {
        while (queued) {
            queued = false;
            const window = await chrome.windows.getLastFocused({populate: true});
            const foreground = window.focused && window.state !== 'minimized' ? window.tabs?.find(tab => tab.active)?.id : null;
            const entries = [...reports.entries()].filter(([, value]) => Date.now() - value.updated < 4500);
            const playing = entries.filter(([, value]) => value.media.playing);
            playing.sort((a,b) => b[1].media.started - a[1].media.started);
            const last = playing[0];
            if (last) {
                const [tab, {media}] = last;
                const tag = [tab, media.site, media.key, media.started].join(':');
                if (tab === foreground) {
                    if (sent) {
                        sent = '';
                        pending = null;
                        await stateChanged();
                    }
                } else if (tag !== sent && !(activeVideo?.tab === tab && activeVideo.key === media.key &&
                    activeVideo.site === media.site)) {
                    const draft = pending?.tag === tag ? pending : {tag, id: crypto.randomUUID()};
                    pending = draft;
                    const result = await exchange({video: {
                        id: draft.id, site: media.site, key: media.key, seconds: Math.floor(media.position_ms / 1000),
                        position_ms: media.position_ms, volume: media.volume, playing: true
                    }});
                    if (result) {
                        sent = tag;
                        pending = null;
                        activeVideo = {id: draft.id, tab, key: media.key, site: media.site};
                        await stateChanged();
                    }
                }
            }
            if (activeVideo) {
                const current = activeVideo;
                const report = reports.get(current.tab);
                const media = report?.media;
                if (media?.key === current.key && media.site === current.site && Date.now() - report.updated < 4500) {
                    const reply = await exchange({watch: {
                        id: current.id, position_ms: media.position_ms, volume: media.volume, playing: media.playing
                    }});
                    if (reply?.open === false) {
                        activeVideo = null;
                        await stateChanged();
                    } else if (Number.isInteger(reply?.desired_volume)) {
                        await chrome.tabs.sendMessage(current.tab, {
                            type: 'video_volume', key: current.key, site: current.site, volume: reply.desired_volume
                        }).catch(() => {});
                    }
                }
            }
        }
    } catch {
        // During browser startup there may not be an active window yet. A later sample retries.
    } finally {
        busy = false;
    }
}

chrome.runtime.onMessage.addListener((message, sender) => {
    const tab = sender.tab?.id;
    if (message.type !== 'sample' || tab === undefined || sender.frameId !== 0 || sender.tab.incognito) return;
    const old = reports.get(tab);
    if (message.media) {
        if (old?.media.site === message.media.site && old.media.key === message.media.key) {
            message.media.started = Math.max(old.media.started, message.media.started);
        }
        reports.set(tab, {media: message.media, updated: Date.now()});
    } else reports.delete(tab);
    check();
});
chrome.windows.onFocusChanged.addListener(check);
chrome.tabs.onActivated.addListener(check);
chrome.tabs.onRemoved.addListener(tab => { reports.delete(tab); check(); });
