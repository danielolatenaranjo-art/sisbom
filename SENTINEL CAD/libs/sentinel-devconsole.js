// sentinel-devconsole.js - Sentinel CAD Developer Console & Security Layer
(function() {
    if (window.__sentinel_security_installed__) return;
    window.__sentinel_security_installed__ = true;

    // 1. Block right-click / context menu
    document.addEventListener('contextmenu', function(e) {
        e.preventDefault();
        return false;
    }, true);

    // 2. Intercept logs and errors
    const logs = [];
    const maxLogs = 500;
    window.__sentinel_logs__ = logs;

    const originalConsole = {
        log: console.log.bind(console),
        warn: console.warn.bind(console),
        error: console.error.bind(console),
        info: console.info ? console.info.bind(console) : console.log.bind(console)
    };

    function addLogEntry(type, args, stack = null) {
        const time = new Date().toLocaleTimeString('es-CL', { hour12: false });
        let text = "";
        try {
            text = Array.from(args).map(a => {
                if (a === null) return 'null';
                if (a === undefined) return 'undefined';
                if (typeof a === 'object') {
                    try { return JSON.stringify(a, null, 2); } catch(e) { return String(a); }
                }
                return String(a);
            }).join(' ');
        } catch(err) {
            text = String(args);
        }

        const entry = { id: Date.now() + Math.random(), type, text, time, stack };
        logs.push(entry);
        if (logs.length > maxLogs) logs.shift();

        // If console UI is currently open, render entry
        if (window.__sentinel_console_ui_active__) {
            renderConsoleEntries();
        }
    }

    console.log = function(...args) {
        addLogEntry('log', args);
        originalConsole.log(...args);
    };
    console.info = function(...args) {
        addLogEntry('info', args);
        originalConsole.info(...args);
    };
    console.warn = function(...args) {
        addLogEntry('warn', args);
        originalConsole.warn(...args);
    };
    console.error = function(...args) {
        const stack = (new Error()).stack;
        addLogEntry('error', args, stack);
        originalConsole.error(...args);
    };

    window.addEventListener('error', function(e) {
        const stack = e.error ? e.error.stack : (e.filename + ':' + e.lineno + ':' + e.colno);
        addLogEntry('error', [`[Uncaught Error]: ${e.message}`], stack);
    });

    window.addEventListener('unhandledrejection', function(e) {
        const reason = e.reason ? (e.reason.stack || e.reason.message || e.reason) : 'Promise Rejection';
        addLogEntry('error', [`[Unhandled Rejection]: ${reason}`]);
    });

    // 3. UI Console Modal
    let consoleModal = null;
    let currentFilter = 'all';
    let searchQuery = '';

    function createConsoleUI() {
        if (consoleModal) return;

        consoleModal = document.createElement('div');
        consoleModal.id = 'sentinel-dev-console-modal';
        consoleModal.style.cssText = `
            position: fixed;
            top: 0;
            left: 0;
            width: 100vw;
            height: 100vh;
            background: rgba(4, 7, 18, 0.88);
            backdrop-filter: blur(12px);
            z-index: 99999999;
            display: none;
            flex-direction: column;
            box-sizing: border-box;
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
            color: #f1f5f9;
        `;

        consoleModal.innerHTML = `
            <div style="display:flex; flex-direction:column; height:100%; width:100%; max-width:1200px; margin:0 auto; padding:16px; box-sizing:border-box;">
                <!-- Header -->
                <div style="display:flex; align-items:center; justify-content:space-between; background:#0f172a; border:1px solid #1e293b; border-radius:12px; padding:12px 18px; margin-bottom:12px; box-shadow:0 8px 24px rgba(0,0,0,0.5);">
                    <div style="display:flex; align-items:center; gap:10px;">
                        <span style="display:inline-block; width:12px; height:12px; border-radius:50%; background:#ef4444; box-shadow:0 0 10px #ef4444;"></span>
                        <h2 style="margin:0; font-size:14px; font-weight:700; letter-spacing:0.05em; color:#fff; text-transform:uppercase;">SENTINEL CAD - Consola de Diagnóstico & Errores</h2>
                    </div>
                    <div style="display:flex; align-items:center; gap:8px;">
                        <button id="s-dev-btn-native" style="background:#1e293b; color:#94a3b8; border:1px solid #334155; border-radius:8px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer; display:flex; align-items:center; gap:6px;">
                            ⚙️ Abrir DevTools Nativo
                        </button>
                        <button id="s-dev-btn-copy" style="background:#1e293b; color:#94a3b8; border:1px solid #334155; border-radius:8px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer;">
                            📋 Copiar Todo
                        </button>
                        <button id="s-dev-btn-clear" style="background:#1e293b; color:#94a3b8; border:1px solid #334155; border-radius:8px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer;">
                            🗑️ Limpiar
                        </button>
                        <button id="s-dev-btn-close" style="background:#dc2626; color:#fff; border:none; border-radius:8px; padding:6px 14px; font-size:11px; font-weight:700; cursor:pointer;">
                            ✕ Cerrar (Esc)
                        </button>
                    </div>
                </div>

                <!-- Filters & Search -->
                <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:12px;">
                    <div style="display:flex; gap:6px;" id="s-dev-filter-tabs">
                        <button data-filter="all" style="background:#dc2626; color:#fff; border:none; border-radius:6px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer;">Todos (<span id="s-cnt-all">0</span>)</button>
                        <button data-filter="error" style="background:#1e293b; color:#94a3b8; border:1px solid #334155; border-radius:6px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer;">Errores (<span id="s-cnt-err">0</span>)</button>
                        <button data-filter="warn" style="background:#1e293b; color:#94a3b8; border:1px solid #334155; border-radius:6px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer;">Advertencias (<span id="s-cnt-warn">0</span>)</button>
                        <button data-filter="log" style="background:#1e293b; color:#94a3b8; border:1px solid #334155; border-radius:6px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer;">Logs (<span id="s-cnt-log">0</span>)</button>
                    </div>
                    <input id="s-dev-search" type="text" placeholder="Filtrar registros..." style="background:#090d16; border:1px solid #1e293b; border-radius:6px; padding:6px 12px; font-size:11px; color:#fff; width:220px; outline:none;" />
                </div>

                <!-- Log Entries List -->
                <div id="s-dev-log-container" style="flex:1; background:#080c16; border:1px solid #1e293b; border-radius:12px; padding:12px; overflow-y:auto; font-size:12px; line-height:1.5; box-shadow:inset 0 2px 10px rgba(0,0,0,0.6);">
                    <!-- Logs will be rendered here -->
                </div>

                <!-- Command / JS Evaluation Input -->
                <div style="display:flex; gap:8px; margin-top:12px; background:#0f172a; border:1px solid #1e293b; border-radius:10px; padding:8px 12px; align-items:center;">
                    <span style="color:#ef4444; font-weight:bold; font-size:14px;">&gt;</span>
                    <input id="s-dev-eval-input" type="text" placeholder="Ejecutar JavaScript (ej: localStorage, db_personal, pywebview.api.get_hwid())..." style="flex:1; background:transparent; border:none; color:#38bdf8; font-family:inherit; font-size:12px; outline:none;" />
                    <button id="s-dev-btn-run" style="background:#2563eb; color:#fff; border:none; border-radius:6px; padding:6px 12px; font-size:11px; font-weight:600; cursor:pointer;">
                        Ejecutar (Enter)
                    </button>
                </div>
            </div>
        `;

        document.body.appendChild(consoleModal);

        // Bind events
        document.getElementById('s-dev-btn-close').onclick = toggleConsoleUI;
        document.getElementById('s-dev-btn-clear').onclick = () => {
            logs.length = 0;
            renderConsoleEntries();
        };
        document.getElementById('s-dev-btn-copy').onclick = () => {
            const fullText = logs.map(l => `[${l.time}] [${l.type.toUpperCase()}] ${l.text} ${l.stack ? '\n' + l.stack : ''}`).join('\n\n');
            navigator.clipboard.writeText(fullText).then(() => {
                alert("Registros copiados al portapapeles.");
            }).catch(() => {
                alert("Error al copiar al portapapeles.");
            });
        };
        document.getElementById('s-dev-btn-native').onclick = () => {
            if (window.pywebview && window.pywebview.api && window.pywebview.api.open_devtools) {
                window.pywebview.api.open_devtools();
            } else {
                alert("DevTools nativo solo está disponible cuando se ejecuta en PyWebView.");
            }
        };

        // Filter tabs
        const tabs = document.querySelectorAll('#s-dev-filter-tabs button');
        tabs.forEach(tab => {
            tab.onclick = () => {
                currentFilter = tab.getAttribute('data-filter');
                tabs.forEach(t => {
                    t.style.background = '#1e293b';
                    t.style.color = '#94a3b8';
                    t.style.border = '1px solid #334155';
                });
                tab.style.background = '#dc2626';
                tab.style.color = '#fff';
                tab.style.border = 'none';
                renderConsoleEntries();
            };
        });

        // Search input
        document.getElementById('s-dev-search').oninput = (e) => {
            searchQuery = e.target.value.toLowerCase();
            renderConsoleEntries();
        };

        // Command eval
        const evalInput = document.getElementById('s-dev-eval-input');
        const runCmd = () => {
            const cmd = evalInput.value.trim();
            if (!cmd) return;
            addLogEntry('info', [`> ${cmd}`]);
            try {
                const result = window.eval(cmd);
                if (result instanceof Promise) {
                    result.then(res => {
                        addLogEntry('log', ['<Promise Resolved>:', res]);
                    }).catch(err => {
                        addLogEntry('error', ['<Promise Rejected>:', err]);
                    });
                } else {
                    addLogEntry('log', ['<Result>:', result]);
                }
            } catch(e) {
                addLogEntry('error', [`<Eval Error>: ${e.message}`], e.stack);
            }
            evalInput.value = '';
        };

        document.getElementById('s-dev-btn-run').onclick = runCmd;
        evalInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                runCmd();
            }
        });
    }

    function renderConsoleEntries() {
        if (!consoleModal) return;
        const container = document.getElementById('s-dev-log-container');
        if (!container) return;

        const countAll = logs.length;
        const countErr = logs.filter(l => l.type === 'error').length;
        const countWarn = logs.filter(l => l.type === 'warn').length;
        const countLog = logs.filter(l => l.type === 'log' || l.type === 'info').length;

        document.getElementById('s-cnt-all').innerText = countAll;
        document.getElementById('s-cnt-err').innerText = countErr;
        document.getElementById('s-cnt-warn').innerText = countWarn;
        document.getElementById('s-cnt-log').innerText = countLog;

        const filtered = logs.filter(l => {
            if (currentFilter === 'error' && l.type !== 'error') return false;
            if (currentFilter === 'warn' && l.type !== 'warn') return false;
            if (currentFilter === 'log' && l.type !== 'log' && l.type !== 'info') return false;
            if (searchQuery && !l.text.toLowerCase().includes(searchQuery)) return false;
            return true;
        });

        if (filtered.length === 0) {
            container.innerHTML = `<div style="color:#64748b; text-align:center; padding:30px; font-style:italic;">No hay registros para mostrar.</div>`;
            return;
        }

        container.innerHTML = filtered.map(l => {
            let badgeColor = '#38bdf8';
            let badgeBg = 'rgba(56, 189, 248, 0.15)';
            let borderColor = '#1e293b';

            if (l.type === 'error') {
                badgeColor = '#ef4444';
                badgeBg = 'rgba(239, 68, 68, 0.2)';
                borderColor = 'rgba(239, 68, 68, 0.3)';
            } else if (l.type === 'warn') {
                badgeColor = '#f59e0b';
                badgeBg = 'rgba(245, 158, 11, 0.2)';
                borderColor = 'rgba(245, 158, 11, 0.3)';
            }

            const escapedText = l.text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            const stackHtml = l.stack ? `<pre style="margin:4px 0 0 0; color:#ef4444; font-size:10px; opacity:0.85; white-space:pre-wrap;">${l.stack.replace(/</g, '&lt;')}</pre>` : '';

            return `
                <div style="padding:6px 8px; margin-bottom:4px; border-radius:6px; background:rgba(15, 23, 42, 0.6); border:1px solid ${borderColor}; display:flex; flex-direction:column; gap:2px;">
                    <div style="display:flex; align-items:flex-start; gap:8px;">
                        <span style="color:#64748b; font-size:10px; shrink-0;">[${l.time}]</span>
                        <span style="background:${badgeBg}; color:${badgeColor}; font-weight:bold; font-size:9px; padding:1px 5px; border-radius:4px; text-transform:uppercase;">${l.type}</span>
                        <div style="flex:1; white-space:pre-wrap; word-break:break-word; color:${l.type === 'error' ? '#fca5a5' : (l.type === 'warn' ? '#fde68a' : '#f1f5f9')}">${escapedText}</div>
                    </div>
                    ${stackHtml}
                </div>
            `;
        }).join('');

        container.scrollTop = container.scrollHeight;
    }

    function toggleConsoleUI() {
        createConsoleUI();
        if (consoleModal.style.display === 'flex') {
            consoleModal.style.display = 'none';
            window.__sentinel_console_ui_active__ = false;
        } else {
            consoleModal.style.display = 'flex';
            window.__sentinel_console_ui_active__ = true;
            renderConsoleEntries();
            const input = document.getElementById('s-dev-eval-input');
            if (input) input.focus();
        }
    }

    window.toggleSentinelDevConsole = toggleConsoleUI;

    // 4. Global Keyboard Shortcut Handler
    window.addEventListener('keydown', function(e) {
        // Developer Shortcuts:
        // 1) Ctrl + Shift + D
        // 2) Ctrl + Alt + C
        // 3) Ctrl + Shift + I
        // 4) F12
        const isCtrl = e.ctrlKey || e.metaKey;
        const isShift = e.shiftKey;
        const isAlt = e.altKey;
        const key = e.key.toUpperCase();

        const isDevTrigger = 
            (isCtrl && isShift && key === 'D') ||
            (isCtrl && isAlt && key === 'C') ||
            (isCtrl && isShift && key === 'I') ||
            (key === 'F12');

        if (isDevTrigger) {
            e.preventDefault();
            e.stopPropagation();

            // Try opening native DevTools if pywebview is available
            if (window.pywebview && window.pywebview.api && window.pywebview.api.open_devtools) {
                window.pywebview.api.open_devtools();
            }

            // Always toggle our rich in-page Developer Console UI
            toggleConsoleUI();
            return false;
        }

        // Allow and trigger full system reload on F5 or Ctrl+R (Unfreeze / Restart CAD)
        if (key === 'F5' || (isCtrl && key === 'R')) {
            try {
                if (window.top && window.top !== window) {
                    window.top.location.reload();
                } else {
                    window.location.reload();
                }
            } catch(err) {
                window.location.reload();
            }
            return;
        }

        // Close console on Escape if open
        if (key === 'ESCAPE' && window.__sentinel_console_ui_active__) {
            e.preventDefault();
            toggleConsoleUI();
            return false;
        }

        // Block other inspect/view-source keys (Ctrl+U, Ctrl+Shift+J, Ctrl+Shift+C)
        if (isCtrl && (key === 'U' || (isShift && (key === 'J' || key === 'C')))) {
            e.preventDefault();
            e.stopPropagation();
            return false;
        }
    }, true);

})();
