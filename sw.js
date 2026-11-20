// Keeps a copy of Kruger Checklist on the phone so it opens with no signal.
// When there is signal it fetches the latest version first (and saves that), so updates arrive by themselves.
const CACHE = "kruger-checklist-v3";
const SHARED = "kruger-shared";
const FILES = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const req = e.request;
  // a KML shared to the app (WhatsApp → Share → Kruger): keep it, then open the app, which loads it
  if(req.method === "POST" && new URL(req.url).pathname.endsWith("/share-kml")){
    e.respondWith((async () => {
      try{
        // WhatsApp can send the message text along with (or instead of) the file: pick the file that is actually a KML/KMZ
        const form = await req.formData(), files = form.getAll("kml").filter(x => x && x.size);
        let best = null, bestScore = 0;
        for(const f of files){
          const head = new Uint8Array(await f.slice(0, 64).arrayBuffer());
          const txt = new TextDecoder().decode(head).replace(/^\uFEFF/, "").trimStart();
          let score = 0;
          if(/\.km[lz]$/i.test(f.name || "")) score += 3;
          if(txt.startsWith("<") || (head[0] === 0x50 && head[1] === 0x4b) || head[0] === 0xff || head[0] === 0xfe) score += 2;
          if(/kml|xml|zip/i.test(f.type || "")) score += 1;
          if(score > bestScore){ best = f; bestScore = score; }
        }
        const c = await caches.open(SHARED);
        if(best && bestScore >= 2) await c.put("./shared-kml", new Response(best, {headers: {"x-name": encodeURIComponent(best.name || "shared.kml")}}));
        else await c.put("./shared-kml", new Response("", {headers: {"x-none": "1"}}));
      }catch(err){}
      return Response.redirect("./?shared-kml=1", 303);
    })());
    return;
  }
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

// tapping the LoRa status notification opens the app
self.addEventListener("notificationclick", e => {
  if(e.notification.tag !== "lora-status") e.notification.close();
  e.waitUntil(self.clients.matchAll({type: "window", includeUncontrolled: true}).then(cs => {
    const c = cs[0]; if(c) return c.focus();
    return self.clients.openWindow("./");
  }));
});
