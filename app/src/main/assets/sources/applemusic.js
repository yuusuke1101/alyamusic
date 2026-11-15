
/*
 * Port WebView untuk Alya Music (Paket 40): pencarian/lookup lewat iTunes Search API, playlist lewat halaman
 * music.apple.com; semua jaringan lewat Hub.http.
 * TIDAK ikut dibawa dari skrip aslinya: resolveFullAudio / download / bulkDownload (mengambil MP3 penuh lewat
 * situs pihak ketiga). Modul ini cuma metadata + preview 30 detik resmi; untuk memutar lagu penuh, sambungkan
 * lewat Hub.toPlayable(track) (dicarikan padanannya di pemutar Alya).
 */
(function (root) {
  'use strict';
  var H = root.AlyaSrc;
  if (!H) throw new Error('applemusic: hub.js belum dimuat');

  function parseUrl(input) {
    var s = String(input || '').trim(), m;
    if (/^\d+$/.test(s)) return { type: 'song', id: s };
    if ((m = s.match(/[?&]i=(\d+)/i))) return { type: 'song', id: m[1] };
    if ((m = s.match(/\/song\/(?:[^/]+\/)?(\d+)/i))) return { type: 'song', id: m[1] };
    if ((m = s.match(/\/album\/(?:[^/]+\/)?(\d+)/i))) return { type: 'album', id: m[1] };
    if ((m = s.match(/\/artist\/(?:[^/]+\/)?(\d+)/i))) return { type: 'artist', id: m[1] };
    if ((m = s.match(/\/playlist\/(?:[^/]+\/)?(pl\.[a-zA-Z0-9]+)/i))) return { type: 'playlist', id: m[1], url: s };
    return { type: 'query', id: s, query: s };
  }

  function track(t) {
    var art = t.artworkUrl100 || t.artworkUrl60 || '';
    return {
      src: 'applemusic',
      id: String(t.trackId || t.collectionId || t.artistId || ''),
      title: t.trackName || t.collectionName || t.artistName || '',
      artist: t.artistName || '',
      album: t.collectionName || '',
      genre: t.primaryGenreName || '',
      releaseDate: t.releaseDate || '',
      duration: H.fmtDur(t.trackTimeMillis),
      durationMs: t.trackTimeMillis || null,
      cover: H.hiRes(art, 600),
      coverHigh: H.hiRes(art, 1400),
      preview: t.previewUrl || null,
      url: t.trackViewUrl || t.collectionViewUrl || t.artistViewUrl || ''
    };
  }

  var ENT = { track: 'song', album: 'album', artist: 'musicArtist' };

  // opt.type: 'track' | 'album' | 'artist'
  function search(query, opt) {
    opt = opt || {};
    var q = String(query || '').trim();
    if (!q) return Promise.reject(new Error('Query pencarian diperlukan'));
    var ent = ENT[opt.type || 'track'] || 'song';
    var url = 'https://itunes.apple.com/search?' + H.qs({ term: q, entity: ent, limit: opt.limit || 20 });
    return H.http(url, { json: true }).then(function (d) {
      var results = ((d && d.results) || []).map(function (x) {
        var o = track(x);
        o.kind = x.wrapperType || x.kind || ent;
        return o;
      });
      return { entity: ent, total: results.length, results: results };
    });
  }

  function lookup(id, extra) {
    return H.http('https://itunes.apple.com/lookup?' + H.qs(Object.assign({ id: id }, extra || {})), { json: true })
      .then(function (d) { return (d && d.results) || []; });
  }

  function song(idOrUrl) {
    var p = parseUrl(idOrUrl);
    var idP = p.type === 'query' ? search(idOrUrl, { limit: 1 }).then(function (r) {
      if (!r.results[0]) throw new Error('Lagu "' + idOrUrl + '" tidak ditemukan di Apple Music');
      return r.results[0].id;
    }) : Promise.resolve(p.id);
    return idP.then(function (id) {
      if (!/^\d+$/.test(id)) throw new Error('ID atau URL lagu Apple Music tidak valid');
      return lookup(id).then(function (r) {
        if (!r[0]) throw new Error('Lagu dengan ID ' + id + ' tidak ditemukan');
        var o = track(r[0]);
        o.artistId = String(r[0].artistId || '');
        o.albumId = String(r[0].collectionId || '');
        o.trackNumber = r[0].trackNumber || 1;
        o.coverMax = H.hiRes(r[0].artworkUrl100 || '', 3000);
        return o;
      });
    });
  }

  function album(idOrUrl) {
    var p = parseUrl(idOrUrl);
    if (!/^\d+$/.test(p.id)) return Promise.reject(new Error('ID atau URL album Apple Music tidak valid'));
    return lookup(p.id, { entity: 'song' }).then(function (items) {
      if (!items.length) throw new Error('Album dengan ID ' + p.id + ' tidak ditemukan');
      var info = items.filter(function (i) { return i.wrapperType === 'collection'; })[0] || items[0];
      var tracks = items.filter(function (i) { return i.wrapperType === 'track'; }).map(function (t) {
        var o = track(t); o.trackNumber = t.trackNumber; return o;
      });
      var art = info.artworkUrl100 || info.artworkUrl60 || '';
      return {
        id: String(info.collectionId || p.id), title: info.collectionName || '', artist: info.artistName || '',
        artistId: String(info.artistId || ''), genre: info.primaryGenreName || '', releaseDate: info.releaseDate || '',
        copyright: info.copyright || '', trackCount: info.trackCount || tracks.length,
        cover: H.hiRes(art, 600), coverHigh: H.hiRes(art, 1400), url: info.collectionViewUrl || '', tracks: tracks
      };
    });
  }

  function artist(idOrUrl) {
    var p = parseUrl(idOrUrl);
    if (!/^\d+$/.test(p.id)) return Promise.reject(new Error('ID atau URL artis Apple Music tidak valid'));
    return Promise.all([lookup(p.id), lookup(p.id, { entity: 'album', limit: 25 })]).then(function (rs) {
      var a = rs[0][0];
      if (!a) throw new Error('Artis dengan ID ' + p.id + ' tidak ditemukan');
      var albums = rs[1].filter(function (i) { return i.wrapperType === 'collection'; }).map(function (x) {
        return {
          id: String(x.collectionId), title: x.collectionName, trackCount: x.trackCount, genre: x.primaryGenreName,
          releaseDate: x.releaseDate, cover: H.hiRes(x.artworkUrl100 || x.artworkUrl60 || '', 500), url: x.collectionViewUrl
        };
      });
      return {
        id: String(a.artistId || p.id), name: a.artistName || '', genre: a.primaryGenreName || '',
        artistType: a.artistType || '', url: a.artistLinkUrl || '', totalAlbums: albums.length, albums: albums
      };
    });
  }

  function playlist(urlOrId) {
    var u = String(urlOrId || '').trim();
    if (/^pl\.[a-zA-Z0-9]+$/i.test(u)) u = 'https://music.apple.com/us/playlist/' + u;
    if (!/^https:\/\/music\.apple\.com\//.test(u)) return Promise.reject(new Error('URL playlist Apple Music tidak valid'));
    return H.http(u).then(function (html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var meta = function (p) { var e = doc.querySelector('meta[property="' + p + '"]'); return (e && e.getAttribute('content')) || ''; };
      var title = meta('og:title') || (doc.title || '').trim();
      var tracks = [];
      var sd = doc.querySelector('script#serialized-server-data');
      if (sd) {
        try {
          var arr = (JSON.parse(sd.textContent) || {}).data || [];
          for (var i = 0; i < arr.length && !tracks.length; i++) {
            var secs = (arr[i] && arr[i].data && arr[i].data.sections) || [];
            for (var j = 0; j < secs.length; j++) {
              var items = (secs[j] && secs[j].items) || [];
              if (items.length > 5) {
                tracks = items.map(function (t) {
                  var ad = (t.artwork && (t.artwork.dictionary || t.artwork)) || {};
                  var art = ad.url ? ad.url.replace('{w}x{h}bb.{f}', '800x800bb.jpg') : null;
                  var tid = (String(t.id || '').match(/(\d+)$/) || [])[1] || t.id;
                  return {
                    src: 'applemusic', id: tid, title: t.title || '', artist: t.artistName || '',
                    trackNumber: t.trackNumber || null, duration: t.duration || null, cover: art,
                    preview: t.previewUrl || t.audioPreviewUrl || null
                  };
                });
                break;
              }
            }
          }
        } catch (e) { /* struktur halaman berubah: biarkan daftar kosong */ }
      }
      return {
        title: title.replace(/ on Apple Music$/i, '').trim(), curator: 'Apple Music', description: meta('og:description'),
        cover: H.hiRes(meta('og:image'), 600), url: u, totalTracks: tracks.length, tracks: tracks
      };
    });
  }

  H.register({
    id: 'applemusic',
    name: 'Apple Music',
    credit: { Creator: 'Ryuusuke', Saluran: 'https://whatsapp.com/channel/0029VbDyL6d9RZAh3JjEM325' },
    caps: ['search', 'detail', 'album', 'artist', 'playlist'],
    types: ['track', 'album', 'artist'],
    search: search,
    song: song,
    album: album,
    artist: artist,
    playlist: playlist,
    parseUrl: parseUrl
  });
})(typeof window !== 'undefined' ? window : globalThis);
