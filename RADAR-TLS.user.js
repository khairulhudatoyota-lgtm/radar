// ==UserScript==
// @name         RADAR -> TLS
// @namespace    radar-eky
// @version      1.0
// @description  Mengisi form Pembuatan ILS/SJKB di TLS dari paket RADAR (satu ketukan).
// @match        https://tls.toyota.astra.co.id/PembuatanSJKB*
// @run-at       document-idle
// @grant        none
// ==/UserScript==
(function () {
  "use strict";
  function radarTlsRun() {
    window.__radarTlsRun = false;
    (async () => {
  if (window.__radarTlsRun) { return; }
  window.__radarTlsRun = true;
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const norm = s => String(s || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  const code = t => String(t || "").split(" - ")[0].trim();

  
  const box = document.createElement("div");
  box.style.cssText = "position:fixed;z-index:2147483647;right:16px;bottom:16px;width:330px;background:#0f2a4a;color:#fff;font:13px/1.45 Arial,sans-serif;border-radius:10px;padding:12px 14px;box-shadow:0 8px 28px rgba(0,0,0,.4);";
  box.innerHTML = '<div style="font-weight:700;letter-spacing:.04em;">RADAR &rarr; TLS</div><div id="rt_msg" style="margin-top:6px;"></div><div style="margin-top:10px;text-align:right;"><button id="rt_x" type="button" style="border:0;border-radius:6px;padding:5px 12px;cursor:pointer;">Tutup</button></div>';
  document.body.appendChild(box);
  const msgEl = box.querySelector("#rt_msg");
  box.querySelector("#rt_x").onclick = () => { box.remove(); window.__radarTlsRun = false; };
  const say = (t, c) => { msgEl.innerHTML = t; msgEl.style.color = c || "#fff"; };
  const fail = m => { say("&#9940; " + m, "#ffb4a8"); window.__radarTlsRun = false; throw new Error("RADAR-TLS: " + m); };

  
  async function getRaw() {
    say("Membaca data dari RADAR...");
    try {
      const t = await Promise.race([navigator.clipboard.readText(), new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), 7000))]);
      if (t && /RADAR-TLS:|"v"\s*:\s*1/.test(t)) return t;
    } catch (e) {}
    return await new Promise(res => {
      msgEl.innerHTML = "Clipboard tidak terbaca. Tempel paket dari RADAR di kotak ini, lalu tekan <b>Jalankan</b>:";
      const ta = document.createElement("textarea");
      ta.rows = 3; ta.style.cssText = "width:100%;margin-top:6px;box-sizing:border-box;font:12px monospace;";
      const go = document.createElement("button");
      go.type = "button"; go.textContent = "Jalankan"; go.style.cssText = "margin-top:6px;border:0;border-radius:6px;padding:6px 14px;cursor:pointer;font-weight:700;";
      msgEl.appendChild(ta); msgEl.appendChild(document.createElement("br")); msgEl.appendChild(go);
      go.onclick = () => { const v = ta.value; msgEl.innerHTML = ""; res(v); };
    });
  }
  const raw = await getRaw();
  let P;
  try { P = JSON.parse(String(raw).replace(/^\s*RADAR-TLS:/, "")); }
  catch (e) { fail("Isi yang ditempel bukan paket RADAR. Di RADAR tekan 'Salin paket untuk script TLS' dulu."); }
  if (!P || P.v !== 1 || !Array.isArray(P.frames) || !P.frames.length) fail("Paket RADAR tidak valid. Salin ulang dari RADAR.");
  if (P.frames.length > 6) fail("Maksimal 6 unit per ILS (ILS ini " + P.frames.length + ").");
  if (P.moda === "SC" && P.frames.length !== 1) fail("Moda SC hanya untuk 1 unit.");
  if (!P.vendor || !P.plat) fail("Vendor atau No. Polisi kosong di paket RADAR.");
  if (!document.querySelector('form[name="PembuatanSJKBForm"]')) fail("Buka halaman Pembuatan ILS / SJKB di TLS dulu.");

  
  const visible = el => !!(el && el.offsetParent !== null);
  async function waitFor(fn, ms) {
    const t = Date.now();
    while (Date.now() - t < ms) { let v = null; try { v = fn(); } catch (e) {} if (v) return v; await sleep(120); }
    return null;
  }
  const setVal = (inp, v) => {
    const d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value");
    d.set.call(inp, v);
    inp.dispatchEvent(new Event("input", { bubbles: true }));
    inp.dispatchEvent(new Event("change", { bubbles: true }));
  };
  const key = (el, name, k, kc) => {
    const ev = new KeyboardEvent(name, { key: k, code: k, bubbles: true, cancelable: true });
    Object.defineProperty(ev, "keyCode", { get: () => kc });
    Object.defineProperty(ev, "which", { get: () => kc });
    el.dispatchEvent(ev);
  };
  const byModel = m => [...document.querySelectorAll(".ui-select-container")].find(c => c.getAttribute("ng-model") === m);
  const chosenText = c => {
    const t = c.querySelector(".ui-select-match-text");
    return t && !t.classList.contains("ng-hide") ? t.textContent.trim() : "";
  };

  
  async function pick(model, label, query, match) {
    say("Mengisi " + label + "...");
    const c = await waitFor(() => { const x = byModel(model); return x && !x.hasAttribute("disabled") ? x : null; }, 12000);
    if (!c) fail(label + ": kotak belum aktif (isian sebelumnya belum selesai).");
    const tog = c.querySelector(".ui-select-toggle");
    if (!tog) fail(label + ": struktur halaman berbeda dari yang diharapkan.");
    tog.click();
    const inp = await waitFor(() => { const i = c.querySelector("input.ui-select-search"); return visible(i) ? i : null; }, 4000);
    if (!inp) fail(label + ": kotak cari tidak terbuka.");
    inp.focus();
    setVal(inp, query);
    let rows = [];
    await waitFor(() => { rows = [...c.querySelectorAll(".ui-select-choices-row")].filter(visible); return rows.length; }, 6000);
    const hit = rows.find(r => match(r.textContent.trim()));
    if (!hit) { key(inp, "keydown", "Escape", 27); fail(label + " '" + query + "' tidak ditemukan di TLS."); }
    hit.click();
    const ok = await waitFor(() => chosenText(c), 5000);
    if (!ok) fail(label + ": pilihan tidak tersimpan.");
    await sleep(350);
    return ok;
  }

  try {
    
    await pick("me.pembuatanSjkbViewModel.vendor", "Vendor", P.vendor, t => norm(code(t)) === norm(P.vendor));
    
    await pick("me.pembuatanSjkbViewModel.moda", "Moda", P.moda || "CC", t => norm(code(t)) === norm(P.moda || "CC"));
    
    await pick("me.pembuatanSjkbViewModel.modaChild", "Tipe Pengiriman", P.tipe || "CCUT", t => norm(code(t)) === norm(P.tipe || "CCUT"));
    
    await pick("me.pembuatanSjkbViewModel.noPolisi", "No. Polisi", P.plat, t => {
      const a = norm(t), b = norm(P.plat);
      return a === b || (a.indexOf(b) === 0 && a.slice(b.length).indexOf("BLOKIR") === 0);
    });
    
    const dm = byModel("me.pembuatanSjkbViewModel.driver");
    await waitFor(() => dm && chosenText(dm), 1800);
    const cur = dm ? chosenText(dm) : "";
    if (P.driver) {
      if (!(cur && norm(cur).indexOf(norm(P.driver)) >= 0)) {
        await pick("me.pembuatanSjkbViewModel.driver", "Driver", P.driver, t => norm(t).indexOf(norm(P.driver)) >= 0);
      }
    } else if (!cur) fail("Driver kosong di data RADAR (jadwal DCCP) dan belum terisi di TLS.");
    
    say("Memilih Lokasi Tujuan...");
    const radio = document.querySelector('input[type="radio"][name="sjkbMethod"][value="Tujuan"]');
    if (!radio) fail("Pilihan 'Lokasi Tujuan' tidak ditemukan di halaman.");
    radio.click();
    await sleep(400);
    await pick("me.pembuatanSjkbViewModel.location", "Tujuan", P.tujuan || "NVDCKRW", t => norm(code(t)) === norm(P.tujuan || "NVDCKRW"));

    
    const frames = () => [...document.querySelectorAll('input[name="frameNumber"]')].filter(visible);
    if (frames().some(i => i.value)) fail("Tabel No. Rangka sudah berisi. Tekan Batal di TLS, lalu ulangi.");
    for (let i = 0; i < P.frames.length; i++) {
      const fno = String(P.frames[i]).trim().toUpperCase();
      say("No. Rangka " + (i + 1) + " / " + P.frames.length + ": " + fno);
      const inp = await waitFor(() => frames().find(x => !x.disabled && !x.value), 9000);
      if (!inp) fail("Kotak No. Rangka berikutnya tidak tersedia (baris ke-" + (i + 1) + ").");
      inp.focus();
      setVal(inp, fno);
      await sleep(150);
      key(inp, "keydown", "Enter", 13);
      const tr = inp.closest("tr");
      const done = await waitFor(() => {
        const st = tr && tr.querySelector('input[name="stallName"]');
        return st && st.value ? st.value : null;
      }, 12000);
      if (!done) fail("No. Rangka " + fno + " tidak ditemukan atau ditolak TLS.");
      await sleep(250);
    }

    
    say("Menekan Simpan...");
    const save = await waitFor(() => [...document.querySelectorAll("button")].find(b => /^\s*Simpan\s*$/.test(b.textContent) && !b.disabled && visible(b)), 8000);
    if (!save) fail("Tombol Simpan belum aktif. Periksa isian yang masih merah di TLS.");
    save.click();
    say("&#9989; Terisi " + P.frames.length + " unit. Periksa kotak Konfirmasi TLS, lalu klik <b>YA</b>.", "#b6f2c6");
    window.__radarTlsRun = false;
  } catch (e) {
    window.__radarTlsRun = false;
    if (!/^Error: RADAR-TLS/.test(String(e))) { say("&#9940; Terjadi kesalahan: " + (e && e.message ? e.message : e), "#ffb4a8"); }
  }
})();
  }
  if (document.getElementById("radar_tls_fab")) return;
  var b = document.createElement("button");
  b.id = "radar_tls_fab"; b.type = "button"; b.textContent = "\u26A1 RADAR\u2192TLS";
  b.style.cssText = "position:fixed;z-index:2147483646;left:14px;bottom:14px;background:#0f2a4a;color:#fff;border:0;border-radius:24px;padding:12px 18px;font:700 14px Arial,sans-serif;box-shadow:0 6px 18px rgba(0,0,0,.35);";
  b.onclick = function () { radarTlsRun(); };
  document.body.appendChild(b);
})();
