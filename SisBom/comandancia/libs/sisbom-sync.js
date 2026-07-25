// sisbom-sync.js - Shared Synchronization and Caching Layer for AppUnificada
(function() {
    const isParent = window.location.pathname.endsWith('index.html');
    const isCentral = window.location.pathname.includes('/central/');
    
    // Module-specific collection lists to prevent unauthorized read attempts (Permission Denied)
    const SYNCED_COLLECTIONS = isCentral 
        ? ['personal', 'vehiculos', 'bitacora', 'despachos', 'alertas', 'sirena', 'prueba_radial']
        : ['personal', 'vehiculos', 'alertas', 'asistencia', 'despachos', 'bitacora', 'prueba_radial', 'sirena'];

    const channel = new BroadcastChannel('sisbom_sync');
    const debounceTimeouts = new Map();

    function getCollectionPath(collectionRef) {
        if (!collectionRef) return '';
        if (collectionRef.path) {
            return collectionRef.path;
        }
        if (collectionRef._query && collectionRef._query.path) {
            return collectionRef._query.path.segments.join('/');
        }
        return '';
    }

    function createMockSnapshot(dataArray) {
        const docs = dataArray.map(item => ({
            id: item.idRegistro || item.ID || item.id,
            data() { return { ...item }; }
        }));
        return {
            docs: docs,
            size: dataArray.length,
            empty: dataArray.length === 0,
            forEach(callback) {
                docs.forEach(callback);
            }
        };
    }

    // Debounced function to save collection updates to local physical disk (.json files)
    function queueDiskSave(collPath, dataArray) {
        if (window.pywebview && window.pywebview.api && window.pywebview.api.save_local_db) {
            if (debounceTimeouts.has(collPath)) {
                clearTimeout(debounceTimeouts.get(collPath));
            }
            
            const tid = setTimeout(() => {
                debounceTimeouts.delete(collPath);
                const pathPrefix = isCentral ? 'central/' : 'comandancia/';
                const fileName = pathPrefix + 'db_' + collPath + '.json';
                const contentStr = JSON.stringify(dataArray);
                
                window.pywebview.api.save_local_db(fileName, contentStr)
                    .then(success => {
                        if (success) {
                            console.log("SisBom Sync: Disk save successful for: " + fileName);
                        } else {
                            console.warn("SisBom Sync: Disk save failed for: " + fileName);
                        }
                    })
                    .catch(err => {
                        console.error("SisBom Sync: Disk save exception for: " + fileName, err);
                    });
            }, 500);
            
            debounceTimeouts.set(collPath, tid);
        }
    }

    if (isParent) {
        console.log("SisBom Sync: Parent window initialized (" + (isCentral ? "Central" : "Comandancia") + ").");
        const activeListeners = new Set();
        let realFbOnSnapshot = null;

        // Load cached databases from local disk (.json) files into LocalStorage immediately when Python API is ready
        const loadInitialDiskCaches = () => {
            if (window.pywebview && window.pywebview.api && window.pywebview.api.load_local_db) {
                console.log("SisBom Sync: Loading local database caches from disk...");
                SYNCED_COLLECTIONS.forEach(coll => {
                    const pathPrefix = isCentral ? 'central/' : 'comandancia/';
                    const fileName = pathPrefix + 'db_' + coll + '.json';
                    
                    window.pywebview.api.load_local_db(fileName)
                        .then(contentStr => {
                            if (contentStr) {
                                localStorage.setItem('sisbom_db_' + coll, contentStr);
                                console.log("SisBom Sync: Successfully loaded cache from disk for: " + fileName);
                            }
                        })
                        .catch(err => {
                            console.error("SisBom Sync: Error loading cache from disk for: " + fileName, err);
                        });
                });
            }
        };

        if (window.pywebview && window.pywebview.api) {
            loadInitialDiskCaches();
        } else {
            window.addEventListener('pywebviewready', loadInitialDiskCaches);
        }
        
        Object.defineProperty(window, 'fbOnSnapshot', {
            get() {
                return function(collectionRef, callback, errorCallback) {
                    const collPath = getCollectionPath(collectionRef);
                    const isSynced = SYNCED_COLLECTIONS.includes(collPath);
                    
                    if (isSynced) {
                        activeListeners.add(collPath);
                    }
                    
                    if (isSynced && realFbOnSnapshot) {
                        return realFbOnSnapshot(collectionRef, (snap) => {
                            const dataArray = [];
                            snap.forEach(doc => {
                                const docData = doc.data();
                                docData.id = doc.id;
                                docData.ID = doc.id;
                                docData.idRegistro = doc.id;
                                dataArray.push(docData);
                            });
                            
                            // Print if the update came from Local Cache or Server
                            const fromCache = snap.metadata ? snap.metadata.fromCache : false;
                            console.log("SisBom Sync: Firestore update for '" + collPath + "' (fromCache: " + fromCache + ", documents: " + dataArray.length + ")");

                            // Save to LocalStorage (for child windows)
                            localStorage.setItem('sisbom_db_' + collPath, JSON.stringify(dataArray));
                            
                            // Broadcast to other windows
                            channel.postMessage({ collection: collPath, data: dataArray });
                            
                            // Save to local physical disk (.json) file
                            queueDiskSave(collPath, dataArray);
                            
                            // Call original callback
                            if (callback) callback(snap);
                        }, (error) => {
                            console.warn("SisBom Sync: Listener error for " + collPath + ":", error.message);
                            if (errorCallback) errorCallback(error);
                        });
                    }
                    
                    return realFbOnSnapshot ? realFbOnSnapshot(collectionRef, callback, errorCallback) : null;
                };
            },
            set(val) {
                if (val && val !== window.fbOnSnapshot) {
                    realFbOnSnapshot = val;
                }
            },
            configurable: true
        });

        // Background listeners disabled by user request to prevent "read storms" and preserve local draft lists
        console.log("SisBom Sync: Background sync listeners disabled. Operating in local cache + write mode.");

    } else {
        // Child window mode
        const activeCallbacks = new Map();
        
        // Listen to BroadcastChannel for real-time updates from parent
        channel.onmessage = (event) => {
            const { collection, data } = event.data;
            if (collection) {
                localStorage.setItem('sisbom_db_' + collection, JSON.stringify(data));
                
                // If there are callbacks, trigger them
                if (activeCallbacks.has(collection)) {
                    const callbacks = activeCallbacks.get(collection);
                    const mockSnap = createMockSnapshot(data);
                    callbacks.forEach(cb => {
                        try { cb(mockSnap); } catch(e) { console.error("Error in synced callback:", e); }
                    });
                }
                
                // Real-time refresh for child pages
                if (window.location.pathname.endsWith('estadisticas.html')) {
                    if (window.DB) {
                        let dataChanged = false;
                        if (collection === 'personal') { window.DB.personal = data; dataChanged = true; }
                        else if (collection === 'vehiculos') { window.DB.vehiculos = data; dataChanged = true; }
                        
                        if (dataChanged) {
                            if (typeof window.refreshDashboard === 'function') {
                                window.refreshDashboard();
                            }
                        } else if (['despachos', 'bitacora', 'prueba_radial', 'sirena', 'asistencia'].includes(collection)) {
                            if (typeof window.fetchAllData === 'function') {
                                window.fetchAllData(false).catch(e => {});
                            }
                        }
                    }
                }
            }
        };

        // Intercept fbOnSnapshot in child windows (Virtual local-only listener)
        let realFbOnSnapshot = null;
        Object.defineProperty(window, 'fbOnSnapshot', {
            get() {
                return function(collectionRef, callback) {
                    const collPath = getCollectionPath(collectionRef);
                    const isSynced = SYNCED_COLLECTIONS.includes(collPath);

                    if (isSynced) {
                        // Register callback
                        if (!activeCallbacks.has(collPath)) {
                            activeCallbacks.set(collPath, []);
                        }
                        activeCallbacks.get(collPath).push(callback);

                        // Load initial data from LocalStorage
                        const cachedDataStr = localStorage.getItem('sisbom_db_' + collPath);
                        if (cachedDataStr) {
                            try {
                                const cachedData = JSON.parse(cachedDataStr);
                                setTimeout(() => {
                                    callback(createMockSnapshot(cachedData));
                                }, 0);
                            } catch(e) {
                                console.error("Error loading cached data for:", collPath, e);
                            }
                        }

                        // Return virtual unsubscribe function
                        return function unsubscribe() {
                            const callbacks = activeCallbacks.get(collPath) || [];
                            const idx = callbacks.indexOf(callback);
                            if (idx !== -1) {
                                callbacks.splice(idx, 1);
                            }
                        };
                    }

                    // Fallback to real fbOnSnapshot for other queries (subcollections)
                    return realFbOnSnapshot ? realFbOnSnapshot(collectionRef, callback) : null;
                };
            },
            set(val) {
                if (val && val !== window.fbOnSnapshot) {
                    realFbOnSnapshot = val;
                }
            },
            configurable: true
        });

        // Intercept fbGetDocs in child windows (Virtual local-only getter)
        let realFbGetDocs = null;
        Object.defineProperty(window, 'fbGetDocs', {
            get() {
                return async function(collectionRef) {
                    const collPath = getCollectionPath(collectionRef);
                    const isSynced = SYNCED_COLLECTIONS.includes(collPath);

                    if (isSynced) {
                        const cachedDataStr = localStorage.getItem('sisbom_db_' + collPath);
                        if (cachedDataStr) {
                            try {
                                const cachedData = JSON.parse(cachedDataStr);
                                return createMockSnapshot(cachedData);
                            } catch(e) {}
                        }
                        return createMockSnapshot([]);
                    }

                    return realFbGetDocs ? await realFbGetDocs(collectionRef) : createMockSnapshot([]);
                };
            },
            set(val) {
                if (val && val !== window.fbGetDocs) {
                    realFbGetDocs = val;
                }
            },
            configurable: true
        });
    }
})();
