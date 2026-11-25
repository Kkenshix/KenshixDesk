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
    const identity = playing ? media.site + ':' + media.key : '';
    if (identity !== previous && playing) started = Date.now();
    previous = identity;
    chrome.runtime.sendMessage({
        type: 'sample',
        media: playing ? {...media, seconds: Math.max(0, Math.min(86400, Math.floor(video.currentTime) || 0)), started} : null
    }).catch(() => {});
}

document.addEventListener('playing', sample, true);
document.addEventListener('pause', sample, true);
document.addEventListener('ended', sample, true);
document.addEventListener('visibilitychange', sample);
window.setInterval(sample, 900);
sample();

chrome.runtime.onMessage.addListener(message => {
    if (message.type !== 'phone_playing') return;
    const media = source();
    if (media?.site === 'youtube' && media.key === message.key) pick()?.pause();
});
