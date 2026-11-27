(function () {
    if (window.__linboxFlashInstalled) return;
    window.__linboxFlashInstalled = true;
    var engineBase = 'https://linbox.local/ruffle/';
    var config = Object.assign({}, window.RufflePlayer && window.RufflePlayer.config, {
        publicPath: engineBase,
        polyfills: true,
        favorFlash: false,
        autoplay: 'on',
        unmuteOverlay: 'visible',
        upgradeToHttps: false,
        preferredRenderer: window.__linboxFlashRenderer || null,
        allowScriptAccess: true,
        allowFullscreen: true,
        splashScreen: false,
        logLevel: 'warn',
        fontSources: [engineBase + 'simhei.ttf'],
        defaultFonts: {
            sans: ['SimHei'], serif: ['SimHei'], typewriter: ['SimHei'],
            japaneseGothic: ['SimHei'], japaneseGothicMono: ['SimHei'], japaneseMincho: ['SimHei'],
            chineseSimplified: ['SimHei']
        }
    });
    window.RufflePlayer = window.RufflePlayer || {};
    window.RufflePlayer.config = config;

    if (!window.__linboxResourceDiagnostics) {
        window.__linboxResourceDiagnostics = true;
        var resourceExt = /\.([a-z0-9]{1,8})$/i;
        function safeResourceFields(raw, method, status, cors) {
            var url;
            try { url = new URL(raw, document.baseURI); } catch (error) { return ''; }
            if (!/^https?:$/.test(url.protocol) || !url.hostname || url.hostname.length > 253) return '';
            var ext = resourceExt.exec(url.pathname || '');
            var allowedExt = ['swf','xml','json','txt','bin','dat','data','pack','pck','bytes','zip','swz','atf','png','jpg','jpeg','gif','mp3','wav','ogg','aac','m4a','flv','f4v','mp4','webm','wasm','ttf','otf','woff','woff2','js','css'];
            var methodName = String(method || 'GET').toUpperCase();
            if (!/^(GET|HEAD|OPTIONS|POST|PUT|DELETE)$/.test(methodName)) methodName = 'OTHER';
            var statusNumber = Number(status) || 0;
            if (statusNumber < 0 || statusNumber > 599) statusNumber = 0;
            return '[LinBoxResource] host=' + url.hostname.toLowerCase() +
                ' ext=' + (ext && allowedExt.indexOf(ext[1].toLowerCase()) >= 0 ? ext[1].toLowerCase() : '-') +
                ' method=' + methodName + ' status=' + statusNumber + ' cors=' + (cors ? 'possible' : 'unknown');
        }
        try {
            if (typeof window.fetch === 'function') {
                var originalFetch = window.fetch;
                window.fetch = function (input, init) {
                    var raw = typeof input === 'string' ? input : input && input.url;
                    var method = init && init.method || input && input.method || 'GET';
                    return originalFetch.apply(this, arguments).then(function (response) {
                        if (!response.ok) console.warn(safeResourceFields(raw, method, response.status, response.type === 'opaque'));
                        return response;
                    }, function (error) {
                        console.warn(safeResourceFields(raw, method, 0, true));
                        throw error;
                    });
                };
            }
        } catch (error) {}
        try {
            if (window.XMLHttpRequest) {
                var originalOpen = XMLHttpRequest.prototype.open;
                var originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function (method, url) {
                    this.__linboxRequest = { method: method, url: url };
                    return originalOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function () {
                    var xhr = this, request = xhr.__linboxRequest || {};
                    xhr.addEventListener('loadend', function () {
                        if (xhr.status === 0 || xhr.status >= 400) {
                            console.warn(safeResourceFields(request.url, request.method, xhr.status, xhr.status === 0));
                        }
                    }, { once: true });
                    return originalSend.apply(this, arguments);
                };
            }
        } catch (error) {}
    }

    // Legacy plugin detection must succeed before the page decides whether to embed its movie.
    var mime = { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash' };
    var plugin = { name: 'Shockwave Flash', filename: 'libpepflashplayer.so', description: 'Shockwave Flash 32.0 r0', length: 1, 0: mime };
    mime.enabledPlugin = plugin;
    plugin.item = function (i) { return i === 0 ? mime : null; };
    plugin.namedItem = function (name) { return name === mime.type ? mime : null; };
    function augmented(original, extra, key) {
        var list = [];
        for (var i = 0; original && i < original.length; i++) list.push(original[i]);
        if (!list.some(function (item) { return item[key] === extra[key]; })) list.push(extra);
        list.forEach(function (item) { list[item[key]] = item; });
        list.item = function (index) { return list[index] || null; };
        list.namedItem = function (name) { return list[name] || null; };
        list.refresh = function () {};
        return list;
    }
    try {
        var plugins = augmented(navigator.plugins, plugin, 'name');
        var mimes = augmented(navigator.mimeTypes, mime, 'type');
        Object.defineProperty(navigator, 'plugins', { get: function () { return plugins; }, configurable: true });
        Object.defineProperty(navigator, 'mimeTypes', { get: function () { return mimes; }, configurable: true });
    } catch (error) { console.warn('[Flash] plugin detection shim unavailable'); }

    var loading;
    window.__linboxLoadRuffle = function () {
        if (loading) return loading;
        if (window.RufflePlayer.newest) return Promise.resolve(window.RufflePlayer.newest());
        loading = new Promise(function (resolve, reject) {
            function append() {
                var script = document.createElement('script');
                script.src = engineBase + 'ruffle.js';
                script.onload = function () {
                    var source = window.RufflePlayer.newest && window.RufflePlayer.newest();
                    if (!source) { reject(new Error('Ruffle player API unavailable')); return; }
                    try {
                        if (window.RufflePlayer.init) window.RufflePlayer.init();
                        else source.polyfill();
                    } catch (error) { reject(error); return; }
                    resolve(source);
                    document.dispatchEvent(new CustomEvent('ruffleReady'));
                };
                script.onerror = function () { script.remove(); reject(new Error('Local Ruffle assets could not be loaded')); };
                (document.head || document.documentElement).appendChild(script);
            }
            if (document.documentElement) append();
            else document.addEventListener('DOMContentLoaded', append, { once: true });
        });
        loading.catch(function (error) { loading = null; console.error('[Ruffle]', error.message); });
        return loading;
    };

    function isFlash(element) {
        if (!element || element.nodeType !== 1 || !/^(OBJECT|EMBED)$/.test(element.tagName)) return false;
        if (/shockwave-flash/i.test(element.type || '') || /d27cdb6e/i.test(element.getAttribute('classid') || '')) return true;
        if (/\.swf(?:[?#]|$)/i.test(element.getAttribute('data') || element.getAttribute('src') || '')) return true;
        return Array.prototype.some.call(element.querySelectorAll('param'), function (param) {
            return /^(movie|src)$/i.test(param.name) && /\.swf(?:[?#]|$)/i.test(param.value);
        });
    }
    function configureMovie(element) { return isFlash(element); }
    function inspect(node) {
        var found = configureMovie(node);
        if (!node.querySelectorAll) return found;
        Array.prototype.forEach.call(node.querySelectorAll('object,embed'), function (element) {
            if (configureMovie(element)) found = true;
        });
        return found;
    }
    var trackedPlayers = new Set();
    var trackedFrames = new WeakSet();
    var resumePlayers = new Set();
    var active = window.__linboxFlashActive !== false;
    function playerActivity(player) {
        if (active || !player.ruffle) return;
        var api = player.ruffle();
        if (api.isPlaying) { resumePlayers.add(player); api.suspend(); }
    }
    function trackPlayers(node) {
        if (!node || !node.querySelectorAll) return;
        var frames = Array.from(node.querySelectorAll('iframe,frame'));
        if (/^(IFRAME|FRAME)$/.test(node.tagName || '')) frames.push(node);
        frames.forEach(function (frame) {
            if (trackedFrames.has(frame)) return;
            trackedFrames.add(frame);
            frame.addEventListener('load', function () {
                if (frame.contentWindow) frame.contentWindow.postMessage({ type: 'linbox-flash-activity', active: active }, '*');
            });
        });
        var players = Array.from(node.querySelectorAll('ruffle-player,ruffle-object,ruffle-embed'));
        if (/^RUFFLE-(PLAYER|OBJECT|EMBED)$/.test(node.tagName || '')) players.push(node);
        players.forEach(function (player) {
            if (trackedPlayers.has(player)) return;
            trackedPlayers.add(player);
            player.addEventListener('loadeddata', function () { playerActivity(player); });
            playerActivity(player);
        });
        trackedPlayers.forEach(function (player) {
            if (!player.isConnected) { trackedPlayers.delete(player); resumePlayers.delete(player); }
        });
    }
    window.__linboxSetFlashActive = function (value) {
        active = !!value;
        window.__linboxFlashActive = active;
        if (active) {
            resumePlayers.forEach(function (player) { if (player.isConnected) player.ruffle().resume(); });
            resumePlayers.clear();
        } else trackedPlayers.forEach(playerActivity);
        document.querySelectorAll('iframe,frame').forEach(function (frame) {
            if (frame.contentWindow) frame.contentWindow.postMessage({ type: 'linbox-flash-activity', active: active }, '*');
        });
    };
    function flashSnapshot() {
        var players = [];
        document.querySelectorAll('ruffle-player,ruffle-object,ruffle-embed').forEach(function (player) {
            try {
                var api = player.ruffle();
                var metadata = api.metadata;
                players.push({
                    state: api.readyState,
                    playing: !!api.isPlaying,
                    as3: metadata ? !!metadata.isActionScript3 : null,
                    swfVersion: metadata ? Number(metadata.swfVersion) || 0 : 0,
                    width: metadata ? Number(metadata.width) || 0 : 0,
                    height: metadata ? Number(metadata.height) || 0 : 0,
                    frameRate: metadata ? Number(metadata.frameRate) || 0 : 0,
                    renderer: String(api.loadedConfig && api.loadedConfig.preferredRenderer || 'auto').slice(0, 16)
                });
            } catch (error) {}
        });
        return players;
    }
    window.__linboxFlashDiagnostics = {};
    window.__linboxRequestFlashDiagnostics = function (id) {
        if (typeof id !== 'string') return;
        var results = window.__linboxFlashDiagnostics[id] = [];
        results.push({ host: location.hostname, players: flashSnapshot() });
        document.querySelectorAll('iframe,frame').forEach(function (frame) {
            if (frame.contentWindow) frame.contentWindow.postMessage({ type: 'linbox-flash-diagnostic-request', id: id }, '*');
        });
        window.setTimeout(function () { delete window.__linboxFlashDiagnostics[id]; }, 5000);
    };
    window.addEventListener('message', function (event) {
        if (!event.data) return;
        if (event.data.type === 'linbox-flash-diagnostic-response') {
            var results = window.__linboxFlashDiagnostics[event.data.id];
            if (results && typeof event.data.host === 'string' && Array.isArray(event.data.players)) {
                results.push(event.data);
            } else if (window.parent !== window && event.source !== window.parent) {
                window.parent.postMessage(event.data, '*');
            }
            return;
        }
        if (window.parent === window || event.source !== window.parent) return;
        if (event.data.type === 'linbox-flash-activity') {
            window.__linboxSetFlashActive(event.data.active);
        } else if (event.data.type === 'linbox-flash-diagnostic-request' && typeof event.data.id === 'string') {
            document.querySelectorAll('iframe,frame').forEach(function (frame) {
                if (frame.contentWindow) frame.contentWindow.postMessage(event.data, '*');
            });
            event.source.postMessage({ type: 'linbox-flash-diagnostic-response', id: event.data.id, host: location.hostname, players: flashSnapshot() }, '*');
        }
    });
    window.__linboxFlashSnapshot = flashSnapshot;
    function start() {
        trackPlayers(document);
        if (inspect(document)) window.__linboxLoadRuffle();
        var observer = new MutationObserver(function (records) {
            var changed = new Set();
            var found = false;
            records.forEach(function (record) {
                if (record.type === 'attributes') {
                    var element = record.target.tagName === 'PARAM' ? record.target.parentElement : record.target;
                    if (configureMovie(element)) { changed.add(element); found = true; }
                } else {
                    Array.prototype.forEach.call(record.addedNodes, trackPlayers);
                    if (Array.prototype.some.call(record.addedNodes, inspect)) found = true;
                }
            });
            if (found) window.__linboxLoadRuffle().then(function () {
                // Ruffle's polyfill watches child insertion, not late movie/src attribute changes.
                changed.forEach(function (element) {
                    if (element.parentNode) element.parentNode.insertBefore(element, element.nextSibling);
                });
            }).catch(function () {});
        });
        observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['type', 'data', 'src', 'classid', 'name', 'value', 'quality'] });
        window.addEventListener('pagehide', function () { observer.disconnect(); }, { once: true });
    }
    window.addEventListener('pageshow', function (event) { if (event.persisted) start(); });
    start();
})();
