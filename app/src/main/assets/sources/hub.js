/**
 * Alya Music — Hub sumber data (Paket 40)
 *
 * Penghubung semua modul di /sources/. Tiap modul berdiri sendiri (satu berkas, satu sumber) dan hanya
 * "mendaftar" ke hub ini; hub yang menggabungkan hasilnya. Tambah sumber baru = tambah berkas baru +
 * satu nama di MODS di bawah, tanpa menyentuh modul lain.
 *
 * Akses jaringan SELALU lewat Hub.http -> rute /api/proxy di AlyaServer (ada daftar host yang diizinkan),
 * karena WebView tidak boleh memanggil situs luar langsung (CORS) dan tidak bisa mengatur User-Agent.
 *
 * Bentuk "track" seragam yang dikembalikan modul:
 *   { src, id, title, artist, album, duration, cover, url, preview, alyaId? }
 */
(function (root) {
  'use strict';

  var MODS = ['lyricfind', 'lirikonline', 'applemusic', 'shazam', 'soundcloud'];

  var Hub = root.AlyaSrc = root.AlyaSrc || {};
  var reg = {};
  var cache = {};

  Hub.base = '';
  Hub.failed = [];

  // ---------- registrasi ----------
  Hub.register = function (m) {
    if (!m || !/^[a-z0-9_]+$/.test(m.id || '')) throw new Error('Modul tidak valid');
    if (Hub[m.id] && !reg[m.id]) throw new Error('Nama modul bentrok dengan hub: ' + m.id);
    m.caps = m.caps || [];
    m.types = m.types || ['track'];
    reg[m.id] = m;
    Hub[m.id] = m;
    return m;
  };
  Hub.get = function (id) { return reg[id] || null; };
  Hub.list = function (cap) {
    return Object.keys(reg).map(function (k) { return reg[k]; })
      .filter(function (m) { return !cap || m.caps.indexOf(cap) >= 0; });
  };

  // ---------- util bersama ----------
  Hub.qs = function (o) {
    return Object.keys(o).filter(function (k) { return o[k] != null && o[k] !== ''; })
      .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(o[k]); }).join('&');
  };
  Hub.fmtDur = function (ms) {
    var s = Math.round((Number(ms) || 0) / 1000);
    if (s <= 0) return '';
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return h > 0 ? h + ':' + (m < 10 ? '0' : '') + m + ':' + (x < 10 ? '0' : '') + x
      : m + ':' + (x < 10 ? '0' : '') + x;
  };
  Hub.hiRes = function (url, size) {
    if (!url || typeof url !== 'string') return '';
    size = size || 600;
    return url.replace(/\{w\}x\{h\}bb\.\{f\}/i, size + 'x' + size + 'bb.jpg')
      .replace(/\/\d+x\d+bb/i, '/' + size + 'x' + size + 'bb')
      .replace(/\/\d+x\d+w(?=\.|\/|$)/i, '/' + size + 'x' + size + 'w');
  };
  Hub.norm = function (s) {
    return String(s || '').toLowerCase().replace(/[\(\[][^\)\]]*[\)\]]/g, ' ').replace(/[^a-z0-9]+/g, ' ').trim();
  };
  Hub.decode = function (s) {
    if (typeof document === 'undefined') return String(s || '');
    var t = document.createElement('textarea');
    t.innerHTML = String(s || '');
    return t.value;
  };
  function settle(list) {
    return Promise.all(list.map(function (p) {
      return p.then(function (v) { return { ok: true, v: v }; }, function (e) { return { ok: false, e: e }; });
    }));
  }
  function msg(e) { return String((e && e.message) || e); }

  // ---------- http lewat proxy lokal ----------
  // opt: { json:true, ttl:ms (default 5 menit, 0 = tanpa cache), timeout:ms (default 20 dtk) }
  Hub.http = function (url, opt) {
    opt = opt || {};
    var ttl = opt.ttl == null ? 300000 : opt.ttl;
    var c = cache[url];
    var p;
    if (ttl > 0 && c && Date.now() - c.t < ttl) {
      p = Promise.resolve(c.v);
    } else {
      var ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
      var tm = ctl ? setTimeout(function () { ctl.abort(); }, opt.timeout || 20000) : 0;
      p = fetch(Hub.base + '/api/proxy?url=' + encodeURIComponent(url), ctl ? { signal: ctl.signal } : undefined)
        .then(function (r) {
          return r.text().then(function (t) {
            if (!r.ok) {
              var m = 'HTTP ' + r.status;
              try { m = JSON.parse(t).error || m; } catch (x) { }
              var e = new Error(m); e.status = r.status; throw e;
            }
            return t;
          });
        })
        .then(function (t) {
          clearTimeout(tm);
          if (ttl > 0) {
            if (Object.keys(cache).length > 60) cache = {};
            cache[url] = { t: Date.now(), v: t };
          }
          return t;
        }, function (e) { clearTimeout(tm); throw e; });
    }
    return p.then(function (t) {
      if (!opt.json) return t;
      try { return JSON.parse(t); } catch (x) { throw new Error('Respons bukan JSON'); }
    });
  };

  // ---------- pencarian gabungan ----------
  // opt: { sources:['applemusic',...], type:'track'|'album'|'artist'|'user'|'playlist', limit }
  // Hasil: { query, results:[track...], errors:{idModul:pesan} } — satu modul gagal tidak menggagalkan yang lain.
  Hub.search = function (q, opt) {
    opt = opt || {};
    var type = opt.type || 'track';
    var ids = opt.sources || Hub.list('search').map(function (m) { return m.id; });
    var mods = ids.map(Hub.get).filter(function (m) { return m && m.search && m.types.indexOf(type) >= 0; });
    return settle(mods.map(function (m) {
      return Promise.resolve().then(function () { return m.search(q, { type: type, limit: opt.limit }); });
    })).then(function (rs) {
      var out = { query: q, results: [], errors: {} };
      rs.forEach(function (r, i) {
        if (r.ok) (r.v.results || []).forEach(function (x) { out.results.push(x); });
        else out.errors[mods[i].id] = msg(r.e);
      });
      return out;
    });
  };

  // ---------- tangga lagu / chart ----------
  // opt: { sources, country, type }  -> { sources:{id:[...]}, errors:{} }
  Hub.charts = function (opt) {
    opt = opt || {};
    var ids = opt.sources || Hub.list('charts').map(function (m) { return m.id; });
    var mods = ids.map(Hub.get).filter(function (m) { return m && m.charts; });
    return settle(mods.map(function (m) {
      return Promise.resolve().then(function () { return m.charts(opt); });
    })).then(function (rs) {
      var out = { sources: {}, errors: {} };
      rs.forEach(function (r, i) {
        if (r.ok) out.sources[mods[i].id] = r.v.results || [];
        else out.errors[mods[i].id] = msg(r.e);
      });
      return out;
    });
  };

  // ---------- lirik berantai ----------
  // Coba tiap modul ber-cap 'lyrics' berurutan; yang pertama berhasil dipakai.
  // Hasil: { src, title, text, url }.  Gagal semua -> Error berisi alasan tiap modul.
  Hub.lyrics = function (title, artist, opt) {
    opt = opt || {};
    var ids = opt.sources || Hub.list('lyrics').map(function (m) { return m.id; });
    var why = [];
    var chain = Promise.reject(new Error('mulai'));
    ids.forEach(function (id) {
      var m = Hub.get(id);
      if (!m || !m.lyrics) return;
      chain = chain.catch(function (e) {
        if (e && e.message !== 'mulai') why.push(msg(e));
        return m.lyrics(title, artist);
      });
    });
    return chain.catch(function (e) {
      if (e && e.message !== 'mulai') why.push(msg(e));
      throw new Error('Lirik tidak ditemukan' + (why.length ? ' (' + why.join('; ') + ')' : ''));
    });
  };

  // ---------- sambungan ke pemutar Alya ----------
  // Sebagian besar sumber di sini cuma memberi METADATA (judul, sampul, preview 30 detik). Supaya tetap bisa
  // diputar penuh, cari padanannya lewat /api/search (YouTube) dan kembalikan track format Alya:
  //   { id, title, author, duration, thumb, source }
  // Lagu SoundCloud yang sudah punya alyaId langsung dipakai tanpa mencari.
  Hub.toPlayable = function (t) {
    if (!t) return Promise.resolve(null);
    if (t.alyaId) {
      return Promise.resolve({ id: t.alyaId, title: t.title, author: t.artist || '', duration: t.duration || '', thumb: t.cover || '', source: 'soundcloud' });
    }
    var q = [t.artist, t.title].filter(Boolean).join(' ').trim();
    if (!q) return Promise.resolve(null);
    return fetch(Hub.base + '/api/search?q=' + encodeURIComponent(q)).then(function (r) {
      if (!r.ok) throw new Error('Pencarian gagal');
      return r.json();
    }).then(function (a) {
      a = Array.isArray(a) ? a : [];
      var want = Hub.norm(t.title);
      var hit = null;
      for (var i = 0; i < a.length && !hit; i++) {
        if (want && Hub.norm(a[i].title).indexOf(want) >= 0) hit = a[i];
      }
      return hit || a[0] || null;
    });
  };

  // ---------- pemuat modul ----------
  // Modul dimuat berurutan (async=false) dari /sources/<nama>.js. Satu modul gagal dimuat tidak menghentikan yang lain.
  Hub.ready = new Promise(function (resolve) {
    if (typeof document === 'undefined') { resolve(); return; }
    var left = MODS.length;
    MODS.forEach(function (n) {
      var s = document.createElement('script');
      s.src = '/sources/' + n + '.js';
      s.async = false;
      s.onload = function () { if (--left === 0) resolve(); };
      s.onerror = function () { Hub.failed.push(n); if (--left === 0) resolve(); };
      document.head.appendChild(s);
    });
  });
})(typeof window !== 'undefined' ? window : globalThis);
