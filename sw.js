// Keeps a copy of Kruger Checklist on the phone so it opens with no signal.
// When there is signal it fetches the latest version first (and saves that), so updates arrive by themselves.
const CACHE = "kruger-checklist-v1";
const FILES = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const req = e.request;
  if(req.method !== "GET" || new URL(req.url).origin !== location.origin) return;
  if(req.mode === "navigate" || req.destination === "document"){
    // the page: newest from the network if it answers within 4 seconds, otherwise the saved copy
    e.respondWith((async () => {
      const cache = await caches.open(CACHE);
      try{
        const res = await Promise.race([fetch(req, {cache: "no-store"}), new Promise((_, rej) => setTimeout(() => rej(new Error("slow")), 4000))]);
        if(res && res.ok){ cache.put("./index.html", res.clone()); return res; }
        throw new Error("bad response");
      }catch(err){
        return (await cache.match("./index.html")) || (await cache.match("./")) || Response.error();
      }
    })());
    return;
  }
  // icons and the manifest: saved copy first
  e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => {
    if(res.ok){ const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); }
    return res;
  })));
});
