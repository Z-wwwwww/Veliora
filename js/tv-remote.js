/* ==========================================================================
   Veliora 电视版 —— 遥控器交互层
   底层数据完全复用主项目：
     - 豆瓣推荐:  fetchDoubanData()          (js/douban.js)
     - 搜索/详情:  fetch('/api/search'|'/api/detail')  (js/api.js 拦截)
     - 代理鉴权:  window.ProxyAuth            (js/proxy-auth.js)
     - 密码校验:  isPasswordProtected / isPasswordVerified / verifyPassword (js/password.js)
     - 播放:      跳转 player.html            (js/player.js)
   本文件只负责「可用遥控器操作」的 UI。
   ========================================================================== */
(function () {
    'use strict';

    // ---------- 全局状态 ----------
    const state = {
        view: 'home',              // home | discover | search | detail | settings | history
        query: '',
        detail: null,              // { title, results:[搜索结果...], selectedIdx, episodes:[], videoInfo }
        discover: { form: '电影', genre: '', country: '', sort: 'U', tags: [], pageStart: 0, delMode: false,
                    loading: false, done: false, seen: new Set() },
        homeStale: false,          // 设置里改了影响首页的开关后置位
    };
    const PROXY = (typeof PROXY_URL !== 'undefined') ? PROXY_URL : '/proxy/';
    let epReversed = localStorage.getItem('episodesReversed') === 'true';

    const esc = s => String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');

    // ---------- 统一图标（Material Design Icons 内联 SVG，随文字颜色，替代零散 emoji/字符） ----------
    const ICON_PATHS = {
        search: 'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
        settings: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
        history: 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6a7 7 0 1 1 7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.96 8.96 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
        play: 'M8 5v14l11-7z',
        info: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z',
        copy: 'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z',
        del: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
        add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
        check: 'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
        close: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
        backspace: 'M22 3H7c-.69 0-1.23.35-1.59.88L0 12l5.41 8.11c.36.53.9.89 1.59.89h15c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-3 12.59L17.59 17 14 13.41 10.41 17 9 15.59 12.59 12 9 8.41 10.41 7 14 10.59 17.59 7 19 8.41 15.41 12 19 15.59z',
        download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
        upload: 'M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z',
        arrowUp: 'M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z',
        arrowDown: 'M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z',
    };
    function icon(name) {
        const d = ICON_PATHS[name];
        return d ? `<svg class="mi" viewBox="0 0 24 24"><path d="${d}"/></svg>` : '';
    }

    // ---------- 本地存储门面（键名/格式与主项目完全一致） ----------
    const store = {
        get(k, d) {
            try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; }
            catch (e) { return d; }
        },
        set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} },
    };

    // 若某个源的站点关掉了搜索接口（`ac=videolist&wd=` 不回 JSON），把它的 key 填进这里，
    // 会从默认值与已选列表中剔除，避免每次搜索都白等一次超时；源本身保留，仍可手动勾选。
    const SEARCH_DEAD_SOURCES = [];

    // 首次使用初始化默认值（与上游 app.js 行为一致）
    function initDefaults() {
        // 剔除已知搜索失效的源，已装机用户清一次（只做一次，不动其他选择）
        if (!localStorage.getItem('deadSourcesCleaned_v1')) {
            const sel = store.get('selectedAPIs', null);
            if (Array.isArray(sel) && SEARCH_DEAD_SOURCES.length) {
                const kept = sel.filter(k => !SEARCH_DEAD_SOURCES.includes(k));
                if (kept.length && kept.length !== sel.length) store.set('selectedAPIs', kept);
            }
            localStorage.setItem('deadSourcesCleaned_v1', '1');
        }
        if (localStorage.getItem('hasInitializedDefaults')) return;
        store.set('selectedAPIs', []);   // 默认无内置源，用户在「设置」中自行添加
        localStorage.setItem('yellowFilterEnabled', 'true');
        localStorage.setItem(PLAYER_CONFIG.adFilteringStorage, 'true');
        localStorage.setItem('doubanEnabled', 'true');
        localStorage.setItem('hasInitializedDefaults', 'true');
    }

    function getCustomAPIs() { return store.get('customAPIs', []); }
    function getSelected() {
        const sel = store.get('selectedAPIs', null);
        if (Array.isArray(sel)) return sel;
        return Object.keys(window.API_SITES || {}).filter(k => k !== 'custom' && !API_SITES[k].adult);
    }
    function setSelected(list) { store.set('selectedAPIs', list); }

    // 图片经后端代理加载（规避豆瓣/采集源的防盗链 418 与跨域），带上密码鉴权。
    // 同一张图复用同一 URL（缓存 8 分钟内有效，服务端时间戳窗口为 10 分钟），
    // 否则 Billboard 焦点跟随时每次都会重新下载。
    const imgUrlCache = new Map();
    function proxyImg(u) {
        if (!u) return '';
        const hit = imgUrlCache.get(u);
        if (hit && Date.now() - hit.ts < 8 * 60 * 1000) return hit.url;
        const hash = (window.__ENV__ && window.__ENV__.PASSWORD) || '';
        const url = PROXY + encodeURIComponent(u) + '?auth=' + encodeURIComponent(hash) + '&t=' + Date.now();
        imgUrlCache.set(u, { url, ts: Date.now() });
        return url;
    }

    // 可用采集源（selectedAPIs 中的内置源 + custom_N 自定义源）
    function getSources() {
        const customs = getCustomAPIs();
        return getSelected().filter(k =>
            k.startsWith('custom_') ? !!customs[parseInt(k.slice(7), 10)]
                                    : (window.API_SITES && window.API_SITES[k] && k !== 'custom'));
    }

    // 已选源中是否含成人源（含自定义 isAdult 源）
    function hasAdultSelected() {
        const customs = getCustomAPIs();
        return getSelected().some(k =>
            k.startsWith('custom_') ? !!(customs[parseInt(k.slice(7), 10)] || {}).isAdult
                                    : !!((window.API_SITES || {})[k] || {}).adult);
    }
    // 与上游一致：选中成人源时强制关闭黄色过滤
    function syncYellowFilterWithAdult() {
        if (hasAdultSelected()) localStorage.setItem('yellowFilterEnabled', 'false');
    }

    // ============================================================
    //  1. 空间导航引擎（基于几何最近邻，适配任意布局）
    // ============================================================
    let current = null;
    let overlayEl = null;   // 弹层（密码/输入/选项）打开时，焦点限制在弹层内

    function focusables() {
        if (overlayEl) {
            return [...overlayEl.querySelectorAll('.focusable')].filter(el =>
                el.offsetParent !== null && el.getBoundingClientRect().width > 0);
        }
        const root = document.querySelector('.tv-view.active') || document;
        const nav = document.getElementById('topbar');
        const list = [...root.querySelectorAll('.focusable')];
        // 顶部导航常驻可达
        if (nav) list.unshift(...nav.querySelectorAll('.focusable'));
        return list.filter(el => el.offsetParent !== null &&
            el.getBoundingClientRect().width > 0);
    }

    let heroFollowTimer = null;
    function setFocus(el, scroll = true) {
        if (!el) return;
        if (current) current.classList.remove('focused');
        current = el;
        el.classList.add('focused');
        if (scroll) ensureVisible(el);

        // Netflix TV 行为：首页焦点停留 0.4s 后，Billboard 切换为当前聚焦影片
        if (state.view === 'home' && el._doubanItem) {
            clearTimeout(heroFollowTimer);
            heroFollowTimer = setTimeout(() => setHero(el._doubanItem), 400);
        }

        // 发现页：焦点移到网格末尾两行附近时，自动追加下一批
        if (state.view === 'discover' && el.classList.contains('tv-tile')) {
            const box = document.getElementById('discResults');
            if (el.parentElement === box && !state.discover.done) {
                const idx = [...box.children].indexOf(el);
                if (box.children.length - idx <= 14) loadDiscover(true);
            }
        }
    }

    function ensureVisible(el) {
        // 筛选浮层内的元素固定定位，无需滚动（滚动反而会拖动底下的网格）
        if (el.closest('#discFilters.overlay')) return;
        // 横向：所在 row 手动 translateX 使卡片居中偏左
        const track = el.closest('.tv-row-track');
        if (track) {
            const row = track.parentElement;
            const cRect = el.getBoundingClientRect();
            const rRect = row.getBoundingClientRect();
            const pad = window.innerWidth * 0.04;
            let dx = (cRect.left - rRect.left) - pad;
            const cur = parseFloat(track.dataset.tx || '0');
            let tx = cur - dx;
            const maxTx = 0;
            const minTx = Math.min(0, row.clientWidth - track.scrollWidth - pad);
            tx = Math.max(minTx, Math.min(maxTx, tx));
            track.dataset.tx = tx;
            track.style.transform = `translateX(${tx}px)`;
        }
        // 纵向：让元素滚入视区。
        // 例外：聚焦首页第一排时回滚到顶部，保持 Billboard 完整可见（Netflix TV 行为）
        const rowEl = el.closest('.tv-row');
        const rowsEl = document.getElementById('rows');
        if (rowEl && rowsEl && rowEl === rowsEl.firstElementChild) {
            const home = document.getElementById('viewHome');
            if (home) home.scrollTo({ top: 0, behavior: 'smooth' });
            return;
        }
        el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    }

    function center(rect) { return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }; }

    function navigate(dir) {
        // 焦点所在节点被重渲染移除时（如药丸区刷新），其 rect 全为 0，会把方向判断带偏
        if (current && (!current.isConnected || current.offsetParent === null)) current = null;
        const items = focusables();
        if (!current) {
            const root = document.querySelector('.tv-view.active');
            setFocus((root && root.querySelector('.focusable')) || items[0]);
            return;
        }
        const from = current.getBoundingClientRect();
        const fc = center(from);
        let best = null, bestScore = Infinity;

        for (const el of items) {
            if (el === current) continue;
            const r = el.getBoundingClientRect();
            const c = center(r);
            const dx = c.x - fc.x, dy = c.y - fc.y;
            // 方向过滤
            if (dir === 'left' && dx > -8) continue;
            if (dir === 'right' && dx < 8) continue;
            if (dir === 'up' && dy > -8) continue;
            if (dir === 'down' && dy < 8) continue;
            // 主轴距离 + 交叉轴惩罚
            let primary, cross;
            if (dir === 'left' || dir === 'right') { primary = Math.abs(dx); cross = Math.abs(dy); }
            else { primary = Math.abs(dy); cross = Math.abs(dx); }
            const score = primary + cross * 2.5;
            if (score < bestScore) { bestScore = score; best = el; }
        }
        if (best) setFocus(best);
    }

    // ============================================================
    //  2. 视图切换
    // ============================================================
    function showView(name) {
        state.view = name;
        document.querySelectorAll('.tv-view').forEach(v => v.classList.remove('active'));
        const map = { home: 'viewHome', discover: 'viewDiscover', search: 'viewSearch', detail: 'viewDetail', settings: 'viewSettings', history: 'viewHistory' };
        document.getElementById(map[name]).classList.add('active');
        // 顶部导航高亮
        document.querySelectorAll('#navMenu .focusable').forEach(b =>
            b.classList.toggle('active', b.dataset.nav === (name === 'detail' ? '' : name)));
        // 视图数据刷新
        if (name === 'home') {
            if (state.homeStale) { state.homeStale = false; loadHome(); }
        }
        else if (name === 'discover') renderDiscover();
        else if (name === 'search') renderSearchHistoryChips();
        else if (name === 'settings') renderSettings();
        else if (name === 'history') renderHistoryView();
        // 默认焦点
        setTimeout(() => {
            if (name === 'home') setFocus(document.querySelector('#rows .tv-tile') || document.getElementById('heroPlay'));
            else if (name === 'discover') setFocus(document.querySelector('#discForm .chip'));
            else if (name === 'search') setFocus(document.querySelector('#keyboard .key'));
            else if (name === 'settings') setFocus(document.querySelector('#srcActions .chip'));
            else if (name === 'history') setFocus(document.querySelector('#historyGrid .tv-tile') || document.getElementById('navHistory'));
            else if (name === 'detail') setFocus(document.querySelector('#sourceTabs .source-tab') || document.querySelector('#episodes .ep'));
        }, 60);
    }

    function goBack() {
        // 发现页深处按返回：弹出半透明筛选浮层，网格位置不动；
        // 不改筛选直接返回/下键收起，焦点落回原卡片
        if (state.view === 'discover' && current && current.closest('#discResults')) {
            filterGate.open();
            return;
        }
        // 搜索页：焦点深入结果时第一次返回先跳回键盘区，再按一次才退出
        if (state.view === 'search' && current && current.closest('#searchResults')) {
            document.getElementById('viewSearch').scrollTo({ top: 0, behavior: 'smooth' });
            setFocus(document.querySelector('#keyboard .key'));
            return;
        }
        if (state.view === 'detail') { showView(state.query ? 'search' : 'home'); }
        else if (state.view !== 'home') { showView('home'); }
        else { /* home：无处可退 */ }
    }

    // ============================================================
    //  3. 键盘 / 遥控器事件
    // ============================================================
    document.addEventListener('keydown', (e) => {
        // 弹层优先（选项 > 文本输入 > 筛选浮层 > 密码）
        if (optGate.active) { optGate.onKey(e); return; }
        if (promptGate.active) { promptGate.onKey(e); return; }
        if (filterGate.active) { filterGate.onKey(e); return; }
        if (pwGate.active) { pwGate.onKey(e); return; }

        switch (e.key) {
            case 'ArrowUp':    e.preventDefault(); navigate('up'); break;
            case 'ArrowDown':  e.preventDefault(); navigate('down'); break;
            case 'ArrowLeft':  e.preventDefault(); navigate('left'); break;
            case 'ArrowRight': e.preventDefault(); navigate('right'); break;
            case 'Enter':      e.preventDefault(); if (current) current.click(); break;
            case 'Backspace':
            case 'Escape':
            case 'GoBack':
            case 'BrowserBack': e.preventDefault(); goBack(); break;
            default:
                // 搜索页支持物理键盘直接输入（PC / 带键盘的遥控器）
                if (state.view === 'search' && e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
                    state.query += e.key;
                    renderQuery();
                }
        }
    });

    // 鼠标悬停也能获取焦点（兼容 PC 调试）
    document.addEventListener('mouseover', (e) => {
        const f = e.target.closest('.focusable');
        if (f) setFocus(f, false);
    });

    // ============================================================
    //  4. 首页（豆瓣推荐，Netflix 横排）
    // ============================================================
    const HOME_ROWS = [
        { title: '热门电影', type: 'movie', tag: '热门' },
        { title: '热门剧集', type: 'tv', tag: '热门' },
        { title: '豆瓣高分', type: 'movie', tag: '豆瓣高分' },
        { title: '欧美精选', type: 'movie', tag: '欧美' },
        { title: '综艺', type: 'tv', tag: '综艺' },
    ];

    async function loadHome() {
        const rowsEl = document.getElementById('rows');
        rowsEl.innerHTML = '';
        let heroSet = false;

        // 豆瓣推荐可在设置中关闭（doubanEnabled，与上游同键）
        if (localStorage.getItem('doubanEnabled') === 'false') {
            rowsEl.innerHTML = `<div style="padding:4vw;color:var(--text-dim);font-size:1.3vw">
                豆瓣推荐已在「设置」中关闭。使用顶部「搜索」查找影片，时钟图标查看观看历史。</div>`;
            setFocus(document.getElementById('heroPlay'));
            return;
        }

        for (const cfg of HOME_ROWS) {
            const row = document.createElement('div');
            row.className = 'tv-row';
            row.innerHTML = `<h3 class="tv-row-title">${cfg.title}</h3>
                <div class="tv-row-track" data-tx="0"></div>`;
            rowsEl.appendChild(row);
            const track = row.querySelector('.tv-row-track');

            try {
                const url = `https://movie.douban.com/j/search_subjects?type=${cfg.type}&tag=${encodeURIComponent(cfg.tag)}&sort=recommend&page_limit=14&page_start=0`;
                const data = await fetchDoubanData(url);
                const subs = (data && data.subjects) || [];
                if (!subs.length) { row.remove(); continue; }

                subs.forEach(item => track.appendChild(makeCard(item)));

                if (!heroSet && subs[0]) { setHero(subs[0]); heroSet = true; }
            } catch (err) {
                console.warn('豆瓣行加载失败:', cfg.title, err);
                row.remove();
            }
        }

        if (!rowsEl.children.length) {
            rowsEl.innerHTML = `<div style="padding:4vw;color:var(--text-dim);font-size:1.3vw">
                无法加载豆瓣推荐（可能是网络/代理问题）。<br>你仍可使用顶部「搜索」直接查找影片。</div>`;
        }
        // 首次焦点
        setFocus(document.querySelector('#rows .tv-tile') || document.getElementById('heroPlay'));
    }

    // 豆瓣接口默认给 s_ratio_poster 小图；升级到 l_ratio_poster 大图（非豆瓣 URL 原样返回）
    function hdCover(u) {
        return (u || '')
            .replace('/s_ratio_poster/', '/l_ratio_poster/')
            .replace('/m_ratio_poster/', '/l_ratio_poster/');
    }

    function setHero(item) {
        const bg = document.getElementById('heroBg');
        // 小图铺满 Billboard 会糊，升级为大图
        const cover = hdCover(item.cover);
        const url = cover ? `url("${proxyImg(cover)}")` : '';
        bg.style.backgroundImage = url;
        // 右侧清晰主图层（与氛围层同一张图，只加载一次）
        const sharp = document.getElementById('heroBgSharp');
        if (sharp) sharp.style.backgroundImage = url;
        document.getElementById('heroTitle').textContent = item.title || 'Veliora';
        document.getElementById('heroMeta').innerHTML =
            (item.rate ? `<span class="rate">★ ${item.rate}</span>` : '') +
            `<span>豆瓣推荐</span>`;
        document.getElementById('heroDesc').textContent = '按 OK 键搜索并播放该影片';
        const open = () => openDetailByTitle(item.title, item.cover);
        document.getElementById('heroPlay').onclick = open;
        document.getElementById('heroInfo').onclick = open;
    }

    function makeCard(item, portrait) {
        const tile = document.createElement('div');
        tile.className = 'tv-tile focusable' + (portrait ? ' portrait' : '');
        const safeTitle = (item.title || '').replace(/"/g, '&quot;');
        tile.innerHTML = `
            <div class="thumb">
                <img src="${proxyImg(item.cover)}" loading="lazy"
                     onerror="this.onerror=null;this.style.display='none';this.nextElementSibling.style.display='flex';">
                <div class="fallback" style="display:none">${safeTitle}</div>
                ${item.rate ? `<div class="badge">★ ${item.rate}</div>` : ''}
            </div>
            <div class="label">${safeTitle}</div>`;
        if (!portrait) tile._doubanItem = item;   // 供 Billboard 焦点跟随使用（仅首页）
        tile.onclick = () => openDetailByTitle(item.title, item.cover);
        return tile;
    }

    // ---------- 观看历史页（顶栏时钟图标打开，返回键回主页；viewingHistory 由 player.js 写入，与主项目共享） ----------
    function renderHistoryView() {
        const grid = document.getElementById('historyGrid');
        grid.innerHTML = '';
        const history = store.get('viewingHistory', []);
        const list = Array.isArray(history) ? history.slice(0, 30) : [];
        if (!list.length) {
            grid.innerHTML = '<div class="history-empty">暂无观看历史</div>';
            return;
        }
        list.forEach(item => {
            const card = makeHistoryCard(item);
            grid.appendChild(card);
            if (!item.vod_pic) backfillHistoryCover(item, card);
        });
    }

    function makeHistoryCard(item) {
        const tile = document.createElement('div');
        tile.className = 'tv-tile focusable';
        const pct = item.duration ? Math.min(100, Math.round((item.playbackPosition || 0) / item.duration * 100)) : 0;
        const epText = (Array.isArray(item.episodes) && item.episodes.length > 1)
            ? `第${(item.episodeIndex || 0) + 1}集` : '';
        const coverHtml = item.vod_pic
            ? `<img class="cw-cover" src="${proxyImg(item.vod_pic)}" loading="lazy"
                   onerror="this.parentElement.classList.remove('has-cover');this.remove();">`
            : '';
        tile.innerHTML = `
            <div class="thumb cw${item.vod_pic ? ' has-cover' : ''}">
                ${coverHtml}
                <div class="cw-body">
                    <div class="cw-title">${esc(item.title)}</div>
                    <div class="cw-meta">${epText}${epText && item.sourceName ? ' · ' : ''}${esc(item.sourceName || '')}</div>
                    <div class="cw-pct">${pct ? '已看 ' + pct + '%' : icon('play') + ' 继续播放'}</div>
                </div>
                <div class="cw-progress" style="width:${pct}%"></div>
            </div>`;
        tile.onclick = () => resumeHistory(item);
        return tile;
    }

    // 旧历史记录没存封面：按标题在原采集源精确搜索补一张，写回 viewingHistory 并就地更新卡片
    const coverBackfillTried = new Set();
    async function backfillHistoryCover(item, tile) {
        if (!item.title || !item.sourceCode) return;
        const key = item.sourceCode + '|' + item.title;
        if (coverBackfillTried.has(key)) return;
        coverBackfillTried.add(key);
        try {
            const res = await searchByAPIAndKeyWord(item.sourceCode, item.title);
            const hit = (res || []).find(i =>
                (i.vod_name || '').trim() === item.title.trim() && i.vod_pic);
            if (!hit) return;
            item.vod_pic = hit.vod_pic;
            const history = store.get('viewingHistory', []);
            const target = history.find(h =>
                h.title === item.title && h.showIdentifier === item.showIdentifier && !h.vod_pic);
            if (target) { target.vod_pic = hit.vod_pic; store.set('viewingHistory', history); }
            const thumb = tile.querySelector('.thumb');
            if (thumb && !thumb.querySelector('.cw-cover')) {
                const img = document.createElement('img');
                img.className = 'cw-cover';
                img.loading = 'lazy';
                img.onerror = () => { thumb.classList.remove('has-cover'); img.remove(); };
                img.src = proxyImg(hit.vod_pic);
                thumb.classList.add('has-cover');
                thumb.prepend(img);
            }
        } catch (e) {}
    }

    function resumeHistory(item) {
        if (!ensureVerified()) return;
        const eps = Array.isArray(item.episodes) ? item.episodes : [];
        const idx = item.episodeIndex || 0;
        const url = eps[idx] || item.directVideoUrl;
        if (!url) {
            if (item.url) { window.location.href = item.url; return; }
            toast('该记录缺少播放地址');
            return;
        }
        try {
            if (eps.length) localStorage.setItem('currentEpisodes', JSON.stringify(eps));
            localStorage.setItem('currentVideoTitle', item.title || '');
            localStorage.setItem('currentEpisodeIndex', String(idx));
        } catch (e) {}
        const params = new URLSearchParams({
            url,
            title: item.title || '',
            index: String(idx),
            position: String(Math.floor(item.playbackPosition || 0)),
            source: item.sourceCode || '',
            id: item.vod_id || '',
            pic: item.vod_pic || '',
            returnUrl: 'index.html'
        });
        window.location.href = 'player.html?' + params.toString();
    }

    // ============================================================
    //  5. 搜索（屏幕虚拟键盘）
    // ============================================================
    const KEYS = [
        ...'ABCDEFGHIJ'.split(''),
        ...'KLMNOPQRST'.split(''),
        ...'UVWXYZ0123'.split(''),
        ...'456789'.split(''),
    ];

    function buildKeyboard() {
        const kb = document.getElementById('keyboard');
        kb.innerHTML = '';
        KEYS.forEach(k => {
            const el = document.createElement('div');
            el.className = 'key focusable';
            el.textContent = k;
            el.onclick = () => { state.query += k; renderQuery(); };
            kb.appendChild(el);
        });
        const space = keyBtn('空格', 'key focusable wide', () => { state.query += ' '; renderQuery(); });
        const del = keyBtn('删除', 'key focusable wide act', () => { state.query = state.query.slice(0, -1); renderQuery(); }, 'backspace');
        const clr = keyBtn('清空', 'key focusable', () => { state.query = ''; renderQuery(); });
        const go = keyBtn('搜索', 'key focusable wide act', () => runSearch(), 'search');
        kb.append(space, del, clr, go);
    }
    function keyBtn(text, cls, fn, ic) {
        const el = document.createElement('div');
        el.className = cls;
        if (ic) el.innerHTML = icon(ic) + (text ? ' ' + esc(text) : '');
        else el.textContent = text;
        el.onclick = fn;
        return el;
    }
    function renderQuery() {
        document.getElementById('searchBox').innerHTML =
            (state.query ? state.query.replace(/</g, '&lt;') : '<span style="color:var(--text-dim)">输入片名…</span>')
            + '<span class="cursor">|</span>';
        scheduleSuggest();
    }

    // ---------- 拼音联想：字母输入 → 中文片名候选 ----------
    // 软键盘只有字母数字，用户实际输入的是全拼（qingyunian）或首字母（qyn）。
    // 采集源是 MacCMS，wd= 只按 vod_name（中文名）做 LIKE 匹配，vod_en(拼音)/vod_sub(别名)
    // 都不参与匹配 —— 实测 wd=qingyunian / QYN / joyoflife 一律 total=0。
    // 所以字母输入必须先联想成中文片名，再拿中文去搜；三路联想互补：
    //   爱奇艺 suggest —— 影视垂直，支持全拼 + 首字母（qyn → 庆余年），结果最干净
    //   百度 suggestion —— 拼音/首字母覆盖最好，但混着网页搜索词（"庆余年演员表"），要清洗
    //   豆瓣 subject_suggest —— 只认中文名/英文原名（拼音一律返回 []），但英文片名靠它
    // 候选词既渲染成「猜你想搜」药丸，也在按「搜索」时自动参与搜索（见 buildSearchQueries）。

    const VIDEO_TAGS = ['电视剧', '电影', '动漫', '动画', '综艺', '纪录片', '少儿', '短剧', '漫剧', '微剧', '系列'];
    const PERSON_TAGS = ['人物', '明星', '演员', '导演'];
    // 联想词尾部噪音：'庆余年演员表'→庆余年、'甄嬛传76集全'→甄嬛传、'三体电视剧'→三体
    const NOISE_TAIL = /(在线观看|免费观看|免费在线|完整版|未删减|高清版|高清|全集|全剧|电视剧|电影版|电影|动漫|动画片|演员表|演员|分集剧情|剧情介绍|剧情|大结局|结局|解说|下载|百度百科|百科|小说原著|小说|原著|漫画|歌曲|图片|壁纸|资源|网盘|迅雷|免费|观看|上映时间|上映|什么时候|好看吗|评价|豆瓣|一共多少集|多少集|多少季|第[0-9一二三四五六七八九十]+集|[0-9]+集全|全[0-9]+集|爱奇艺|腾讯视频|优酷|芒果TV|哔哩哔哩|樱花动漫)$/;
    const SEASON_TAIL = /\s*第[0-9一二三四五六七八九十百]+[季部]$/;
    // 整条就是网页搜索词的（「breakingbad翻译成中文」「甄嬛传哪一年播出的」），直接不作为候选
    const NOISE_ANY = /(什么意思|怎么读|怎么写|翻译成|音标|原曲|简谱|歌词|哪一年|哪个平台|在哪看|在哪播|多少集|多少季|好看吗|值得看|排行|排名|百度|知乎|贴吧|网盘|迅雷|资源|演员表|分集|剧情介绍|大结局|解说|花絮|片尾曲|主题曲|上映时间|取景地|拍摄地|原著小说)/;

    const hasCJK = s => /[一-龥]/.test(s || '');

    function cleanSuggestTitle(s) {
        let t = String(s == null ? '' : s).replace(/[《》【】"'“”‘’]/g, '').trim();
        for (let i = 0; i < 4; i++) {
            const n = t.replace(NOISE_TAIL, '').trim();
            if (!n || n === t) break;
            t = n;
        }
        return t;
    }

    // 走后端代理取文本（联想接口都不带 CORS 头，且 App 内需带鉴权参数）
    async function fetchProxyText(url, timeout = 6000) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeout);
        try {
            const base = PROXY + encodeURIComponent(url);
            const target = window.ProxyAuth && window.ProxyAuth.addAuthToProxyUrl
                ? await window.ProxyAuth.addAuthToProxyUrl(base) : base;
            const res = await fetch(target, { signal: controller.signal });
            if (!res.ok) return '';
            return await res.text();
        } catch (e) {
            return '';
        } finally { clearTimeout(timer); }
    }

    // 各引擎统一返回 [{ title, weight }]，weight 越小越可信（0=确定是影视条目）
    async function suggestFromIqiyi(q) {
        const txt = await fetchProxyText(
            'https://suggest.video.iqiyi.com/?if=mobile&key=' + encodeURIComponent(q));
        let data;
        try { data = JSON.parse(txt); } catch (e) { return []; }
        const out = [];
        ((data && data.data) || []).forEach(it => {
            const pe = it.presentation_element || {};
            const tags = (pe.tags || []).map(t => t.name || '');
            if (tags.some(t => PERSON_TAGS.includes(t))) return;          // 影人条目搜不出片子
            const isVideo = tags.some(t => VIDEO_TAGS.includes(t)) || VIDEO_TAGS.includes(it.cname || '');
            const name = it.name || '';
            out.push({ title: cleanSuggestTitle(name), weight: isVideo ? 0 : 2 });
            // 英文原名条目的 show_reason 就是中文译名（Interstellar→星际穿越、Friends→老友记第一季），
            // 采集源只认中文名，这条才是能搜出结果的关键词
            const zh = pe.show_reason || '';
            if (!hasCJK(name) && hasCJK(zh) && zh.length <= 20 && !/[\/、《]/.test(zh)) {
                out.push({ title: cleanSuggestTitle(zh), weight: isVideo ? 0 : 2 });
            }
        });
        return out;
    }

    async function suggestFromBaidu(q) {
        // json=1 返回 JSONP：window.baidu.sug({"q":..,"g":[{"q":"庆余年"},..]})
        const txt = await fetchProxyText(
            'https://suggestion.baidu.com/su?ie=utf-8&json=1&p=3&wd=' + encodeURIComponent(q));
        const m = txt.match(/\{[\s\S]*\}/);
        if (!m) return [];
        let data;
        try { data = JSON.parse(m[0]); } catch (e) { return []; }
        const raw = Array.isArray(data.g) ? data.g.map(i => (i && i.q) || i)
                  : Array.isArray(data.s) ? data.s : [];
        return raw.map(s => ({ title: cleanSuggestTitle(s), weight: 1 }));
    }

    // 不走 fetchDoubanData：它超时 10s 且失败后还会去试早已失效的 allorigins 兜底，
    // 联想是三路并发等齐的，豆瓣一慢就把整次搜索拖住（实测能多等 10s 以上）
    async function suggestFromDouban(q) {
        const txt = await fetchProxyText(
            'https://movie.douban.com/j/subject_suggest?q=' + encodeURIComponent(q), 4000);
        let data;
        try { data = JSON.parse(txt); } catch (e) { return []; }
        return (Array.isArray(data) ? data : [])
            .filter(it => !(it.url || '').includes('/celebrity/'))        // 跳过影人条目
            .map(it => ({ title: cleanSuggestTitle(it.title), weight: 0 }));
    }

    // 输入 → 中文片名候选（按可信度排序）。同一输入只请求一次，逐字输入时前缀命中缓存
    const suggestCache = new Map();
    function resolveTitles(input) {
        const q = (input || '').trim();
        if (!q) return Promise.resolve([]);
        const key = q.toLowerCase();
        if (suggestCache.has(key)) return suggestCache.get(key);

        const ascii = !hasCJK(q);
        // 引擎顺序即同权重下的排序优先级。豆瓣放第一：它认英文原名且直接给中文译名
        // （friends → 老友记 第一季），正是采集源要的关键词
        const engines = ascii
            ? [suggestFromDouban(key), suggestFromIqiyi(key), suggestFromBaidu(key)]
            : [suggestFromDouban(q), suggestFromIqiyi(q)];

        const job = Promise.all(engines.map(p => Promise.resolve(p).catch(() => [])))
            .then(lists => {
                const scored = new Map();
                lists.forEach((list, engineIdx) => {
                    list.forEach((item, i) => {
                        const t = item.title;
                        if (!t || t.length < 2 || t.length > 24) return;
                        if (NOISE_ANY.test(t)) return;
                        // 字母输入时纯字母候选没意义（源按中文名匹配，原样输入另外会搜一次）
                        if (ascii && !hasCJK(t)) return;
                        const score = item.weight * 100 + engineIdx * 10 + i;
                        if (!scored.has(t) || scored.get(t) > score) scored.set(t, score);
                    });
                });
                const ordered = [...scored.entries()].sort((a, b) => a[1] - b[1]).map(e => e[0]);
                return dropWebSearchNoise(withCommonPrefix(ordered)).slice(0, 12);
            });
        suggestCache.set(key, job);
        job.catch(() => suggestCache.delete(key));
        return job;
    }

    // 百度联想常给「追风者软件」「追风者瑞金取景地」这类网页搜索词，片名本身反而不在列表里。
    // 若前几名共享同一中文前缀，就把该前缀本身补成首选候选（→「追风者」）。
    function withCommonPrefix(titles) {
        const top = titles.slice(0, 6).filter(hasCJK);
        if (top.length < 3) return titles;
        // 取「命中最多」的前缀（同命中数取更长的）：命中越多越像片名本体，
        // 只取最长会退化成「追风者若来」这种半截搜索词
        let best = '', bestCount = 0;
        for (const t of top) {
            for (let len = 2; len < t.length; len++) {
                const p = t.slice(0, len);
                if (!hasCJK(p)) continue;
                const count = top.filter(x => x.startsWith(p)).length;
                if (count < 3) continue;
                if (count > bestCount || (count === bestCount && p.length > best.length)) {
                    best = p; bestCount = count;
                }
            }
        }
        best = best.replace(/[第之的与和上下新·\s]+$/, '');   // 「庆余年第」这类断在半个词上的前缀
        if (best.length < 2 || titles.includes(best)) return titles;
        return [best, ...titles];
    }

    // 已有候选 + 非「季/部」后缀 = 网页搜索词（「甄嬛传导演」「庆余年小说原著」），去掉
    const SEASONISH = /^(第[0-9一二三四五六七八九十百]+[季部]|[0-9]{1,2}|之.{1,10}|续集|外传|前传|后传|年番|动态漫画.*|粤语|国语|特别篇|剧场版|终章|完结篇)$/i;
    function dropWebSearchNoise(titles) {
        const kept = [];
        titles.forEach(t => {
            const parent = kept.find(k => t !== k && t.startsWith(k));
            if (parent) {
                const rest = t.slice(parent.length).replace(/^[\s·:：\-—_]+/, '');
                if (!SEASONISH.test(rest)) return;
            }
            kept.push(t);
        });
        return kept;
    }

    let suggestTimer = null, suggestToken = 0;
    function scheduleSuggest() {
        clearTimeout(suggestTimer);
        const box = document.getElementById('searchSuggest');
        if (!box) return;
        const q = state.query.trim();
        // 单字/单字母联想没意义，且遥控器逐字输入时每个字都要打三个接口，先攒够 2 位再问
        if (q.length < 2) { box.innerHTML = ''; suggestToken++; return; }
        suggestTimer = setTimeout(async () => {
            const token = ++suggestToken;
            try {
                const titles = await resolveTitles(q);
                if (token !== suggestToken || state.query.trim() !== q) return;   // 输入已变化，丢弃过期联想
                renderSuggestChips(titles);
            } catch (e) { /* 联想失败静默，不影响直接搜索 */ }
        }, 450);
    }

    function renderSuggestChips(titles) {
        const box = document.getElementById('searchSuggest');
        if (!box) return;
        box.innerHTML = '';
        if (!titles || !titles.length) return;
        const label = document.createElement('span');
        label.className = 'chips-label';
        label.textContent = '猜你想搜：';
        box.appendChild(label);
        titles.slice(0, 8).forEach(t => box.appendChild(chipBtn(t, () => {
            state.query = t;
            renderQuery();
            runSearch();
        })));
    }

    // 字母输入 → 实际搜索关键词。primary 一定要搜，fallback 只在 primary 没结果时再搜，
    // 免得次级联想（qyn 也会联想出「企业年金」）把无关片子混进结果
    async function buildSearchQueries(raw) {
        if (hasCJK(raw)) return { primary: [raw], fallback: [] };
        let titles = [];
        try { titles = await resolveTitles(raw); } catch (e) {}
        const bases = [], fulls = [];
        titles.filter(hasCJK).forEach(t => {
            // 去掉「第N季」搜主片名：源里一次能带出全部季，比逐季搜更全
            const b = t.replace(SEASON_TAIL, '').trim() || t;
            if (b.length >= 2 && hasCJK(b) && !bases.includes(b)) bases.push(b);
            if (t !== b && !fulls.includes(t)) fulls.push(t);
        });
        const ranked = [...bases, ...fulls];
        if (!ranked.length) return { primary: [raw], fallback: [] };
        // 原样输入放 fallback：源里偶尔有「星际穿越 Interstellar」这种片名，但拿 FRIENDS 去搜
        // 会把上百个含 friend 的无关条目也捞回来，只在中文候选搜空时才用
        return { primary: ranked.slice(0, 1), fallback: [...ranked.slice(1, 3), raw] };
    }

    async function runSearch() {
        const raw = state.query.trim();
        if (!raw) { toast('请输入片名'); return; }
        if (!ensureVerified()) return;
        if (!getSources().length) { toast('没有可用采集源，请到「设置」中选择'); return; }
        showLoading(hasCJK(raw) ? '搜索中…' : '正在联想片名…');
        try {
            const { primary, fallback } = await buildSearchQueries(raw);
            if (!hasCJK(raw)) renderSuggestChips(await resolveTitles(raw));   // 猜错时可直接点别的候选

            let used = primary;
            let list = await searchQueries(primary);
            if (!list.length && fallback.length) {     // 首选候选搜空了，再试次级候选
                used = fallback;
                list = await searchQueries(fallback);
            }
            // 只记真搜到东西的关键词，且记中文片名而不是 QYN 这种字母串，
            // 否则历史里全是联想歪了的词，点一次还是空
            if (list.length) saveSearchHistory(used.filter(hasCJK)[0] || raw);
            renderSearchResults(list, used.filter(hasCJK));
        } catch (e) {
            toast('搜索失败：' + e.message);
        } finally { hideLoading(); }
    }

    async function searchQueries(queries) {
        const cn = queries.filter(hasCJK);
        showLoading(cn.length ? '搜索「' + cn.join('、') + '」…' : '搜索中…');
        const lists = await Promise.all(queries.map(q => searchAll(q).catch(() => [])));
        return mergeResults(lists, cn[0] || queries[0]);
    }

    // 多关键词结果合并去重（同源同 vod_id 视为同一条），按与关键词的贴合度排序：
    // 源的 wd= 是模糊匹配，会带回一堆沾边条目，纯按片名字典序排会把正主埋在中间
    function mergeResults(lists, keyword) {
        const seen = new Set(), all = [];
        lists.flat().forEach(item => {
            const k = (item.source_code || '') + '|' + (item.vod_id || item.vod_name || '');
            if (seen.has(k)) return;
            seen.add(k);
            all.push(item);
        });
        const kw = (keyword || '').toLowerCase();
        const rank = item => {
            const n = (item.vod_name || '').toLowerCase();
            if (!kw) return 3;
            if (n === kw) return 0;
            if (n.startsWith(kw)) return 1;
            if (n.includes(kw)) return 2;
            return 3;
        };
        all.sort((a, b) =>
            rank(a) - rank(b) ||
            (a.vod_name || '').localeCompare(b.vod_name || '') ||
            (a.source_name || '').localeCompare(b.source_name || ''));
        return all;
    }

    // 黄色内容过滤关键词（与上游 app.js 一致）
    const YELLOW_BANNED = ['伦理片', '福利', '里番动漫', '门事件', '萝莉少女', '制服诱惑', '国产传媒', 'cosplay', '黑丝诱惑', '无码', '日本无码', '有码', '日本有码', 'SWAG', '网红主播', '色情片', '同性片', '福利视频', '福利片'];

    function applyYellowFilter(list) {
        if (localStorage.getItem('yellowFilterEnabled') !== 'true') return list;
        return list.filter(item => {
            const typeName = item.type_name || '';
            return !YELLOW_BANNED.some(kw => typeName.includes(kw));
        });
    }

    // 复用主项目 searchByAPIAndKeyWord（js/search.js：支持自定义源与多页抓取），多源合并
    async function searchAll(query) {
        const sources = getSources();
        if (!sources.length) throw new Error('没有可用采集源，请到「设置」中选择');
        const results = await Promise.all(sources.map(src =>
            searchByAPIAndKeyWord(src, query).catch(() => [])));
        let all = applyYellowFilter(results.flat());
        // 与上游一致：按片名、来源排序，方便同名结果聚在一起
        all.sort((a, b) =>
            (a.vod_name || '').localeCompare(b.vod_name || '') ||
            (a.source_name || '').localeCompare(b.source_name || ''));
        return all;
    }

    // ---------- 搜索历史（videoSearchHistory，与主项目 ui.js 同键同格式） ----------
    function getSearchHistory() {
        const parsed = store.get(SEARCH_HISTORY_KEY, []);
        if (!Array.isArray(parsed)) return [];
        return parsed
            .map(i => typeof i === 'string' ? { text: i, timestamp: 0 } : i)
            .filter(i => i && i.text);
    }

    function saveSearchHistory(query) {
        query = query.trim().substring(0, 50).replace(/</g, '&lt;').replace(/>/g, '&gt;');
        if (!query) return;
        const now = Date.now();
        let history = getSearchHistory()
            .filter(i => i.timestamp && now - i.timestamp < 5184000000)  // 2 个月有效期
            .filter(i => i.text !== query);
        history.unshift({ text: query, timestamp: now });
        store.set(SEARCH_HISTORY_KEY, history.slice(0, MAX_HISTORY_ITEMS));
        renderSearchHistoryChips();
    }

    function renderSearchHistoryChips() {
        const box = document.getElementById('recentSearches');
        if (!box) return;
        box.innerHTML = '';
        const history = getSearchHistory();
        if (!history.length) return;
        const label = document.createElement('span');
        label.className = 'chips-label';
        label.textContent = '最近搜索：';
        box.appendChild(label);
        history.forEach(h => box.appendChild(chipBtn(h.text, () => {
            state.query = h.text;
            renderQuery();
            runSearch();
        })));
        box.appendChild(chipBtn('清除记录', () => {
            localStorage.removeItem(SEARCH_HISTORY_KEY);
            renderSearchHistoryChips();
            setFocus(document.querySelector('#keyboard .key'));
        }, 'warn', 'del'));
    }

    function chipBtn(text, fn, cls, ic) {
        const el = document.createElement('div');
        el.className = 'chip focusable' + (cls ? ' ' + cls : '');
        if (ic) el.innerHTML = icon(ic) + (text ? ' ' + esc(text) : '');
        else el.textContent = text;
        el.onclick = fn;
        return el;
    }

    function renderSearchResults(list, guessed) {
        const box = document.getElementById('searchResults');
        box.innerHTML = '';
        if (!list.length) {
            const tip = hasCJK(state.query)
                ? '换个片名或到「设置」里多选几个采集源试试'
                : ((guessed && guessed.length)
                    ? '已试过联想片名：' + esc(guessed.join('、')) + '；可点上方「猜你想搜」选正确的片名'
                    : '采集源只能按中文片名搜索；请点上方「猜你想搜」里的候选词');
            box.innerHTML = `<div style="grid-column:1/-1;color:var(--text-dim);padding:2vw;line-height:1.8">`
                + `未找到「${esc(state.query)}」相关结果<br><span style="font-size:.85em">${tip}</span></div>`;
            return;
        }
        // 字母输入时告知实际用的中文关键词，避免用户以为搜的是自己打的字母
        if (guessed && guessed.length) {
            const note = document.createElement('div');
            note.style.cssText = 'grid-column:1/-1;color:var(--text-dim);font-size:.85em;padding:0 0 1vw';
            note.textContent = '按「' + guessed.join('、') + '」搜索到以下结果';
            box.appendChild(note);
        }
        // 按片名聚合：一部影片一张卡（与首页豆瓣卡片一致），点进详情后再切换播放源，
        // 避免同一部片在 N 个源里出现 N 张重复卡
        const groups = new Map();
        list.forEach(item => {
            const key = (item.vod_name || '').trim();
            if (!groups.has(key)) groups.set(key, []);
            groups.get(key).push(item);
        });
        groups.forEach(items => {
            const rep = items.find(i => i.vod_pic) || items[0];
            const tile = document.createElement('div');
            tile.className = 'tv-tile portrait focusable';
            const title = (rep.vod_name || '').replace(/"/g, '&quot;');
            const badge = items.length > 1 ? items.length + ' 个源' : (rep.source_name || '');
            tile.innerHTML = `
                <div class="thumb">
                    <img src="${proxyImg(rep.vod_pic)}" loading="lazy"
                         onerror="this.onerror=null;this.style.display='none';this.nextElementSibling.style.display='flex';">
                    <div class="fallback" style="display:none">${title}</div>
                    <div class="badge" style="color:#9cc2ff">${badge}</div>
                </div>
                <div class="label">${title}${rep.vod_remarks ? ' · ' + rep.vod_remarks : ''}</div>`;
            tile.onclick = () => openDetail(items, 0);
            box.appendChild(tile);
        });
        setTimeout(() => setFocus(box.querySelector('.tv-tile')), 50);
    }

    // ============================================================
    //  6. 详情 + 选集（复用 /api/detail）
    // ============================================================
    function sourceLabel(code) {
        if ((code || '').startsWith('custom_')) {
            const c = getCustomApiInfo(code.replace('custom_', ''));
            return (c && c.name) || '自定义源';
        }
        return (window.API_SITES && window.API_SITES[code] && window.API_SITES[code].name) || code;
    }

    // 通过标题搜索后进入详情（用于豆瓣卡片）；cover 为豆瓣封面，详情页优先复用其高清版
    // 立即进入详情页：每个源先显示灰色加载中药丸，各自搜索完成后变为可选，无结果则移除
    let detailToken = 0;
    async function openDetailByTitle(title, cover) {
        state.query = title || '';
        if (!ensureVerified()) return;
        const sources = getSources();
        if (!sources.length) { toast('没有可用采集源，请到「设置」中选择'); return; }

        const token = ++detailToken;
        state.detail = {
            results: [], selectedIdx: -1,
            doubanCover: hdCover(cover) || '',
            pendingSources: sources.map(sourceLabel),
            fuzzy: []   // 非精确同名结果，全部源无精确匹配时兜底
        };
        showView('detail');
        // 先用豆瓣信息占位，剧集区等首个源返回后填充
        document.getElementById('detailPoster').src = proxyImg(hdCover(cover) || '');
        document.getElementById('detailTitle').textContent = title || '';
        document.getElementById('detailMeta').innerHTML = '';
        document.getElementById('detailDesc').textContent = '正在查找片源…';
        document.getElementById('episodes').innerHTML = '';
        updateEpOrderBtn();
        renderSourceTabs();

        sources.forEach(src => {
            searchByAPIAndKeyWord(src, title).catch(() => []).then(list => {
                if (token !== detailToken) return;   // 已打开其他详情或离开
                const d = state.detail;
                const li = d.pendingSources.indexOf(sourceLabel(src));
                if (li >= 0) d.pendingSources.splice(li, 1);
                const usable = applyYellowFilter(list || []);
                const exact = usable.filter(i => (i.vod_name || '').trim() === (title || '').trim());
                if (exact.length) addDetailResults(exact);
                else d.fuzzy.push(...usable);
                // 全部源返回后仍无精确结果 → 回退到模糊结果（与原 searchAll 行为一致）
                if (!d.pendingSources.length && !d.results.length) {
                    if (d.fuzzy.length) addDetailResults(d.fuzzy);
                    else {
                        document.getElementById('detailDesc').textContent = '未找到可播放的片源';
                        toast('未找到可播放的片源');
                    }
                }
                renderSourceTabs();
            });
        });
    }

    // 追加结果药丸；首批到达时自动选中并加载剧集
    function addDetailResults(items) {
        const d = state.detail;
        const first = !d.results.length;
        d.results.push(...items);
        if (first) { d.selectedIdx = 0; loadEpisodes(0); }
        renderSourceTabs();
    }

    // results: 同一影片的多个源结果，作为「播放源」切换（搜索结果页入口）
    async function openDetail(results, idx, doubanCover) {
        detailToken++;   // 使仍在进行的按标题搜索失效
        state.detail = { results, selectedIdx: idx, doubanCover: doubanCover || '', pendingSources: [], fuzzy: [] };
        showView('detail');
        renderSourceTabs();
        await loadEpisodes(idx);
    }

    function renderSourceTabs() {
        const tabs = document.getElementById('sourceTabs');
        const { results, selectedIdx, pendingSources } = state.detail;
        tabs.innerHTML = '';
        results.forEach((r, i) => {
            const t = document.createElement('div');
            t.className = 'source-tab focusable' + (i === selectedIdx ? ' selected' : '');
            t.textContent = (r.source_name || '源' + (i + 1)) + (r.vod_remarks ? ' · ' + r.vod_remarks : '');
            t.onclick = () => { state.detail.selectedIdx = i; renderSourceTabs(); loadEpisodes(i); };
            tabs.appendChild(t);
        });
        // 尚未返回结果的源：灰色不可选
        (pendingSources || []).forEach(name => {
            const t = document.createElement('div');
            t.className = 'source-tab loading';
            t.textContent = name;
            tabs.appendChild(t);
        });
    }

    async function loadEpisodes(idx) {
        const r = state.detail.results[idx];
        const token = detailToken;
        const wrap = document.getElementById('episodes');
        wrap.innerHTML = `<div style="grid-column:1/-1;color:var(--text-dim)">加载剧集…</div>`;
        try {
            // 自定义源与 player.js 同约定：source=custom + customApi=地址
            let apiParams = '&source=' + encodeURIComponent(r.source_code || '');
            if ((r.source_code || '').startsWith('custom_')) {
                const c = getCustomApiInfo(r.source_code.replace('custom_', ''));
                if (!c) throw new Error('自定义源信息不存在');
                apiParams = '&customApi=' + encodeURIComponent(c.url) +
                    (c.detail ? '&customDetail=' + encodeURIComponent(c.detail) : '') +
                    '&source=custom';
            }
            const res = await fetch(`/api/detail?id=${encodeURIComponent(r.vod_id)}${apiParams}`);
            const data = await res.json();
            // 期间已切换到其他源/其他详情，丢弃过期响应
            if (token !== detailToken || state.detail.selectedIdx !== idx) return;
            const episodes = data.episodes || [];
            const info = data.videoInfo || {};
            state.detail.episodes = episodes;
            state.detail.videoInfo = info;

            // 头部信息：优先复用豆瓣高清封面（Billboard 已下载过同一张，直接命中缓存），回退采集源图
            document.getElementById('detailPoster').src =
                proxyImg(state.detail.doubanCover || info.cover || r.vod_pic);
            document.getElementById('detailTitle').textContent = info.title || r.vod_name || state.query;
            document.getElementById('detailMeta').innerHTML =
                [info.year, info.area, info.type, r.source_name].filter(Boolean)
                    .map(x => `<span>${x}</span>`).join('　');
            document.getElementById('detailDesc').textContent =
                (info.desc || '').replace(/<[^>]+>/g, '') || '暂无简介';

            renderEpisodes(episodes, r);
        } catch (e) {
            if (token === detailToken && state.detail.selectedIdx === idx) {
                wrap.innerHTML = `<div style="grid-column:1/-1;color:var(--text-dim)">剧集加载失败</div>`;
            }
            toast('剧集加载失败：' + e.message);
        }
    }

    function renderEpisodes(episodes, r) {
        const wrap = document.getElementById('episodes');
        wrap.innerHTML = '';
        updateEpOrderBtn();
        if (!episodes.length) {
            wrap.innerHTML = `<div style="grid-column:1/-1;color:var(--text-dim)">该源暂无可播放剧集</div>`;
            return;
        }
        const order = [...episodes.keys()];
        if (epReversed) order.reverse();
        order.forEach(i => {
            const ep = document.createElement('div');
            ep.className = 'ep focusable';
            if (episodes.length === 1) ep.innerHTML = icon('play') + ' 播放';
            else ep.textContent = '第' + (i + 1) + '集';
            ep.onclick = () => play(i, r);
            wrap.appendChild(ep);
        });
        setTimeout(() => setFocus(document.querySelector('#sourceTabs .source-tab.selected') || wrap.querySelector('.ep')), 50);
    }

    function updateEpOrderBtn() {
        const btn = document.getElementById('btnEpOrder');
        if (btn) btn.innerHTML = icon(epReversed ? 'arrowUp' : 'arrowDown') + (epReversed ? ' 正序排列' : ' 倒序排列');
    }

    function toggleEpisodeOrder() {
        epReversed = !epReversed;
        localStorage.setItem('episodesReversed', String(epReversed));   // player.js 同键，播放页选集同步倒序
        if (state.detail && Array.isArray(state.detail.episodes)) {
            renderEpisodes(state.detail.episodes, state.detail.results[state.detail.selectedIdx]);
            setTimeout(() => setFocus(document.getElementById('btnEpOrder')), 60);
        } else updateEpOrderBtn();
    }

    function copyEpisodeLinks() {
        const eps = (state.detail && state.detail.episodes) || [];
        if (!eps.length) { toast('暂无可复制的链接'); return; }
        const text = eps.join('\n');
        const done = () => toast('已复制 ' + eps.length + ' 条播放链接');
        const fallback = () => {
            try {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'fixed'; ta.style.opacity = '0';
                document.body.appendChild(ta);
                ta.select();
                const ok = document.execCommand('copy');
                document.body.removeChild(ta);
                ok ? done() : toast('复制失败，当前环境不支持剪贴板');
            } catch (e) { toast('复制失败，当前环境不支持剪贴板'); }
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, fallback);
        } else fallback();
    }

    // ============================================================
    //  7. 播放（复用 player.html，完全一致的播放底层）
    // ============================================================
    function play(index, r) {
        const { episodes, videoInfo } = state.detail;
        const title = (videoInfo && videoInfo.title) || r.vod_name || state.query;
        // 与主项目 player.js 约定一致：localStorage + URL 参数
        try {
            localStorage.setItem('currentEpisodes', JSON.stringify(episodes));
            localStorage.setItem('currentVideoTitle', title);
            localStorage.setItem('currentEpisodeIndex', String(index));
        } catch (e) {}
        const params = new URLSearchParams({
            url: episodes[index],
            title: title,
            index: String(index),
            source: r.source_code || '',
            pic: state.detail.doubanCover || (videoInfo && videoInfo.cover) || r.vod_pic || '',
            returnUrl: 'index.html'
        });
        window.location.href = 'player.html?' + params.toString();
    }

    // ============================================================
    //  8. 通用弹层：文本输入（软键盘）与选项选择
    // ============================================================
    function getPromptOverlay() {
        let el = document.getElementById('promptOverlay');
        if (!el) {
            el = document.createElement('div');
            el.id = 'promptOverlay';
            el.className = 'tv-center hidden';
            document.body.appendChild(el);
        }
        return el;
    }

    // 软键盘文本输入：promptGate.open(标题, {charset:'text'|'url'}) → Promise<string|null>
    const promptGate = {
        active: false, value: '', upper: false, charset: 'text', resolve: null,
        open(label, opts = {}) {
            return new Promise(res => {
                this.active = true;
                this.value = opts.value || '';
                this.charset = opts.charset || 'text';
                this.upper = false;
                this.resolve = res;
                const el = getPromptOverlay();
                el.classList.remove('hidden');
                el.innerHTML = `
                    <div class="prompt-label">${esc(label)}</div>
                    <div class="search-box" id="promptBox" style="min-width:46vw"></div>
                    <div class="tv-keyboard" id="promptKb" style="max-width:66vw;margin-bottom:0"></div>
                    <div class="msg" style="font-size:1vw">支持物理键盘直接输入 · 返回键删除</div>`;
                this._prevOverlay = overlayEl;   // 支持从筛选浮层等其他弹层内打开
                overlayEl = el;
                this.buildKb();
                this.render();
                setTimeout(() => setFocus(document.querySelector('#promptKb .key')), 50);
            });
        },
        buildKb() {
            const kb = document.getElementById('promptKb');
            kb.innerHTML = '';
            let chars;
            if (this.charset === 'url') {
                chars = [...'abcdefghijklmnopqrstuvwxyz0123456789', ...':/.-_?&=%#@+~'];
                if (this.upper) chars = chars.map(c => c.toUpperCase());
            } else {
                chars = [...'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'];
            }
            chars.forEach(k => kb.appendChild(keyBtn(k, 'key focusable', () => { this.value += k; this.render(); })));
            if (this.charset === 'url') {
                kb.appendChild(keyBtn(this.upper ? 'abc' : 'ABC', 'key focusable', () => {
                    this.upper = !this.upper;
                    this.buildKb();
                    setTimeout(() => setFocus(document.querySelector('#promptKb .key')), 30);
                }));
            }
            kb.appendChild(keyBtn('空格', 'key focusable', () => { this.value += ' '; this.render(); }));
            kb.appendChild(keyBtn('', 'key focusable', () => { this.value = this.value.slice(0, -1); this.render(); }, 'backspace'));
            kb.appendChild(keyBtn('清空', 'key focusable', () => { this.value = ''; this.render(); }));
            kb.appendChild(keyBtn('取消', 'key focusable act', () => this.close(null)));
            kb.appendChild(keyBtn('确定', 'key focusable wide act', () => this.close(this.value.trim()), 'check'));
        },
        render() {
            const b = document.getElementById('promptBox');
            if (b) b.innerHTML = esc(this.value) + '<span class="cursor">|</span>';
        },
        close(result) {
            this.active = false;
            overlayEl = this._prevOverlay || null;
            this._prevOverlay = null;
            getPromptOverlay().classList.add('hidden');
            const r = this.resolve;
            this.resolve = null;
            if (r) r(result);
        },
        onKey(e) {
            switch (e.key) {
                case 'ArrowUp':    e.preventDefault(); navigate('up'); break;
                case 'ArrowDown':  e.preventDefault(); navigate('down'); break;
                case 'ArrowLeft':  e.preventDefault(); navigate('left'); break;
                case 'ArrowRight': e.preventDefault(); navigate('right'); break;
                case 'Enter':      e.preventDefault(); if (current) current.click(); break;
                case 'Backspace':  e.preventDefault(); this.value = this.value.slice(0, -1); this.render(); break;
                case 'Escape': case 'GoBack': case 'BrowserBack':
                    e.preventDefault(); this.close(null); break;
                default:
                    if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
                        this.value += e.key;
                        this.render();
                    }
            }
        }
    };

    // 选项选择：optGate.open(标题HTML, [{text,value}], {dismissable}) → Promise<value|null>
    const optGate = {
        active: false, resolve: null, dismissable: true,
        open(labelHtml, options, opts = {}) {
            return new Promise(res => {
                this.active = true;
                this.resolve = res;
                this.dismissable = opts.dismissable !== false;
                const el = getPromptOverlay();
                el.classList.remove('hidden');
                el.innerHTML = `
                    <div class="prompt-label">${labelHtml}</div>
                    <div class="chips" id="optChips" style="justify-content:center"></div>`;
                const box = el.querySelector('#optChips');
                options.forEach(o => box.appendChild(chipBtn(o.text, () => this.close(o.value), o.cls)));
                this._prevOverlay = overlayEl;
                overlayEl = el;
                setTimeout(() => setFocus(box.querySelector('.chip')), 50);
            });
        },
        close(v) {
            this.active = false;
            overlayEl = this._prevOverlay || null;
            this._prevOverlay = null;
            getPromptOverlay().classList.add('hidden');
            const r = this.resolve;
            this.resolve = null;
            if (r) r(v);
        },
        onKey(e) {
            switch (e.key) {
                case 'ArrowUp':    e.preventDefault(); navigate('up'); break;
                case 'ArrowDown':  e.preventDefault(); navigate('down'); break;
                case 'ArrowLeft':  e.preventDefault(); navigate('left'); break;
                case 'ArrowRight': e.preventDefault(); navigate('right'); break;
                case 'Enter':      e.preventDefault(); if (current) current.click(); break;
                case 'Backspace': case 'Escape': case 'GoBack': case 'BrowserBack':
                    e.preventDefault(); if (this.dismissable) this.close(null); break;
            }
        }
    };

    // ============================================================
    //  9. 发现页（豆瓣多维筛选：形态 × 类型 × 地区 × 标签，可组合）
    //     使用 new_search_subjects 接口，genres/countries/tags 参数可叠加
    // ============================================================
    const DISC_FORMS = ['电影', '电视剧', '综艺', '动画', '纪录片'];
    const DISC_SORTS = [['U', '近期热度'], ['S', '高分优先'], ['R', '最新上映']];
    const DISC_GENRES = ['剧情', '喜剧', '动作', '爱情', '科幻', '悬疑', '惊悚', '恐怖', '犯罪', '奇幻', '冒险', '战争', '传记', '历史', '音乐', '家庭'];
    const DISC_COUNTRIES = ['中国大陆', '中国香港', '中国台湾', '美国', '韩国', '日本', '英国', '法国', '德国', '泰国', '印度'];

    // 自定义标签沿用主项目键名（userMovieTags/userTvTags），作为可叠加的附加筛选
    function tagStoreKey() { return state.discover.form === '电影' ? 'userMovieTags' : 'userTvTags'; }
    function getUserTags() {
        const saved = store.get(tagStoreKey(), null);
        return Array.isArray(saved) ? saved : [];
    }
    function saveUserTags(tags) { store.set(tagStoreKey(), tags); }

    // 筛选浮层：网格深处按返回时弹出，滚动位置与数据都不动；
    // 不改筛选就收起（返回键，或在最下排继续按「下」）→ 焦点落回原卡片
    const filterGate = {
        active: false, returnEl: null,
        open() {
            this.active = true;
            this.returnEl = current;
            const panel = document.getElementById('discFilters');
            panel.classList.add('overlay');
            overlayEl = panel;
            setFocus(document.querySelector('#discForm .chip'), false);
        },
        close() {
            this.active = false;
            overlayEl = null;
            document.getElementById('discFilters').classList.remove('overlay');
            // 筛选没变 → 原卡片还在，焦点直接落回去；变了（网格已重建）→ 回到新结果第一张
            if (this.returnEl && document.contains(this.returnEl)) setFocus(this.returnEl);
            else setFocus(document.querySelector('#discResults .tv-tile') || document.querySelector('#discForm .chip'));
            this.returnEl = null;
        },
        onKey(e) {
            switch (e.key) {
                case 'ArrowUp':    e.preventDefault(); navigate('up'); break;
                case 'ArrowLeft':  e.preventDefault(); navigate('left'); break;
                case 'ArrowRight': e.preventDefault(); navigate('right'); break;
                case 'ArrowDown': {
                    e.preventDefault();
                    const prev = current;
                    navigate('down');
                    if (current === prev) this.close();   // 最下排再按「下」＝收起回到网格
                    break;
                }
                case 'Enter': e.preventDefault(); if (current) current.click(); break;
                case 'Backspace': case 'Escape': case 'GoBack': case 'BrowserBack':
                    e.preventDefault(); this.close(); break;
            }
        }
    };

    function renderDiscover() {
        const d = state.discover;
        const label = t => {
            const s = document.createElement('span');
            s.className = 'chips-label';
            s.textContent = t;
            return s;
        };
        const reload = () => {
            const focusText = current && current.textContent;   // 重渲染后焦点回到同一个 chip
            d.pageStart = 0;
            renderDiscover();
            loadDiscover();
            if (focusText) {
                const same = [...document.querySelectorAll('#discFilters .chip')]
                    .find(c => c.textContent === focusText);
                if (same) setFocus(same, false);
            }
        };

        // 形态 + 排序 + 换一批
        const form = document.getElementById('discForm');
        form.innerHTML = '';
        form.appendChild(label('形态'));
        DISC_FORMS.forEach(f => form.appendChild(chipBtn(f, () => {
            if (d.form === f) return;
            d.form = f;
            d.tags = [];
            d.delMode = false;
            reload();
        }, d.form === f ? 'on' : '')));
        const spacer = document.createElement('span');
        spacer.className = 'chips-spacer';
        form.appendChild(spacer);
        form.appendChild(label('排序'));
        DISC_SORTS.forEach(([key, name]) => form.appendChild(chipBtn(name, () => {
            if (d.sort === key) return;
            d.sort = key;
            reload();
        }, d.sort === key ? 'on' : '')));

        // 类型（与地区/标签可任意组合）
        const g = document.getElementById('discGenre');
        g.innerHTML = '';
        g.appendChild(label('类型'));
        g.appendChild(chipBtn('全部', () => { if (d.genre) { d.genre = ''; reload(); } }, d.genre === '' ? 'on' : ''));
        DISC_GENRES.forEach(x => g.appendChild(chipBtn(x, () => {
            d.genre = d.genre === x ? '' : x;
            reload();
        }, d.genre === x ? 'on' : '')));

        // 地区
        const c = document.getElementById('discCountry');
        c.innerHTML = '';
        c.appendChild(label('地区'));
        c.appendChild(chipBtn('全部', () => { if (d.country) { d.country = ''; reload(); } }, d.country === '' ? 'on' : ''));
        DISC_COUNTRIES.forEach(x => c.appendChild(chipBtn(x, () => {
            d.country = d.country === x ? '' : x;
            reload();
        }, d.country === x ? 'on' : '')));

        // 自定义标签（多选叠加，如：治愈 / 烧脑 / 高智商）
        const t = document.getElementById('discTags');
        t.innerHTML = '';
        t.appendChild(label('标签'));
        getUserTags().forEach(tag => {
            const selected = d.tags.includes(tag);
            t.appendChild(chipBtn(tag, () => {
                if (d.delMode) {
                    saveUserTags(getUserTags().filter(x => x !== tag));
                    const wasSelected = d.tags.includes(tag);
                    d.tags = d.tags.filter(x => x !== tag);
                    if (!getUserTags().length) d.delMode = false;
                    renderDiscover();
                    setTimeout(() => setFocus(document.querySelector('#discTags .chip')), 60);
                    if (wasSelected) { d.pageStart = 0; loadDiscover(); }
                    toast('已删除标签：' + tag);
                    return;
                }
                d.tags = selected ? d.tags.filter(x => x !== tag) : d.tags.concat(tag);
                reload();
            }, d.delMode ? 'warn' : (selected ? 'on' : ''), d.delMode ? 'close' : null));
        });
        t.appendChild(chipBtn('添加标签', async () => {
            const tag = await promptGate.open('输入自定义标签（如：治愈 / 烧脑 / 高分；中文可用物理键盘输入）');
            if (!tag) return;
            const tags = getUserTags();
            if (tags.includes(tag)) { toast('标签已存在'); return; }
            tags.push(tag);
            saveUserTags(tags);
            d.tags.push(tag);   // 添加即选中参与筛选
            reload();
            toast('已添加标签：' + tag);
        }, null, 'add'));
        if (getUserTags().length) {
            t.appendChild(chipBtn(d.delMode ? '完成' : '删除标签', () => {
                d.delMode = !d.delMode;
                renderDiscover();
                setTimeout(() => setFocus(document.querySelector('#discTags .chip')), 60);
                if (d.delMode) toast('点击标签即可删除');
            }, d.delMode ? 'warn' : '', d.delMode ? 'check' : 'del'));
        }

        // 首次进入自动加载
        if (!document.getElementById('discResults').children.length) loadDiscover();
    }

    // 追加式加载：append=true 时把下一批 20 条接到网格尾部（焦点滚到底自动触发）。
    // 防累计卡顿：按 id 去重、数量硬上限、CSS content-visibility 跳过屏外渲染。
    const DISC_MAX_ITEMS = 240;

    async function loadDiscover(append) {
        const d = state.discover;
        const box = document.getElementById('discResults');
        if (d.loading) return;
        d.loading = true;
        if (!append) {
            d.pageStart = 0;
            d.done = false;
            d.seen = new Set();
            document.getElementById('viewDiscover').scrollTop = 0;   // 新筛选从头看
            showLoading('加载豆瓣推荐…');
        }
        try {
            const tagsParam = [d.form, ...d.tags].join(',');
            const url =
                `https://movie.douban.com/j/new_search_subjects?sort=${d.sort}&range=0,10` +
                `&tags=${encodeURIComponent(tagsParam)}` +
                (d.genre ? `&genres=${encodeURIComponent(d.genre)}` : '') +
                (d.country ? `&countries=${encodeURIComponent(d.country)}` : '') +
                `&start=${d.pageStart}`;
            const data = await fetchDoubanData(url);
            // 去重（豆瓣分页偶有条目重叠）
            const subs = ((data && data.data) || []).filter(it => {
                const key = it.id || it.title;
                if (!key || d.seen.has(key)) return false;
                d.seen.add(key);
                return true;
            });
            if (!append) box.innerHTML = '';
            if (!subs.length) {
                d.done = true;
                if (!append) {
                    box.innerHTML = `<div style="grid-column:1/-1;color:var(--text-dim);padding:2vw">该筛选组合暂无内容，换个条件试试</div>`;
                } else toast('已加载全部内容');
                return;
            }
            d.pageStart += 20;
            subs.forEach(item => box.appendChild(makeCard(item, true)));
            if (box.children.length >= DISC_MAX_ITEMS) {
                d.done = true;
                toast(`已加载 ${box.children.length} 条，建议用筛选缩小范围`);
            }
        } catch (e) {
            if (!append) {
                box.innerHTML = `<div style="grid-column:1/-1;color:var(--text-dim);padding:2vw">加载失败（网络/代理问题），换个条件或稍后再试</div>`;
            } else toast('加载更多失败，稍后再试');
        } finally {
            d.loading = false;
            if (!append) hideLoading();
        }
    }

    // ============================================================
    //  10. 设置页（采集源 / 自定义源 / 开关 / 历史与配置）
    // ============================================================
    function renderSettings() {
        renderSrcChips();
        renderCustomChips();
        renderToggleChips();
        renderDataChips();
    }

    function renderSrcChips() {
        const sel = new Set(getSelected());
        const entries = Object.entries(window.API_SITES || {}).filter(([k]) => k !== 'custom');
        const act = document.getElementById('srcActions');
        act.innerHTML = '';
        act.appendChild(chipBtn('全选普通源', () => {
            const normal = entries.filter(([, s]) => !s.adult).map(([k]) => k);
            setSelected(normal.concat(getSelected().filter(k => k.startsWith('custom_'))));
            renderSettings();
        }, null, 'check'));
        act.appendChild(chipBtn('全不选', () => {
            setSelected([]);
            renderSettings();
        }, null, 'close'));

        const box = document.getElementById('srcChips');
        box.innerHTML = '';
        entries.forEach(([key, site]) => {
            if (site.adult && (typeof HIDE_BUILTIN_ADULT_APIS !== 'undefined') && HIDE_BUILTIN_ADULT_APIS) return;
            box.appendChild(chipBtn(
                site.name + (site.adult ? ' ⚠18+' : ''),
                () => toggleSource(key),
                sel.has(key) ? 'on' : '',
                sel.has(key) ? 'check' : null));
        });
        document.getElementById('srcCount').textContent = `（已选 ${getSources().length} 个）`;
    }

    function toggleSource(key) {
        const sel = getSelected();
        setSelected(sel.includes(key) ? sel.filter(k => k !== key) : sel.concat(key));
        syncYellowFilterWithAdult();
        renderSettings();
        setTimeout(() => {   // 重渲染后焦点回到该源
            const target = [...document.querySelectorAll('#srcChips .chip, #customChips .chip')]
                .find(c => c.textContent.trim().startsWith(
                    key.startsWith('custom_') ? (getCustomAPIs()[+key.slice(7)] || {}).name || '' : API_SITES[key].name));
            if (target) setFocus(target);
        }, 60);
    }

    let customDelMode = false;
    function renderCustomChips() {
        const box = document.getElementById('customChips');
        box.innerHTML = '';
        const sel = new Set(getSelected());
        const customs = getCustomAPIs();
        customs.forEach((api, i) => {
            const key = 'custom_' + i;
            box.appendChild(chipBtn(
                api.name + (api.isAdult ? ' ⚠18+' : ''),
                () => {
                    if (customDelMode) {
                        removeCustomSource(i);
                        toast('已删除：' + api.name);
                        return;
                    }
                    toggleSource(key);
                },
                customDelMode ? 'warn' : (sel.has(key) ? 'on' : ''),
                customDelMode ? 'close' : (sel.has(key) ? 'check' : null)));
        });
        box.appendChild(chipBtn('添加自定义源', addCustomSource, null, 'add'));
        if (customs.length) {
            box.appendChild(chipBtn(customDelMode ? '完成' : '删除源', () => {
                customDelMode = !customDelMode;
                renderCustomChips();
                setTimeout(() => setFocus(document.querySelector('#customChips .chip')), 60);
            }, customDelMode ? 'warn' : '', customDelMode ? 'check' : 'del'));
        } else if (!customs.length && !customDelMode) {
            const hint = document.createElement('span');
            hint.className = 'chips-label';
            hint.textContent = '未添加自定义源';
            box.appendChild(hint);
        }
    }

    async function addCustomSource() {
        const max = (typeof CUSTOM_API_CONFIG !== 'undefined' && CUSTOM_API_CONFIG.maxSources) || 5;
        if (getCustomAPIs().length >= max) { toast(`最多支持 ${max} 个自定义源`); return; }
        const name = await promptGate.open('自定义源名称');
        if (!name) return;
        let url = await promptGate.open('API 地址（如 https://example.com/api.php/provide/vod）', { charset: 'url' });
        if (!url) return;
        url = url.replace(/\/+$/, '');
        if (!/^https?:\/\/.+/.test(url)) { toast('地址需以 http:// 或 https:// 开头'); return; }
        const isAdult = await optGate.open('该源的内容类型？', [
            { text: '普通资源站', value: false },
            { text: '成人资源站 ⚠', value: true, cls: 'warn' },
        ]);
        if (isAdult === null) return;
        const apis = getCustomAPIs();
        apis.push({ name, url, isAdult });
        store.set('customAPIs', apis);
        setSelected(getSelected().concat('custom_' + (apis.length - 1)));
        syncYellowFilterWithAdult();
        toast('已添加自定义源：' + name);
        renderSettings();
        setTimeout(() => setFocus(document.querySelector('#customChips .chip')), 60);
    }

    function removeCustomSource(idx) {
        const apis = getCustomAPIs();
        apis.splice(idx, 1);
        store.set('customAPIs', apis);
        // 删除后 custom_N 序号前移，重新映射 selectedAPIs
        const sel = [];
        getSelected().forEach(k => {
            if (!k.startsWith('custom_')) { sel.push(k); return; }
            const i = parseInt(k.slice(7), 10);
            if (i < idx) sel.push(k);
            else if (i > idx) sel.push('custom_' + (i - 1));
        });
        setSelected(sel);
        if (!apis.length) customDelMode = false;
        renderSettings();
        setTimeout(() => setFocus(document.querySelector('#customChips .chip')), 60);
    }

    function renderToggleChips() {
        const box = document.getElementById('toggleChips');
        box.innerHTML = '';
        const adult = hasAdultSelected();

        const yellowOn = localStorage.getItem('yellowFilterEnabled') === 'true';
        box.appendChild(chipBtn(`黄色内容过滤：${yellowOn ? '开' : '关'}`, () => {
            if (adult) { toast('已选择成人源，过滤功能不可用'); return; }
            localStorage.setItem('yellowFilterEnabled', yellowOn ? 'false' : 'true');
            renderToggleChips();
            setTimeout(() => setFocus(document.querySelector('#toggleChips .chip')), 60);
        }, (yellowOn ? 'on' : '') + (adult ? ' disabled' : '')));

        const adOn = localStorage.getItem(PLAYER_CONFIG.adFilteringStorage) !== 'false';
        box.appendChild(chipBtn(`分片广告过滤：${adOn ? '开' : '关'}`, () => {
            localStorage.setItem(PLAYER_CONFIG.adFilteringStorage, adOn ? 'false' : 'true');
            renderToggleChips();
            setTimeout(() => setFocus(document.querySelectorAll('#toggleChips .chip')[1]), 60);
        }, adOn ? 'on' : ''));

        const dbOn = localStorage.getItem('doubanEnabled') !== 'false';
        box.appendChild(chipBtn(`首页豆瓣推荐：${dbOn ? '开' : '关'}`, () => {
            localStorage.setItem('doubanEnabled', dbOn ? 'false' : 'true');
            state.homeStale = true;
            renderToggleChips();
            setTimeout(() => setFocus(document.querySelectorAll('#toggleChips .chip')[2]), 60);
        }, dbOn ? 'on' : ''));
    }

    function renderDataChips() {
        const box = document.getElementById('dataChips');
        box.innerHTML = '';
        box.appendChild(chipBtn('清空观看历史', async () => {
            const ok = await optGate.open('确定清空全部观看历史？', [
                { text: '取消', value: null },
                { text: '清空', value: true, cls: 'warn' },
            ]);
            if (!ok) return;
            localStorage.removeItem('viewingHistory');
            toast('观看历史已清空');
        }, 'warn', 'del'));
        box.appendChild(chipBtn('清空搜索历史', () => {
            localStorage.removeItem(SEARCH_HISTORY_KEY);
            renderSearchHistoryChips();
            toast('搜索历史已清空');
        }, 'warn', 'del'));
        box.appendChild(chipBtn('导出配置', exportConfig, null, 'download'));
        box.appendChild(chipBtn('从 URL 导入配置', importConfigFromUrl, null, 'upload'));
        box.appendChild(chipBtn('导入配置文件', () => {
            const input = document.getElementById('importFileInput');
            if (input) input.click();
        }, null, 'upload'));
    }

    // ---------- 配置导入 / 导出（格式与上游 app.js 完全兼容） ----------
    async function sha256Hex(str) {
        if (window._jsSha256) return window._jsSha256(str);
        return await window.sha256(str);
    }

    async function exportConfig() {
        const items = {};
        ['selectedAPIs', 'customAPIs', 'yellowFilterEnabled', 'adFilteringEnabled',
         'doubanEnabled', 'hasInitializedDefaults', 'viewingHistory', SEARCH_HISTORY_KEY]
            .forEach(key => {
                const v = localStorage.getItem(key);
                if (v !== null) items[key] = v;
            });
        const times = Date.now().toString();
        const config = { name: 'Veliora-Settings', time: times, cfgVer: '1.0.0', data: items };
        config.hash = await sha256Hex(JSON.stringify(config.data));
        const blob = new Blob([JSON.stringify(config)], { type: 'text/plain;charset=utf-8' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'Veliora-Settings_' + times + '.json';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
        toast('配置已导出');
    }

    async function applyImportedConfig(config) {
        // 兼容改名前导出的 LibreTV-Settings 备份
        if (!config || !['Veliora-Settings', 'LibreTV-Settings'].includes(config.name)) throw new Error('配置文件格式不正确');
        const dataHash = await sha256Hex(JSON.stringify(config.data));
        if (dataHash !== config.hash) throw new Error('配置文件哈希值不匹配');
        for (const item in config.data) localStorage.setItem(item, config.data[item]);
        toast('配置导入成功，即将刷新页面');
        setTimeout(() => window.location.reload(), 1500);
    }

    async function importConfigFromUrl() {
        const url = await promptGate.open('配置文件 URL', { charset: 'url' });
        if (!url) return;
        showLoading('正在导入配置…');
        try {
            const res = await fetch(url, { mode: 'cors', headers: { 'Accept': 'application/json' } });
            if (!res.ok) throw new Error('获取配置文件失败');
            await applyImportedConfig(await res.json());
        } catch (e) {
            toast('导入失败：' + e.message);
        } finally { hideLoading(); }
    }

    function bindImportFile() {
        const input = document.getElementById('importFileInput');
        if (!input) return;
        input.addEventListener('change', async () => {
            const file = input.files && input.files[0];
            input.value = '';
            if (!file) return;
            try {
                if (file.size > 1024 * 1024 * 10) throw new Error('文件大小超过 10MB');
                const text = await file.text();
                await applyImportedConfig(JSON.parse(text));
            } catch (e) {
                toast('导入失败：' + (e.message || '文件格式错误'));
            }
        });
    }

    // ============================================================
    //  11. 免责声明（首次使用展示，hasSeenDisclaimer 与上游同键）
    // ============================================================
    async function maybeShowDisclaimer() {
        if (localStorage.getItem('hasSeenDisclaimer')) return;
        await optGate.open(`
            <div class="disclaimer-text">
                <h3>使用声明</h3>
                <p><b>服务性质：</b>Veliora 仅提供视频搜索服务，不直接提供、存储或上传任何视频内容，所有搜索结果均来自第三方公开接口。</p>
                <p><b>用户责任：</b>使用本服务时须遵守相关法律法规，不得利用搜索结果从事侵权行为。</p>
                <p><b>内容过滤：</b>可在「设置」中开启黄色内容过滤与广告过滤。</p>
            </div>`,
            [{ text: '我已阅读并同意', value: true }],
            { dismissable: false });
        localStorage.setItem('hasSeenDisclaimer', 'true');
    }

    // ============================================================
    //  12. 密码门（遥控器可操作，复用 password.js）
    // ============================================================
    const pwGate = {
        active: false,
        value: '',
        onKey() {},
        open() {
            if (!window.isPasswordProtected || !isPasswordProtected()) return;
            if (isPasswordVerified && isPasswordVerified()) return;
            this.active = true;
            const c = document.getElementById('loading');
            overlayEl = c;
            c.classList.remove('hidden');
            c.innerHTML = `
                <div style="font-size:1.6vw;font-weight:800">需要访问密码</div>
                <div class="search-box" id="pwBox" style="min-width:40vw;text-align:center">
                    <span class="cursor">|</span></div>
                <div class="tv-keyboard" id="pwKb" style="max-width:60vw"></div>`;
            buildPwKeyboard();
            setTimeout(() => setFocus(document.querySelector('#pwKb .key')), 50);
            this.onKey = (e) => {
                switch (e.key) {
                    case 'ArrowUp': e.preventDefault(); navigate('up'); break;
                    case 'ArrowDown': e.preventDefault(); navigate('down'); break;
                    case 'ArrowLeft': e.preventDefault(); navigate('left'); break;
                    case 'ArrowRight': e.preventDefault(); navigate('right'); break;
                    case 'Enter': e.preventDefault(); if (current) current.click(); break;
                    case 'Backspace': case 'Escape':
                        e.preventDefault(); this.value = this.value.slice(0, -1); this.render(); break;
                }
            };
        },
        render() {
            const b = document.getElementById('pwBox');
            if (b) b.innerHTML = (this.value ? '•'.repeat(this.value.length) : '') + '<span class="cursor">|</span>';
        },
        async submit() {
            const ok = await verifyPassword(this.value);
            if (ok) {
                this.active = false;
                overlayEl = null;
                document.dispatchEvent(new CustomEvent('passwordVerified'));
                const c = document.getElementById('loading');
                c.classList.add('hidden');
                c.innerHTML = LOADING_HTML;   // 还原 spinner 结构，showLoading 才有 #loadingMsg 可用
                toast('验证成功');
                boot();
            } else { this.value = ''; this.render(); toast('密码错误'); }
        }
    };
    function buildPwKeyboard() {
        const kb = document.getElementById('pwKb');
        kb.innerHTML = '';
        [...'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'].forEach(k => {
            const el = document.createElement('div');
            el.className = 'key focusable';
            el.textContent = k;
            el.onclick = () => { pwGate.value += k.toLowerCase(); pwGate.render(); };
            // 允许大小写：默认小写，长按无意义；密码多为小写/数字，够用
            kb.appendChild(el);
        });
        kb.appendChild(keyBtn('', 'key focusable', () => { pwGate.value = pwGate.value.slice(0, -1); pwGate.render(); }, 'backspace'));
        kb.appendChild(keyBtn('确定', 'key focusable wide act', () => pwGate.submit(), 'check'));
    }

    function ensureVerified() {
        if (window.isPasswordProtected && isPasswordProtected() &&
            !(isPasswordVerified && isPasswordVerified())) {
            pwGate.open();
            return false;
        }
        return true;
    }

    // ============================================================
    //  13. 工具：加载 / 提示 / 时钟
    // ============================================================
    // 注意：#loading 容器被密码门借用过（pwGate.open 会把里面的 spinner 结构整体换成密码键盘），
    // 验证成功前 #loadingMsg 是不存在的。这里必须容错重建，否则 showLoading 抛 TypeError，
    // 会把调用它的搜索/发现/详情整条流程静默中断（此前搜索点了没反应就是这个原因）。
    const LOADING_HTML = '<div class="tv-spinner"></div><div class="msg" id="loadingMsg">加载中…</div>';
    function showLoading(msg) {
        if (pwGate.active) return;                 // 密码门正开着，别把它盖掉
        const c = document.getElementById('loading');
        if (!c) return;
        let m = document.getElementById('loadingMsg');
        if (!m) { c.innerHTML = LOADING_HTML; m = document.getElementById('loadingMsg'); }
        if (m) m.textContent = msg || '加载中…';
        c.classList.remove('hidden');
    }
    function hideLoading() {
        if (pwGate.active) return;
        const c = document.getElementById('loading');
        if (c) c.classList.add('hidden');
    }

    let toastTimer;
    function toast(msg) {
        const t = document.getElementById('toast');
        t.textContent = msg;
        t.classList.add('show');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
    }

    function tickClock() {
        const d = new Date();
        const hh = String(d.getHours()).padStart(2, '0');
        const mm = String(d.getMinutes()).padStart(2, '0');
        document.getElementById('clock').textContent = `${hh}:${mm}`;
    }

    // ============================================================
    //  14. 顶部导航绑定 + 启动
    // ============================================================
    function bindNav() {
        document.querySelectorAll('#navMenu .focusable').forEach(btn => {
            btn.onclick = () => showView(btn.dataset.nav);
        });
    }

    function boot() {
        loadHome();
    }

    window.addEventListener('DOMContentLoaded', async () => {
        initDefaults();
        tickClock();
        setInterval(tickClock, 30000);
        bindNav();
        buildKeyboard();
        renderQuery();
        bindImportFile();
        const orderBtn = document.getElementById('btnEpOrder');
        if (orderBtn) orderBtn.onclick = toggleEpisodeOrder;
        const copyBtn = document.getElementById('btnCopyLinks');
        if (copyBtn) copyBtn.onclick = copyEpisodeLinks;
        setFocus(document.getElementById('heroPlay'), false);

        await maybeShowDisclaimer();

        // 首页豆瓣推荐用注入的密码哈希即可加载，直接展示（更像流媒体首屏）；
        // 真正需要鉴权的搜索 / 播放会在触发时按需弹出遥控器密码框。
        boot();
    });
})();
