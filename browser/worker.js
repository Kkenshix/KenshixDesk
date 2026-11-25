importScripts('config.js');

const reports = new Map();
let pending = null;
let sent = '';
let busy = false;
let queued = false;
let pauseTab = null;

chrome.storage.session.get(['sent', 'pending', 'pauseTab']).then(state => {
    sent = state.sent ?? '';
    pending = state.pending ?? null;
    pauseTab = state.pauseTab ?? null;
}).finally(check);

function stateChanged() {
    return chrome.storage.session.set({sent, pending, pauseTab});
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
            entries.sort((a,b) => b[1].media.started - a[1].media.started);
            const last = entries[0];
            if (last) {
                const [tab, {media}] = last;
                const tag = [tab, media.site, media.key, media.started].join(':');
                if (tab === foreground) {
                    if (sent) {
                        sent = '';
                        pending = null;
                        await stateChanged();
                    }
                } else if (tag !== sent) {
                    const draft = pending?.tag === tag ? pending : {tag, id: crypto.randomUUID()};
                    pending = draft;
                    const result = await exchange({video: {id: draft.id, site: media.site, key: media.key, seconds: media.seconds}});
                    if (result) {
                        sent = tag;
                        pending = null;
                        if (media.site === 'youtube') pauseTab = {id: draft.id, tab, key: media.key, until: Date.now() + 16000};
                        await stateChanged();
                    }
                }
            }
            if (pauseTab && (Date.now() >= pauseTab.until || pauseTab.tab === foreground)) {
                pauseTab = null;
                await stateChanged();
            }
            if (pauseTab) {
                const current = pauseTab;
                const reply = await exchange({watch: current.id});
                if (reply?.playing) {
                    await chrome.tabs.sendMessage(current.tab, {type: 'phone_playing', key: current.key}).catch(() => {});
                    pauseTab = null;
                    await stateChanged();
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
