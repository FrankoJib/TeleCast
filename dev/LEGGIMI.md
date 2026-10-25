# Banco di prova

Simula un sito (con Referer obbligatorio, segmento "travestito" da PNG e HLS cifrato), una TV LG DLNA e una ok. Google TV (Chromecast) e prova il motore `core/` su JVM.

```bash
# 1. sito + LG finta
python3 dev/fake_world.py &
# 2. Chromecast finto (serve un certificato qualsiasi)
openssl req -x509 -newkey rsa:2048 -nodes -keyout /tmp/k.pem -out /tmp/c.pem -days 30 -subj "/CN=cast"
python3 dev/fake_cast.py /tmp/c.pem /tmp/k.pem &
# 3. compila ed esegui i test (Kotlin 2.x)
kotlinc app/src/main/java/it/telecast/core/*.kt dev/CoreTest.kt -d /tmp/out
kotlin -cp /tmp/out test.CoreTestKt
```

## Blocco pubblicità

```bash
# motore: formati degli elenchi, aggiornamento con fonti di riserva, regole su reindirizzamenti e pop-up
kotlinc app/src/main/java/it/telecast/core/*.kt dev/AdFilterTest.kt -d /tmp/ad
# facoltativo: prova anche con gli elenchi veri (pro.txt e popup.txt in una cartella)
curl -o /tmp/l/pro.txt --create-dirs https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt
curl -o /tmp/l/popup.txt https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt
kotlin -cp /tmp/ad testad.AdFilterTestKt /tmp/l
```

Script iniettati nelle pagine (link toccati, strati trasparenti, riquadri pubblicitari), provati in Chromium con Playwright:

```bash
# estrae gli script dall'app compilata (serve android.jar per compilare BrowserActivity)
kotlinc -cp android.jar app/src/main/java dev/DumpScripts.kt -d /tmp/js
kotlin -cp /tmp/js:android.jar DumpScriptsKt /tmp/js
node dev/shield_test.js /tmp/js
```
