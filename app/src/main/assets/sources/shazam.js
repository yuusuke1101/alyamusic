
/*
 * Port WebView untuk Alya Music (Paket 40): jaringan lewat Hub.http. Berkas Shazam yang diunggah ada dua dan
 * isinya identik, jadi di sini cukup satu modul.
 * Chart diambil dengan regex dari HTML halaman Shazam — rapuh: kalau Shazam mengubah nama kelas, chart kosong.
 */
(function (root) {
  'use strict';
  var H = root.AlyaSrc;
  if (!H) throw new Error('shazam: hub.js belum dimuat');

  function track(t) {
    var art = t.artworkUrl100 || t.artworkUrl60 || t.artworkUrl30 || '';
    return {
      src: 'shazam',
      id: t.trackId || null,
      title: t.trackName || '',
      artist: t.artistName || '',
      album: t.collectionName || '',
      genre: t.primaryGenreName || '',
      releaseDate: t.releaseDate ? t.releaseDate.split('T')[0] : '',
      duration: H.fmtDur(t.trackTimeMillis),
      durationSeconds: t.trackTimeMillis ? Math.round(t.trackTimeMillis / 1000) : 0,
      preview: t.previewUrl || null,
      cover: art ? art.replace(/\/\d+x\d+bb\.jpg$/, '/600x600bb.jpg') : '',
      thumbnail: art,
      url: t.trackViewUrl || '',
      artistUrl: t.artistViewUrl || ''
    };
  }

  function search(query, opt) {
    opt = opt || {};
    var q = String(query || '').trim();
    if (!q) return Promise.reject(new Error('Query pencarian tidak boleh kosong'));
    var lim = Math.max(1, Math.min(Number(opt.limit) || 10, 50));
    return H.http('https://itunes.apple.com/search?' + H.qs({ term: q, entity: 'song', limit: lim }), { json: true })
      .then(function (d) {
        var results = (d && Array.isArray(d.results)) ? d.results.map(track) : [];
        return { total: results.length, results: results };
      });
  }

  // opt.country: 'world' | kode negara (mis. 'indonesia' / 'id' sesuai yang dikenal Shazam); opt.limit
  function charts(opt) {
    opt = opt || {};
    var country = String(opt.country || 'world').trim().toLowerCase();
    var lim = Math.max(1, Math.min(Number(opt.limit) || 20, 200));
    return H.http('https://www.shazam.com/charts/top-50/' + encodeURIComponent(country)).then(function (html) {
      var re = /<span[^>]*SongItem_rankingNumber[^>]*>(\d+)<\/span>[\s\S]*?<a[^>]*charts_userevent_list_songTitle[^>]*href="([^"]+)"[^>]*>([^<]+)<\/a>[\s\S]*?<a[^>]*charts_userevent_list_artistName[^>]*>([^<]+)<\/a>[\s\S]*?(https:\/\/is1-ssl\.mzstatic\.com\/image\/thumb\/[^"]+)/g;
      var list = [], m;
      while ((m = re.exec(html)) !== null && list.length < lim) {
        var raw = m[5].split(' ')[0].split('"')[0];
        list.push({
          src: 'shazam', rank: Number(m[1]), id: null,
          title: m[3].replace(/&amp;/g, '&'), artist: m[4].replace(/&amp;/g, '&'),
          url: 'https://www.shazam.com' + m[2],
          cover: raw.replace(/\/\d+x\d+bb\.(jpg|webp)$/, '/600x600bb.jpg'), thumbnail: raw
        });
      }
      return { chart: country, total: list.length, results: list };
    });
  }

  function detail(trackId) {
    var id = String(trackId || '').trim();
    if (!id) return Promise.reject(new Error('Track ID tidak boleh kosong'));
    return H.http('https://itunes.apple.com/lookup?' + H.qs({ id: id }), { json: true }).then(function (d) {
      var r = d && Array.isArray(d.results) && d.results[0];
      if (!r) throw new Error('Lagu dengan ID ' + id + ' tidak ditemukan');
      return track(r);
    });
  }

  function preview(trackId) {
    return detail(trackId).then(function (t) {
      if (!t.preview) throw new Error('Audio preview tidak tersedia untuk lagu ini');
      return { title: t.title, artist: t.artist, preview: t.preview, cover: t.cover };
    });
  }

  H.register({
    id: 'shazam',
    name: 'Shazam',
    credit: { Creator: 'Ryuusuke', Saluran: 'https://whatsapp.com/channel/0029VbDyL6d9RZAh3JjEM325' },
    caps: ['search', 'charts', 'detail'],
    types: ['track'],
    search: search,
    charts: charts,
    detail: detail,
    preview: preview
  });
})(typeof window !== 'undefined' ? window : globalThis);
