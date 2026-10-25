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
