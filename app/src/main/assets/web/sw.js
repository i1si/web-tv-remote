// Minimal service worker: makes the remote installable. It does not cache anything because
// the remote is useless without a live connection to the TV.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {});
