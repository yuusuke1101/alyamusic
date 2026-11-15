

/*
 * Port WebView untuk Alya Music (Paket 40): logika sama dengan skrip Node aslinya, tetapi jaringan lewat
 * Hub.http (/api/proxy). Modul ini hanya memberi METADATA (judul, artis, album, ada-LRC-atau-tidak, chart);
 * API-nya tidak memberi teks lirik penuh, jadi tidak punya cap 'lyrics'.
 */
(function (root) {
  'use strict';
  var H = root.AlyaSrc;
  if (!H) throw new Error('lyricfind: hub.js belum dimuat');

  var BASE_API = 'https://lyrics.lyricfind.com/api/v1';
  var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

  function okResp(d, fallback) {
    if (!d || (d.response && d.response.code !== 100)) {
      throw new Error((d && d.response && d.response.description) || fallback);
    }
  }
  function artistName(x) {
    return x.artist ? x.artist.name : (x.artists ? x.artists.map(function (a) { return a.name; }).join(', ') : '');
  }

  // type: 'track' | 'artist' | 'album'
  function search(query, opt) {
    opt = opt || {};
    var q = String(query || '').trim();
    if (!q) return Promise.reject(new Error('Query pencarian lagu/artis wajib diisi'));
    var type = ['artist', 'album', 'track'].indexOf(String(opt.type).toLowerCase()) >= 0 ? String(opt.type).toLowerCase() : 'track';
    var url = BASE_API + '/search?' + H.qs({
      reqtype: 'default', territory: 'US', searchtype: type, all: q, output: 'json', useragent: UA, limit: opt.limit || 10
    });
    return H.http(url, { json: true }).then(function (data) {
      okResp(data, 'Gagal mencari lirik');
      var items = (data.tracks || data.artists || data.albums || []).map(function (it) {
        return {
          src: 'lyricfind',
          id: it.lfid,
          title: it.title || it.name || '',
          artist: artistName(it),
          album: it.album ? it.album.title : null,
          duration: it.duration || null,
          cover: '',
          url: it.lyricfind_url || null,
          preview: null,
          snippet: it.snippet || null,
          lyricsPreview: it.context ? it.context.replace(/<\/?em>/g, '') : null,
          slug: it.slug || null,
          hasLrc: Boolean(it.has_lrc),
          spotifyId: it.spotify || null
        };
      });
      return { type: type, total: data.totalresults || items.length, results: items };
    });
  }

  // opt.type: 'tracks' | 'artists'
  function charts(opt) {
    opt = opt || {};
    var isArtist = String(opt.type || 'tracks').toLowerCase().indexOf('art') === 0;
    var url = BASE_API + '/charts?' + H.qs({
      reqtype: isArtist ? 'artistcharts' : 'trackcharts', territory: 'US', output: 'json', limit: 15, offset: 0, useragent: UA
    });
    return H.http(url, { json: true }).then(function (data) {
      okResp(data, 'Gagal memuat charts');
      var items = isArtist ? (data.artists || []).map(function (a) {
        return { src: 'lyricfind', id: a.lfid, name: a.name, title: a.name, artist: '', slug: a.slug, genres: a.genres ? a.genres.map(function (g) { return g.name; }) : [] };
      }) : (data.tracks || []).map(function (t) {
        return { src: 'lyricfind', id: t.lfid, title: t.title, artist: artistName(t), duration: t.duration || null, cover: '', hasLrc: Boolean(t.has_lrc) };
      });
      return { chartType: isArtist ? 'artists' : 'tracks', total: data.totalresults || items.length, results: items };
    });
  }

  function artist(slug) {
    var s = String(slug || '').toLowerCase().trim().replace(/^slug:/, '');
    if (!s) return Promise.reject(new Error('Slug artis wajib diisi (contoh: coldplay)'));
    var url = BASE_API + '/metadata?' + H.qs({
      reqtype: 'artistdata', artistid: 'slug:' + s, territory: 'US', output: 'json', limit: 1, offset: 0, useragent: UA
    });
    return H.http(url, { json: true }).then(function (data) {
      if (!data || (data.response && data.response.code !== 100) || !data.artist) {
        throw new Error((data && data.response && data.response.description) || 'Artis tidak ditemukan');
      }
      var a = data.artist;
      return {
        id: a.lfid, name: a.name, slug: a.slug,
        genres: a.genres ? a.genres.map(function (g) { return g.name; }) : [],
        similarArtists: a.similarArtists ? a.similarArtists.map(function (x) { return { name: x.name, slug: x.slug }; }) : []
      };
    });
  }

  H.register({
    id: 'lyricfind',
    name: 'LyricFind',
    credit: { Creator: 'Ryuusuke', Saluran: 'https://whatsapp.com/channel/0029VbDyL6d9RZAh3JjEM325' },
    caps: ['search', 'charts', 'artist'],
    types: ['track', 'artist', 'album'],
    search: search,
    charts: charts,
    artist: artist
  });
})(typeof window !== 'undefined' ? window : globalThis);
