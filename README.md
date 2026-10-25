# TeleCast

App Android che manda alle tue TV i video dei siti web **senza mirroring**: la TV scarica e riproduce il video da sola, il telefono fa da telecomando (e puoi bloccarlo).

Funziona con:
- **TV DLNA** come le **LG webOS** (e la maggior parte delle smart TV Samsung, Sony, Philips…);
- **TV con Chromecast integrato** come le **ok. Google TV** (e ogni Google TV / Android TV o chiavetta Chromecast).

## Installazione

Serve compilare l'APK una volta. Due strade:

**A) Android Studio (PC o Mac)**
1. Apri la cartella `TeleCast` con *File → Open* e aspetta la sincronizzazione di Gradle.
2. Collega il telefono via USB con il *Debug USB* attivo e premi ▶ *Run*.
   In alternativa: *Build → Build APK(s)*, poi copia `app/build/outputs/apk/debug/app-debug.apk` sul telefono e aprilo.

**B) GitHub, senza installare nulla**
1. Crea un repository e carica tutto il contenuto della cartella `TeleCast` (compresa la cartella `.github`).
2. Parte da sola la compilazione "Compila APK" (scheda *Actions*, circa 5-8 minuti).
3. Dal telefono apri la pagina **Releases** del repository e tocca **TeleCast.apk** per scaricarlo e installarlo.

Gli aggiornamenti si installano sopra la versione precedente (la chiave di firma è inclusa nel progetto).

## Uso

1. In Chrome apri la pagina del film e tocca **Condividi → TeleCast** (oppure naviga direttamente nell'app).
2. Fai partire il video nella pagina: il pulsante in basso diventa giallo con il numero dei video trovati.
3. Tocca **Trasmetti**, scegli il video e poi **dove guardarlo**: in cima c'è l'ultima TV usata, sotto le altre trovate.
4. Durante la visione, nel telecomando, **Cambia TV** sposta il film su un'altra TV dal punto in cui sei.

Se una TV non compare: deve essere accesa e sulla stessa Wi-Fi (non quella "ospiti"); puoi anche inserire il suo IP a mano.

## Blocco pubblicità

Il browser di TeleCast blocca da solo pubblicità, pop-up e pagine spam:
- **oltre 200.000 siti pubblicitari** (elenchi [HaGeZi](https://github.com/hagezi/dns-blocklists) Multi PRO + Pop-Up Ads, scaricati dall'app e aggiornati ogni 4 giorni; sui dati mobili solo se mancano);
- **reindirizzamenti** verso altri siti che non partono da un link toccato davvero (il classico "tocchi Play e si apre un sito spam"), anche dagli strati trasparenti messi sopra il video;
- **pop-up** verso altri siti, finestre "Hai vinto!", richieste di permessi, download di app e link che aprono altre app.

Lo **scudo** in alto mostra quante cose ha bloccato sulla pagina. Se qualcosa che serviva viene bloccato, tocca **Apri** nell'avviso (o lo scudo → *Bloccati di recente*). Se un sito non funziona, scudo → **Disattiva qui** (solo per quel sito).

Per i siti con tanti pop-up conviene aprirli direttamente in TeleCast invece che da Chrome.

## Se qualcosa non va

- **Il video non parte sulla LG**: l'app prova da sola più modalità (flusso unico "compatibilità", poi HLS nativo). Dal telecomando puoi forzare **Prova l'altra modalità**, oppure spostarlo sull'altra TV.
- **Si ferma a schermo spento**: menu ⋮ → *Funziona a schermo spento*.
- **Video senza audio**: alcune LG non decodificano l'audio AC3/DTS di certi file; con l'HLS dei siti di solito non succede.
- **Log diagnostico** (menu ⋮ o icona nel telecomando): copialo e incollalo quando chiedi aiuto.

Limiti: non funziona con i servizi protetti da DRM (Netflix, Prime Video, Disney+…) né con i video che la pagina non scarica davvero.

## Come funziona

```
Chrome ──Condividi──▶ TeleCast (browser interno)
                        │ intercetta .m3u8 / .mp4 + Referer, cookie, User-Agent
                        ▼
            proxy HTTP sul telefono :8765 ◀────────── le TV scaricano da qui
                        │                          ▲                ▲
                        ▼                          │ DLNA (SOAP)    │ Google Cast v2 (TLS :8009)
                  sito del video               TV LG webOS     ok. Google TV (ricevitore Google)
```

- `core/` (Kotlin puro, testato su JVM con una LG e un Chromecast simulati): scoperta SSDP e mDNS, comandi DLNA, protocollo Cast v2, proxy HTTP con Range e CORS, riscrittura HLS, unione dei segmenti in MPEG-TS, tentativi automatici, spostamento tra TV.
- `core/AdFilter.kt`: blocco pubblicità (elenco compatto di hash, aggiornamento con fonti di riserva, regole contro reindirizzamenti e pop-up).
- `BrowserActivity`, `Shield`, `DevicePicker`, `RemoteActivity`, `CastService`: interfaccia, blocco pubblicità, scelta della TV, telecomando e servizio in primo piano.
- `dev/`: banco di prova (sito, LG e Chromecast finti in Python + test Kotlin e JavaScript), istruzioni in `dev/LEGGIMI.md`.

Icone: Material Design Icons (Apache 2.0). Elenchi pubblicitari: HaGeZi DNS Blocklists (GPL-3.0), scaricati dall'app al primo avvio e non inclusi nel progetto.
