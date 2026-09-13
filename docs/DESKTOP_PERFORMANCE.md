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
| Non applicabile come semplice tuning | GC incrementale | `components/script/script_runtime.rs`: commento esplicito sulle pre-barriere non corrette | Richiede prima un intervento di correttezza nel motore |
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

## GC JavaScript: correzione dopo l'approfondimento

Il GC incrementale non è un candidato da abilitare tramite preferenza.
`components/script/script_runtime.rs`, subito prima di impostare
`JSGC_INCREMENTAL_GC_ENABLED`, dichiara: “Pre-barriers aren't implemented correctly
at the moment, so this preference defaults to false.” La disabilitazione ha quindi
una ragione di correttezza, non una semplice scelta di prestazioni.

L'indicazione iniziale di provarne l'abilitazione viene ritirata. Prima servono
pre-barriere corrette, revisione del tracing e verifiche dedicate nel motore.
Per ridurre pause GC nell'immediato, profilare le allocazioni del gioco e valutare
il riuso di buffer/oggetti nei percorsi caldi. Il GC per zona è un parametro
separato e non risolve il problema delle pre-barriere.

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

## Approfondimento: percorso effettivo del frame

Il percorso osservato è:

1. `TimerRefreshDriver` pianifica una callback; `BaseRefreshDriver` risveglia il loop.
2. `RunningAppState::spin_event_loop` esegue il lavoro Servo e aggiorna le richieste delle finestre.
3. `Painter::needs_repaint` verifica sia le ragioni di repaint sia `wait_to_paint` del refresh driver.
4. La shell richiede un redraw; il gestore headed esegue `Gui::update`.
5. `ServoShellWindow::repaint_webviews` invoca `WebView::paint`, poi `Paint::render` e `Painter::render`.
6. WebRender aggiorna e renderizza la scena; il callback offscreen compone il risultato nel parent egui.
7. `Gui::paint` presenta il parent.

Non tutte queste operazioni si eseguono immediatamente alla scadenza del timer:
coda eventi, messaggi, disponibilità del frame e attesa dello swap si interpongono.
Questo spiega perché il timer da 8 ms non basta a stimare FPS o latenza input.
`Paint::handle_messages` deduplica già `NewWebRenderFrameReady` per painter:
aggiungere una seconda deduplicazione senza misure probabilmente non aiuta.

### Repaint della GUI contro repaint del contenuto

`WebView::paint` chiama il rendering senza un controllo locale del dirty state.
`Paint::render` inoltra direttamente a `Painter::render`. Quindi un redraw
richiesto da un overlay può arrivare anche al renderer della pagina. Non dimostra
che WebRender ricostruisca ogni volta la scena: `renderer.update()` e
`renderer.render()` sono distinti dal lavoro di scene building.

Esperimento più circoscritto del bypass totale egui: tenere separati il dirty
state dell'overlay e quello del contenuto, ricomponendo il framebuffer esistente
quando cambia solo l'overlay. Misurare con pagina statica e dialogo animato.
Questo richiede preservare resize, context loss, screenshot, metriche di paint
e tick delle animazioni: saltare semplicemente `webview.paint()` può impedire
la corretta progressione del refresh driver, notificato dentro `Painter::render`.

## Approfondimento: il caricamento a blocchi non è interamente asincrono

`components/net/filemanager_thread.rs::fetch_file_in_chunks` usa `spawn_task`
che, in `components/net/async_runtime.rs`, chiama `Handle::spawn` su Tokio.
Dentro il task il reader è però `std::io::BufReader<std::fs::File>` e
`reader.fill_buf()` è una lettura sincrona. `yield_now().await` avviene dopo
il blocco, non rende asincrona la lettura che lo precede.

Oltre all'estrazione dei pack, l'I/O a cache fredda può quindi occupare i worker
Tokio usati da altri task. Distinguere almeno tre misure: attesa mutex ed
estrazione; apertura/metadata; trasferimento dei blocchi. Valutare I/O async
appropriato o un pool bloccante dedicato e limitato. Non dedurre dal nome
`spawn_blocking_task` del wrapper che usi `tokio::spawn_blocking`: quel wrapper
chiama `block_on` e non è una soluzione pronta da riutilizzare.

### Allocazioni e copie per blocco

Il blocco nominale è 32 KiB (`FILE_CHUNK_SIZE = 32768`). Il loader:

- copia il buffer di `fill_buf()` tramite `.to_vec()`;
- copia il chunk nel `ResponseBody::Receiving` tramite `extend_from_slice`;
- crea un'altra copia per `Data::Payload(chunk.to_vec())`;
- invia il payload attraverso un canale non limitato.

Evidenza: queste copie sono presenti nel codice. Ipotesi: possono incidere sui
caricamenti grandi; il costo reale rispetto a decodifica immagini/audio e upload
GPU non è ancora noto. Il body conserva la risposta mentre il canale può avere
payload in coda: la memoria di picco va misurata, non stimata come un solo blocco.

Esperimenti separati: eliminare la copia temporanea di `fill_buf()` mantenendo
gli ownership corretti; preallocare il body con limite quando la dimensione è
nota; verificare se il protocollo consente payload condivisi invece di copie;
misurare la necessità di backpressure con un consumatore lento. Un canale
limitato richiede adeguare producer/consumer e cancellazione, non solo cambiare
il costruttore. Preservare range HTTP, EOF, file modificati durante il caricamento
e propagazione degli errori: l'attuale `fill_buf().unwrap()` merita anche una
revisione di robustezza indipendente dalle prestazioni.

