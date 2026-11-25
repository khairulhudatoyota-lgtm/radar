// RADAR service worker v4 (25 Nov 2026): ringan di sinyal lemah.
// • Halaman utama: tetap dicek ke server dulu (versi baru langsung terpakai), TAPI kalau server belum menjawab dalam 3,5 detik
//   (sinyal lemah) RADAR langsung dibuka dari salinan terakhir di HP; unduhan versi baru tetap jalan di belakang dan dipakai
//   saat dibuka berikutnya. Pengaman versi lama (appMin) di aplikasi tetap memaksa muat ulang kalau ada build lebih baru.
// • Pustaka dari CDN (Firebase, Excel, 3D, QR, peta, font): disimpan di HP, dipakai dari HP (tidak menunggu internet).
// v3: + klik notifikasi giliran membuka RADAR. v2: halaman utama selalu dicek ke server, cadangan offline.
const C="radar-shell-v4", LIB="radar-lib-v1", KEEP=[C,LIB];
const SHELL=["./","./index.html","./manifest.webmanifest","./icon-192.png","./icon-512.png"];
const LIB_RE=/^https:\/\/(cdnjs\.cloudflare\.com\/ajax\/libs\/|cdn\.jsdelivr\.net\/npm\/|unpkg\.com\/|www\.gstatic\.com\/firebasejs\/|fonts\.googleapis\.com\/|fonts\.gstatic\.com\/)/;
const PAGE_WAIT=3500;

self.addEventListener("install",e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(SHELL.map(u=>new Request(u,{cache:"reload"})))).catch(()=>{}));self.skipWaiting();});
self.addEventListener("activate",e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>!KEEP.includes(x)).map(x=>caches.delete(x)))).then(()=>self.clients.claim()));});

function putPage(res){ if(res&&res.ok){ const cp=res.clone(); caches.open(C).then(c=>c.put("./index.html",cp)).catch(()=>{}); } return res; }

self.addEventListener("fetch",e=>{
  const r=e.request; if(r.method!=="GET") return;
  const u=new URL(r.url);

  // Pustaka CDN berversi: pakai salinan di HP, perbarui diam-diam di belakang.
  if(u.origin!==location.origin){
    if(!LIB_RE.test(r.url)) return;   // Firebase database, peta (tile), dll: langsung ke internet seperti biasa
    e.respondWith(caches.open(LIB).then(c=>c.match(r).then(m=>{
      const net=fetch(r).then(res=>{ if(res && (res.ok || res.type==="opaque")) c.put(r,res.clone()).catch(()=>{}); return res; });
      if(m){ e.waitUntil(net.catch(()=>{})); return m; }
      return net;
    })).catch(()=>fetch(r)));
    return;
  }

  const page=r.mode==="navigate"||/\/(index\.html)?$/.test(u.pathname)||/version\.json$/.test(u.pathname);

  // Membuka RADAR: tunggu server maks. 3,5 detik, lalu pakai salinan di HP.
  if(r.mode==="navigate"){
    const net=fetch(r,{cache:"no-cache"}).then(putPage);
    e.waitUntil(net.catch(()=>{}));
    e.respondWith(new Promise(resolve=>{
      let done=false; const fin=x=>{ if(!done && x){ done=true; resolve(x); } };
      const t=setTimeout(()=>{ caches.match("./index.html").then(m=>fin(m)); }, PAGE_WAIT);
      net.then(res=>{ clearTimeout(t); fin(res); })
         .catch(()=>{ clearTimeout(t); caches.match("./index.html").then(m=>fin(m||Response.error())); });
    }));
    return;
  }

  // Cek versi (index.html / version.json lewat fetch): selalu tanya server, cadangan salinan kalau offline.
  const net=page?fetch(r,{cache:"no-cache"}):fetch(r);
  e.respondWith(net.then(res=>{ if(res&&res.ok){ const cp=res.clone(); caches.open(C).then(c=>c.put(r,cp)).catch(()=>{}); } return res; })
    .catch(()=>caches.match(r).then(m=>m||(page?caches.match("./index.html"):undefined)).then(m=>m||Response.error())));
});

self.addEventListener("notificationclick",e=>{
  e.notification.close();
  e.waitUntil(self.clients.matchAll({type:"window",includeUncontrolled:true}).then(cs=>{ for(const c of cs){ if("focus" in c) return c.focus(); } return self.clients.openWindow ? self.clients.openWindow("./") : null; }));
});
