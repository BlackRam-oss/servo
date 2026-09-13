# Analisi delle prestazioni desktop di Roves

Analisi statica di `main`, commit `82f179787fdf088da81d27a8d45f4cd2dfdbf6e9`.
Branch: `perf/desktop-analysis`, creato direttamente da quel commit.
Le modifiche mobile non sono incluse. Non sono disponibili benchmark eseguiti:
le opportunità indicate sono ipotesi da misurare, non miglioramenti dimostrati.

## Risultato

Sì, ci sono opportunità concrete. Le prime verifiche dovrebbero riguardare
la sincronizzazione delle animazioni con il monitor, il costo della composizione
della shell e gli scatti dovuti al primo accesso ai contenuti compressi.
Le ottimizzazioni del compilatore sono una pista secondaria, da confrontare
con un benchmark: la release attuale è già ottimizzata.

| Priorità | Opportunità | Evidenza nel codice | Effetto atteso da verificare |
|---|---|---|---|
| Alta | Refresh driver desktop legato al monitor/vsync | `components/paint/refresh_driver.rs`, `TimerRefreshDriver::observe_next_frame`: `Duration::from_millis(1000 / 120)` | Migliore regolarità dei frame e meno lavoro superfluo sui monitor lenti |
| Alta | Misurare un percorso di rendering senza egui quando non ci sono overlay | `desktop/gui.rs`: `Gui::update` chiama `repaint_webviews` e inserisce un callback di composizione; `Gui::paint` presenta la GUI | Ridurre lavoro CPU della shell e copie GPU, soprattutto a risoluzioni alte |
| Alta per giochi con streaming | Spostare decompressione e I/O bloccante fuori dal percorso di caricamento | `protocols/game.rs::load` chiama `ensure_available` prima di restituire il future; `protocols/packed_content.rs` usa un mutex unico | Ridurre picchi di latenza al primo caricamento di livelli/audio/texture |
| Alta per giochi con molte allocazioni JS | Valutare GC incrementale | `components/config/prefs.rs`: `js_mem_gc_incremental_enabled = false`; applicato in `components/script/script_runtime.rs` | Ridurre pause lunghe del GC, con possibile costo sul throughput |
| Media | Confrontare release, production e un profilo orientato alla velocità con ThinLTO | `Cargo.toml`: production usa `opt-level = "s"`, LTO e un codegen unit; release workflow usa `--release` | Possibile vantaggio CPU; costo di compilazione e dimensioni da misurare |
| Media per GPU limitate | Risoluzione interna configurabile per il contenuto del gioco | Viewport fisico dipendente dalla scala HiDPI in `desktop/gui.rs` e `headed_window.rs` | Ridurre il carico GPU con compromesso sulla qualità |
| Bassa, solo avvio | Ridurre/rendere opzionale il tempo minimo della splash | `desktop/app.rs`: `MIN_SPLASH_DURATION = 500 ms`, condizione in `try_finish_booting` | Avvio caldo potenzialmente più rapido, nessun beneficio sugli FPS |

I percorsi `desktop/...` e `protocols/...` della tabella sono relativi a
`ports/servoshell/`. Il content packer è in `support/content-packer/`.

## 1. Ritmo dei frame

`1000 / 120` è una divisione intera: il timer richiede 8 ms, nominalmente 125
scadenze al secondo, non 120. Questo non dimostra che il gioco produca 125 FPS:
la coda eventi, il rendering e la presentazione possono limitare la frequenza.
Il timer però non segue il refresh reale di un monitor a 60, 144 o 165 Hz.

Servo supporta già un `RefreshDriver` personalizzato (`BaseRefreshDriver::new`).
Non è stata trovata un'implementazione desktop equivalente a quella OHOS nella
shell esaminata. Prima di intervenire, registrare tempi dei callback rAF,
rendering e presentazione e verificare come il backend limita lo swap.

Esperimento: confrontare il driver attuale con un driver sincronizzato al display.
Un intervallo calcolato con precisione superiore corregge l'arrotondamento ma
non sostituisce il vsync. Evitare un limite universale a 60 FPS: penalizzerebbe
i monitor con refresh elevato. Gestire cambio monitor, minimizzazione e pausa.

## 2. Composizione della shell

Ogni redraw normale entra in `Gui::update` e `Gui::paint` tramite
`HeadedWindow::handle_window_event`. La toolbar è nascosta, ma resta il percorso
egui: aggiornamento, rendering WebView, callback e presentazione del parent.
`components/shared/paint/rendering_context.rs::render_to_parent_callback`
usa `blit_framebuffer`, che esegue anche un clear del rettangolo destinazione.

Non presumere un doppio swap del monitor: `window.rs::repaint_webviews` chiama
`present()` sul contesto WebView, ma il contesto offscreen ha `present()` vuoto.
La presentazione finale avviene nel parent. Questo va distinto dal costo del blit.

Esperimento: misurare separatamente aggiornamento GUI, paint WebView, blit e
present. Solo se il costo è rilevante, aggiungere un percorso diretto per una
singola WebView senza dialoghi/overlay, ripristinando il percorso GUI quando serve.
Preservare accessibilità, input, ridimensionamento, splash e schermate di errore.
Non eliminare il clear senza verificare copertura, alpha e stato OpenGL.

## 3. Contenuti compressi e scatti

