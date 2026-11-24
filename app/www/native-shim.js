// Kruger app (Android): makes Capacitor's native plugins look like the web APIs the checklist
// already uses, so the same page runs in Chrome and in the app.
//   navigator.bluetooth  -> the BLE plugin (just what the Meshtastic code needs)
//   navigator.wakeLock   -> keep the screen on
//   navigator.share      -> Android's share sheet, with files
//   download links       -> the share sheet (save to Files, Drive, WhatsApp...)
//   navigator.clipboard  -> Android's clipboard
(function(){
  "use strict";
  const C = window.Capacitor;
  if(!C || !C.isNativePlatform || !C.isNativePlatform()) return;
  window.KC_NATIVE = true;
  window.KC_FG = true;
  const Kc = window.KcNative = C.Plugins.KcNative;
  const Ble = window.capacitorCommunityBluetoothLe && window.capacitorCommunityBluetoothLe.BleClient;

  // the app in front or not (Android doesn't always tell the page)
  Kc.addListener("appState", s => { window.KC_FG = !!s.active; });

  const b64 = blob => new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(String(r.result).split(",")[1] || "");
    r.onerror = () => rej(r.error);
    r.readAsDataURL(blob);
  });
  window.KC_b64 = b64;

  class Evt {
    constructor(){ this._l = {}; }
    addEventListener(t, f){ (this._l[t] = this._l[t] || []).push(f); }
    removeEventListener(t, f){ this._l[t] = (this._l[t] || []).filter(x => x !== f); }
    _emit(t, e){ (this._l[t] || []).slice().forEach(f => { try{ f(e || {type: t, target: this}); }catch(err){ console.error(err); } }); }
  }

  /* ---------- Web Bluetooth ---------- */
  if(Ble){
    let ready = null;
    const init = () => ready || (ready = Ble.initialize({androidNeverForLocation: true}).catch(e => { ready = null; throw e; }));
    const toDV = d => d instanceof DataView ? d
      : ArrayBuffer.isView(d) ? new DataView(d.buffer.slice(d.byteOffset, d.byteOffset + d.byteLength))
      : new DataView(d);
    class Char extends Evt {
      constructor(dev, svc, uuid){ super(); this.service = {uuid: svc, device: dev}; this._d = dev; this._s = svc; this.uuid = uuid; this.value = null; }
      async readValue(){ const v = await Ble.read(this._d.id, this._s, this.uuid); this.value = v; return v; }
      async writeValue(b){ await Ble.write(this._d.id, this._s, this.uuid, toDV(b)); }
      writeValueWithResponse(b){ return this.writeValue(b); }
      async startNotifications(){
        await Ble.startNotifications(this._d.id, this._s, this.uuid, v => { this.value = v; this._emit("characteristicvaluechanged", {type: "characteristicvaluechanged", target: this}); });
        return this;
      }
      async stopNotifications(){ try{ await Ble.stopNotifications(this._d.id, this._s, this.uuid); }catch(e){} return this; }
    }
    class Svc {
      constructor(dev, uuid){ this.device = dev; this.uuid = uuid; }
      async getCharacteristic(u){ return new Char(this.device, this.uuid, String(u).toLowerCase()); }
    }
    class Gatt {
      constructor(dev){ this.device = dev; this.connected = false; }
      async connect(){
        await init();
        await Ble.connect(this.device.id, () => { this.connected = false; this.device._emit("gattserverdisconnected"); }, {timeout: 12000});
        this.connected = true;
        return this;
      }
      disconnect(){ this.connected = false; Ble.disconnect(this.device.id).catch(() => {}); }
      async getPrimaryService(u){ return new Svc(this.device, String(u).toLowerCase()); }
    }
    class Dev extends Evt {
      constructor(id, name){ super(); this.id = id; this.name = name || ""; this.gatt = new Gatt(this); }
    }
    const devs = {};
    const devOf = (id, name) => devs[id] || (devs[id] = new Dev(id, name));
    const KEY = "flightDayLog.bleDevs";
    const remembered = () => { try{ return JSON.parse(localStorage.getItem(KEY) || "{}") || {}; }catch(e){ return {}; } };
    const bt = {
      async requestDevice(opts){
        try{ await init(); }
        catch(e){ const err = new Error((e && e.message) || "Bluetooth off"); err.name = "NetworkError"; throw err; }
        const services = [];
        ((opts && opts.filters) || []).forEach(f => (f.services || []).forEach(s => services.push(String(s).toLowerCase())));
        let d;
        try{ d = await Ble.requestDevice({services}); }
        catch(e){
          const err = new Error((e && e.message) || "cancelled");
          err.name = /cancel|no device/i.test(err.message) ? "NotFoundError" : "NetworkError";
          throw err;
        }
        const r = remembered(); r[d.deviceId] = d.name || "";
        try{ localStorage.setItem(KEY, JSON.stringify(r)); }catch(e){}
        return devOf(d.deviceId, d.name);
      },
      async getDevices(){ return Object.entries(remembered()).map(([id, n]) => devOf(id, n)); },
      async getAvailability(){ return true; }
    };
    try{ Object.defineProperty(navigator, "bluetooth", {configurable: true, value: bt}); }catch(e){ console.warn("no bluetooth shim", e); }
  }

  /* ---------- keep the screen on ---------- */
  let awake = 0;
  const wl = {
    async request(){
      awake++; await Kc.keepAwake({on: true});
      const s = new Evt(); s.released = false; s.type = "screen";
      s.release = async () => {
        if(s.released) return;
        s.released = true; awake = Math.max(0, awake - 1);
        if(!awake) await Kc.keepAwake({on: false});
        s._emit("release");
      };
      return s;
    }
  };
  try{ Object.defineProperty(navigator, "wakeLock", {configurable: true, value: wl}); }catch(e){}

  /* ---------- share sheet, with files ---------- */
  const share = async d => {
    d = d || {};
    const files = await Promise.all((d.files || []).map(async f => ({name: f.name || "file", mime: f.type || "application/octet-stream", base64: await b64(f)})));
    const r = await Kc.share({text: d.text || "", title: d.title || "", files});
    if(r && r.cancelled){ const e = new Error("Share cancelled"); e.name = "AbortError"; throw e; }
  };
  try{
    Object.defineProperty(navigator, "share", {configurable: true, value: share});
    Object.defineProperty(navigator, "canShare", {configurable: true, value: () => true});
  }catch(e){}

  /* ---------- downloads go to the share sheet (save to Files / Drive) ---------- */
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){
    if(this.download && /^(blob|data):/.test(this.href)){
      const name = this.download;
      fetch(this.href).then(r => r.blob()).then(async blob => {
        await Kc.share({text: "", title: name, files: [{name, mime: blob.type || "application/octet-stream", base64: await b64(blob)}]});
      }).catch(e => console.warn("save failed", e));
      return;
    }
    return click.call(this);
  };

  /* ---------- clipboard ---------- */
  try{
    Object.defineProperty(navigator, "clipboard", {configurable: true, value: {
      writeText: t => Kc.copy({text: String(t)}),
      readText: () => Kc.paste().then(r => (r && r.text) || "")
    }});
  }catch(e){}
})();
