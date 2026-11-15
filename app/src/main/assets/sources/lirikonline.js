
/*
 * Port WebView untuk Alya Music (Paket 40): cheerio diganti DOMParser bawaan browser, jaringan lewat Hub.http.
 * Beda dari skrip aslinya: Referer/User-Agent tidak bisa diatur dari proxy (dipakai bawaan app).
 * Memberi lirik TEKS BIASA (tanpa waktu per baris) -> cap 'lyrics' untuk rantai Hub.lyrics.
 */
(function (root) {
  'use strict';
  var H = root.AlyaSrc;
  if (!H) throw new Error('lirikonline: hub.js belum dimuat');

  var BASE_URL = 'https://www.lirikonline.com';

  function clean(t) { return String(t || '').replace(/\s+/g, ' ').trim(); }
  function dom(html) { return new DOMParser().parseFromString(html, 'text/html'); }
  function txt(el) { return el ? clean(el.textContent) : ''; }

  function search(query) {
    var q = String(query || '').trim();
    if (!q) return Promise.reject(new Error('Kata kunci pencarian lagu wajib diisi'));
    return H.http(BASE_URL + '/?s=' + encodeURIComponent(q)).then(function (html) {
      var doc = dom(html), seen = {}, results = [];
      Array.prototype.forEach.call(doc.querySelectorAll('article.post'), function (el) {
        var a = el.querySelector('.entry-title a');
        var title = txt(a), url = a && a.getAttribute('href');
        if (!title || !url || seen[url]) return;
        seen[url] = 1;
        results.push({
          src: 'lirikonline', id: url, title: title, artist: '', url: url,
          snippet: txt(el.querySelector('.entry-content')) || null,
          author: txt(el.querySelector('.entry-author')) || null,
          date: txt(el.querySelector('time.entry-date')) || null
        });
      });
      return { total: results.length, results: results };
    });
  }

  // target: URL halaman lirik ATAU judul lagu (dicari dulu, hasil pertama dipakai)
  function detail(target) {
    var t = String(target || '').trim();
    if (!t) return Promise.reject(new Error('Target lirik lagu (URL atau judul lagu) wajib diisi'));
    var pageUrl = /^https?:\/\//.test(t) ? Promise.resolve(t) : search(t).then(function (r) {
      if (!r.results.length) throw new Error('Lirik lagu "' + t + '" tidak ditemukan');
      return r.results[0].url;
    });
    return pageUrl.then(function (url) {
      return H.http(url).then(function (html) {
        var doc = dom(html);
        var title = txt(doc.querySelector('header.entry-header h1.entry-title')) || txt(doc.querySelector('h1.page-title')) || 'Untitled';
        var box = doc.querySelector('.entry-content.entry-content-single') || doc.querySelector('.entry-content');
        if (!box) throw new Error('Isi lirik tidak ditemukan di halaman');
        box = box.cloneNode(true);
        Array.prototype.forEach.call(box.querySelectorAll('script, style, ins, .adsbygoogle, .idblog-topbanner-aftermenu, .breadcrumbs'), function (n) { n.remove(); });
        var paras = [];
        Array.prototype.forEach.call(box.querySelectorAll('p'), function (p) {
          var lines = H.decode(p.innerHTML.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '').replace(/&nbsp;/gi, ' '))
            .split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
          if (lines.length) paras.push(lines.join('\n'));
        });
        var lyrics = paras.join('\n\n').trim();
        if (!lyrics) lyrics = box.textContent.replace(/\n{3,}/g, '\n\n').trim();
        return {
          title: title, url: url, lyrics: lyrics,
          author: txt(doc.querySelector('.entry-author')) || null,
          date: txt(doc.querySelector('time.entry-date')) || null
        };
      });
    });
  }

  // Dipakai Hub.lyrics. Hasil pencarian pertama bisa salah lagu, jadi judulnya dicek dulu.
  function lyrics(title, artist) {
    var want = H.norm(title);
    return detail([artist, title].filter(Boolean).join(' ')).then(function (d) {
      if (want && H.norm(d.title).indexOf(want) < 0) throw new Error('LirikOnline: hasil tidak cocok dengan judul');
      if (!d.lyrics) throw new Error('LirikOnline: lirik kosong');
      return { src: 'lirikonline', title: d.title, text: d.lyrics, url: d.url };
    });
  }

  H.register({
    id: 'lirikonline',
    name: 'LirikOnline',
    credit: { Creator: 'Ryuusuke', Saluran: 'https://whatsapp.com/channel/0029VbDyL6d9RZAh3JjEM325' },
    caps: ['lyrics', 'detail'],
    types: [],
    search: search,
    detail: detail,
    lyrics: lyrics
  });
})(typeof window !== 'undefined' ? window : globalThis);