`GameProtocolHandler::load` fa controlli filesystem, eventuale estrazione e
apertura del file prima di restituire il future. `PackedContent::ensure_available`
serializza l'estrazione con un solo mutex. Il primo file richiesto può richiedere
l'estrazione dell'intero pack, attraverso `ensure_file_available` del content packer.
Questo può occupare il thread che invoca il loader; non è dimostrato che sia
sempre il thread UI. Distinguere questo caso dagli scatti del motore JS/GPU.

Esperimento: confrontare una build non compressa con cache fredda e calda della
build compressa, sullo stesso contenuto. Misurare richieste e decompressione.
Se confermato: prefetch del livello successivo, pack più piccoli e separati per
livello, estrazione tramite worker con deduplicazione per pack e cancellazione.
Un semplice mutex per pack permette più concorrenza ma non rende l'I/O asincrono.
Non aggiungere una cache RAM indiscriminata: il filesystem è già cacheato dal SO
ed una seconda copia può aumentare la memoria dei giochi con texture grandi.

## GC JavaScript

Il GC incrementale e quello per zona sono disabilitati nelle preferenze
predefinite; `components/script/script_runtime.rs` applica queste impostazioni
a SpiderMonkey. Questo è un candidato specifico per giochi che allocano molto,
non la prova che il GC sia il collo di bottiglia. Le impostazioni possono essere
sovrascritte al lancio: verificare i valori effettivi.

Esperimento separato: abilitare il GC incrementale mantenendo le altre opzioni
invariate, misurando pause GC, p99 dei frame, throughput e memoria. Valutare il GC
per zona in un secondo confronto. Verificare perché queste scelte upstream sono
disabilitate e la compatibilità con integrazione/tracing di Servo prima di
cambiare il default. Non promettere meno scatti basandosi solo sul nome della
preferenza e non cambiare contemporaneamente heap, scheduling e compilazione.

## 4. Profili di compilazione

`.github/workflows/release.yml` usa `./mach build --release`, non production.
`production` eredita release ma sceglie `opt-level = "s"`: è una scelta orientata
alla dimensione. Il profilo `profiling` mantiene simboli e abilita ThinLTO.
Non cambiare globalmente production da `s` a `3` senza confrontare giochi reali.

Matrice suggerita: release attuale; production attuale; profilo sperimentale
con `opt-level = 3`, `lto = "thin"`, `codegen-units = 1`. Confrontare anche
compilazione e dimensioni. PGO è un secondo passo, solo con workload rappresentativi.
Non usare `target-cpu=native` per gli eseguibili pubblici: limiterebbe la compatibilità.
Le opzioni Rust non assicurano gli stessi cambiamenti per SpiderMonkey o altre
librerie C/C++: verificare separatamente la loro compilazione.

Fonte primaria: [Cargo profiles](https://doc.rust-lang.org/cargo/reference/profiles.html)
e [rustc codegen options](https://doc.rust-lang.org/rustc/codegen-options/index.html).

## 5. Cosa è già corretto

- Il loop desktop termina in `ControlFlow::Wait` o `WaitUntil` (`desktop/app.rs::set_running_control_flow`): non emerge un busy loop universale da correggere.
- JIT baseline, Ion e compilazione off-thread sono già abilitati nelle preferenze predefinite (`components/config/prefs.rs`); abilitare nuovamente il JIT non è un'ottimizzazione.
- Il manifest dei pack è caricato da `PackedContent::resolve`, non riletto ad ogni richiesta.
- Il repaint della WebView attiva è già separato dalle altre WebView (`window.rs`).

## Piano di misurazione riproducibile

Usare Windows, macOS e Linux con GPU/driver documentati. Almeno un monitor a
60 Hz e uno a refresh elevato, risoluzione e scala HiDPI fisse. Usare la stessa
build del gioco e gli stessi percorsi d'input. Test-page offre Pixi/Three/audio
come smoke test; aggiungere un gioco reale prima di trarre conclusioni.

Workload: pagina statica; scena 2D animata con sprite; scena 3D WebGL; DOM animato;
caricamento di un livello con molti asset; avvio caldo e freddo; minimizzazione.
Separare scenari CPU e GPU limitati. Disattivare log diagnostici e profiler nelle
misure finali; usarli solo per attribuire il costo e ripetere poi senza strumenti.

Dopo un warm-up di 30 secondi, registrare 60 secondi per almeno cinque run
alternando baseline e candidato. Riportare intervalli rAF p50/p95/p99, frame
oltre il budget del monitor, CPU, GPU, RSS e tempi di caricamento/avvio. I tempi
rAF non misurano direttamente la presentazione sullo schermo: affiancare strumenti
GPU/presentazione nativi. Considerare rumore, temperatura e cache OS.

La shell espone `--profiler-trace-path` in `ports/servoshell/prefs.rs` e un time
profiler: verificare la sintassi con l'help dell'eseguibile costruito. Per il codice
nativo usare il profilo profiling e strumenti di campionamento del sistema.
Attribuire JS, layout, rendering e I/O prima di scegliere la patch.

Accettare un candidato solo se il miglioramento supera la variabilità tra run,
non peggiora p99/memoria in modo significativo e supera le verifiche funzionali
(input, audio, fullscreen, accessibilità, resize, salvataggi). Registrare i numeri
nel branch. L'analisi attuale non modifica runtime o configurazione di release.
