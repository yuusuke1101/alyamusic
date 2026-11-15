
(function (root) {
  'use strict';
  var H = root.AlyaSrc;
  if (!H) throw new Error('soundcloud: hub.js belum dimuat');

  var BASE = 'https://m.soundcloud.com';
  var API = 'https://api-v2.soundcloud.com';
  var clientId = '';

  function b64u(s) {
    var bytes = new TextEncoder().encode(s), bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function normUrl(u) { return String(u).replace('://m.soundcloud.com', '://soundcloud.com'); }

  function getClientId(force) {
    if (clientId && !force) return Promise.resolve(clientId);
    return H.http(BASE, { ttl: 0 }).then(function (html) {
      var m = String(html).match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/);
      if (!m) throw new Error('Gagal membaca konfigurasi SoundCloud');
      var id;
      try { id = JSON.parse(m[1]).runtimeConfig.clientId; } catch (e) { id = ''; }
      if (!id) throw new Error('client_id SoundCloud tidak ditemukan');
      clientId = id;
      return id;
    });
  }

  function api(endpoint, params) {
    function call(cid) {
      return H.http(API + endpoint + '?' + H.qs(Object.assign({}, params, { client_id: cid })), { json: true });
    }
    return getClientId().then(call).catch(function (e) {
      if (e && (e.status === 401 || e.status === 403)) return getClientId(true).then(call);
      throw e;
    });
  }

  function mapTrack(t) {
    var url = normUrl(t.permalink_url || '');
    return {
      src: 'soundcloud',
      id: t.id,
      title: t.title,
      artist: t.user ? t.user.username : '',
      album: '',
      duration: H.fmtDur(t.duration),
      genre: t.genre || '',
      plays: t.playback_count || 0,
      likes: t.likes_count || 0,
      cover: (t.artwork_url || (t.user && t.user.avatar_url) || '').replace('-large', '-t500x500'),
      url: url,
      preview: null,
      created: t.created_at,
      alyaId: url ? 'sc' + b64u(url) : null
    };
  }

  var KIND = { track: 'tracks', user: 'users', playlist: 'playlists' };

  // opt.type: 'track' | 'user' | 'playlist'
  function search(query, opt) {
    opt = opt || {};
    var q = String(query || '').trim();
    if (!q) return Promise.reject(new Error('Query pencarian diperlukan'));
    var type = KIND[opt.type] ? opt.type : 'track';
    return api('/search/' + KIND[type], { q: q, limit: Math.max(1, parseInt(opt.limit, 10) || 10) }).then(function (d) {
      var results = (d.collection || []).map(function (x) {
        if (type === 'track') return mapTrack(x);
        if (type === 'user') return { src: 'soundcloud', id: x.id, title: x.username, name: x.username, artist: '', followers: x.followers_count, tracks: x.track_count, url: x.permalink_url, cover: x.avatar_url };
        return { src: 'soundcloud', id: x.id, title: x.title, artist: x.user ? x.user.username : '', tracks: x.track_count, url: x.permalink_url, cover: x.artwork_url || '' };
      });
      return { type: type, total: results.length, results: results };
    });
  }

  // URL track / playlist / user SoundCloud -> detailnya
  function info(url) {
    if (!url) return Promise.reject(new Error('URL SoundCloud diperlukan'));
    return api('/resolve', { url: normUrl(url) }).then(function (d) {
      if (d.kind === 'track') return { kind: 'track', track: mapTrack(d) };
      if (d.kind === 'user') {
        return { kind: 'user', user: { id: d.id, name: d.username, followers: d.followers_count, tracks: d.track_count, description: d.description || '', avatar: d.avatar_url, url: d.permalink_url } };
      }
      if (d.kind === 'playlist') {
        var have = (d.tracks || []).filter(function (t) { return t.title; }).map(mapTrack);
        var missing = (d.tracks || []).filter(function (t) { return t.id && !t.title; }).map(function (t) { return t.id; });
        var batches = [];
        for (var i = 0; i < missing.length; i += 30) batches.push(missing.slice(i, i + 30));
        return Promise.all(batches.map(function (b) { return api('/tracks', { ids: b.join(',') }); })).then(function (rs) {
          var tracks = have.slice();
          rs.forEach(function (r) { (r || []).forEach(function (t) { tracks.push(mapTrack(t)); }); });
          return { kind: 'playlist', title: d.title, user: d.user ? d.user.username : '', total: tracks.length, tracks: tracks };
        });
      }
      return { kind: d.kind, raw: d };
    });
  }

  H.register({
    id: 'soundcloud',
    name: 'SoundCloud',
    credit: { Creator: 'Ryuusuke', Saluran: 'https://whatsapp.com/channel/0029VbDyL6d9RZAh3JjEM325' },
    caps: ['search', 'detail'],
    types: ['track', 'user', 'playlist'],
    search: search,
    info: info
  });
})(typeof window !== 'undefined' ? window : globalThis);
