// RADAR service worker v3: + klik notifikasi giliran membuka RADAR.
// RADAR service worker v2: halaman utama SELALU dicek ke server (tidak pakai cache browser), cadangan offline.
// v1 memakai cache HTTP browser (GitHub Pages: 10 menit), sehingga setelah upload kadang tampil versi baru lalu kembali ke versi lama.
const C="radar-shell-v2", SHELL=["./","./index.html","./manifest.webmanifest","./icon-192.png","./icon-512.png"];
self.addEventListener("install",e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(SHELL.map(u=>new Request(u,{cache:"reload"})))).catch(()=>{}));self.skipWaiting();});
self.addEventListener("activate",e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>self.clients.claim()));});
self.addEventListener("fetch",e=>{
  const r=e.request; if(r.method!=="GET"||new URL(r.url).origin!==location.origin) return;
  const u=new URL(r.url), page=r.mode==="navigate"||/\/(index\.html)?$/.test(u.pathname)||/version\.json$/.test(u.pathname);
  const net=page?fetch(r,{cache:"no-cache"}):fetch(r);   // halaman: selalu tanya server (ETag, ringan); file lain: normal
  e.respondWith(net.then(res=>{ if(res&&res.ok){ const cp=res.clone(); caches.open(C).then(c=>c.put(page&&r.mode==="navigate"?"./index.html":r,cp)); } return res; })
    .catch(()=>caches.match(r).then(m=>m||caches.match("./index.html"))));
});

self.addEventListener("notificationclick",e=>{
  e.notification.close();
  e.waitUntil(self.clients.matchAll({type:"window",includeUncontrolled:true}).then(cs=>{ for(const c of cs){ if("focus" in c) return c.focus(); } return self.clients.openWindow ? self.clients.openWindow("./") : null; }));
});
