const pick = () => [...document.querySelectorAll('video')]
    .filter(video => video.readyState >= 2 && !video.ended && video.videoWidth > 0)
    .sort((a, b) => (b.videoWidth * b.videoHeight) - (a.videoWidth * a.videoHeight))[0];

function source() {
    const url = new URL(location.href);
    if (url.hostname === 'www.youtube.com') {
        const key = url.searchParams.get('v') || url.pathname.match(/^\/(?:shorts|live)\/([^/]+)\/?$/)?.[1];
        if (/^[\w-]{11}$/.test(key ?? '') && !document.querySelector('.ad-showing')) {
            return {site: 'youtube', key};
        }
    } else {
        const key = url.pathname.match(/^\/([a-z0-9_-]{2,50})\/?$/i)?.[1]?.toLowerCase();
        if (key) return {site: 'kick', key};
    }
    return null;
}

let previous = '';
let started = 0;
function sample() {
    const video = pick();
    const media = source();
    const playing = Boolean(video && media && !video.paused);
    const identity = video && media ? media.site + ':' + media.key : '';
    if (identity !== previous) started = 0;
    if (playing && started === 0) started = Date.now();
    previous = identity;
    chrome.runtime.sendMessage({
        type: 'sample',
        media: identity ? {
            ...media, started, playing,
            position_ms: Math.max(0, Math.min(86400000, Math.round(video.currentTime * 1000) || 0)),
            volume: video.muted ? 0 : Math.round(video.volume * 100)
        } : null
    }).catch(() => {});
}

document.addEventListener('playing', sample, true);
document.addEventListener('pause', sample, true);
document.addEventListener('ended', sample, true);
document.addEventListener('volumechange', sample, true);
document.addEventListener('visibilitychange', sample);
window.setInterval(sample, 900);
sample();

chrome.runtime.onMessage.addListener(message => {
    if (message.type !== 'video_volume' || !Number.isInteger(message.volume) ||
        message.volume < 0 || message.volume > 100) return;
    const media = source();
    const video = pick();
    if (video && media?.site === message.site && media.key === message.key) {
        video.volume = message.volume / 100;
        if (message.volume > 0) video.muted = false;
        sample();
    }
});