## Approfondimento: contenuti, cache e concorrenza

`ensure_pack_extracted` usa un marker per saltare pack già estratti. L'estrazione
legge uno stream zstd/tar: non carica deliberatamente tutto l'archivio in RAM.
La ricerca file→pack usa `manifest.files.get`, mentre il pack viene cercato
linearmente nella lista. Indicizzare anche i pack è possibile ma ha priorità
bassa: normalmente decompressione e scritture dominano quella ricerca.

Prima di parallelizzare, controllare che due pack non scrivano percorsi comuni
e coordinare la cancellazione con `clear_content_cache`. Marker, invalidazione
tramite hash del contenuto e aggiornamenti del gioco devono restare coerenti.
Un file già presente può essere osservato mentre si estrae: un futuro design
parallelo deve definire quando diventa leggibile, usando staging/commit dove
necessario. Limitare la concorrenza per evitare saturazione disco e picchi RAM.

## Approfondimento: finestra nascosta e consumi

`WebView::set_throttled` è disponibile e l'integrazione EGL lo utilizza.
Nella shell desktop esaminata non è stato trovato un uso di `set_throttled`, né
un gestore funzionale di `WindowEvent::Occluded` collegato a quell'API; il nome
dell'evento compare nel tracing. Questo è un gap d'integrazione da verificare
con una finestra minimizzata, non la prova che ogni piattaforma continui a
renderizzare a pieno ritmo: il window manager e Servo possono introdurre altre
limitazioni.

Esperimento: misurare CPU/GPU e tick rAF con app visibile, minimizzata, coperta
e semplicemente senza focus. Se necessario, collegare minimizzazione/occlusione
al throttling, ripristinandolo al ritorno. Non usare automaticamente perdita di
focus come pausa: un gioco visibile su un altro monitor può dover continuare.
Definire comportamento per audio, multiplayer, input e avanzamento simulazione.
Questo intervento punta a consumo e disponibilità CPU, non ad aumentare gli FPS
mentre il gioco è visibile.

## Approfondimento: WebGL, parallelismo e log

### Query WebGL sincrone

In `components/script/dom/webgl/webglrenderingcontext.rs`, `Finish`, diverse
query `GetParameter` e `DrawingBufferWidth/Height` inviano un comando e aspettano
`receiver.recv()`. Altre proprietà, come alcuni limiti hardware, sono già
restituite da campi locali. Non tutte le query attraversano il canale.

Per un gioco che interroga frequentemente stato/risultati, questi round trip
possono diventare un costo CPU/di sincronizzazione. Tracciare numero e durata
prima di intervenire. Nel gioco, memorizzare i valori stabili e aggiornare le
dimensioni in risposta ai resize; evitare `finish()` nei frame normali. Nel
motore, un'eventuale cache deve rispettare stato, resize e context loss.
Non trasformare query sincrone in risultati obsoleti per ottenere FPS maggiori.

### Pool WebRender

`Painter::new` limita i worker WebRender al minimo tra parallelismo disponibile
e `thread_pool_webrender_workers_max`, che per default vale 4. Il metodo di upload
texture distingue già ANGLE (`Immediate`) dagli altri renderer (`PixelBuffer`).
Non aumentare i worker al numero di core indiscriminatamente: scene building
può migliorare mentre JS, decoder e Tokio competono per gli stessi core.
Provare 2/4/8 worker solo se i profili mostrano scene building CPU limitato;
valutare anche macchine con pochi core. Lasciare invariato il metodo di upload
senza tracce GPU/driver che giustifichino una variante.

### Logging

`desktop/logging.rs` configura un file e il livello predefinito `info`.
Il controllo speciale degli errori di caricamento prende il mutex solo su
specifici record `Error`, non per ogni messaggio: non è un lock globale del frame.
I log ad alta frequenza del gioco o del motore possono invece falsare le misure.
Confrontare logging normale e `RUST_LOG=warn` in un test controllato, conservando
una modalità diagnostica; non eliminare gli errori per inseguire le prestazioni.

## Ordine operativo rivisto

| Ordine | Lavoro | Ambito | Criterio prima di implementare |
|---|---|---|---|
| 1 | Baseline e attribuzione CPU/GPU/I/O | Strumenti e gioco | Tracce riproducibili, cache e risoluzione fissate |
| 2 | Refresh monitor/vsync | Shell e integrazione Servo | Confermare ritmo rAF e attese present su 60/144+ Hz |
| 3 | I/O bloccante e copie dei blocchi | Servo net e loader Roves | Confermare worker occupati e memoria di picco nei caricamenti |
| 4 | Prefetch e pack per livello | Packer/loader e gioco | Scatti correlati alla prima estrazione |
| 5 | Throttling quando nascosto | Shell desktop | Consumi anomali minimizzato, comportamento audio definito |
| 6 | Repaint overlay separato | Shell e renderer | Costo contenuto ripetuto su scene statiche con overlay |
| 7 | Profili, worker e risoluzione | Build/configurazione | Benchmark specifici CPU o GPU limitati |
| Escluso come tuning | Abilitazione GC incrementale | Correttezza SpiderMonkey/Servo | Pre-barriere corrette prima di qualsiasi abilitazione |

Le migliori prime patch non sono necessariamente quelle con il maggior numero
di flag. La scelta deve seguire il collo di bottiglia del gioco rappresentativo.
Questa revisione estende e corregge l'analisi statica; non contiene benchmark
runtime né stime percentuali di miglioramento. Non cambia codice di produzione.
