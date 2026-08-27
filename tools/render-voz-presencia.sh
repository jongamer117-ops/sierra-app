#!/usr/bin/env bash
# Pre-renderiza las frases fijas de presencia con la voz real de Sierra.
# Correr en sierra-pc. Salida -> app/src/main/res/raw/
set -euo pipefail

PIPER=/home/jonathanf/piper-hud/venv/bin/piper
VOZ=/home/jonathanf/piper-hud/voces/es_MX-cortanav3-high.onnx
OUT="$(dirname "$0")/../app/src/main/res/raw"
mkdir -p "$OUT"

render() {
  echo "$2" | "$PIPER" --model "$VOZ" --output_file "/tmp/$1.wav"
  ffmpeg -y -loglevel error -i "/tmp/$1.wav" -c:a libvorbis -q:a 3 "$OUT/$1.ogg"
  rm -f "/tmp/$1.wav"
}

render voz_quieta          "Acá estoy."
render voz_escuchando      "Te oigo."
render voz_pensando        "Dame un segundo."
render voz_en_cola         "Lo mandé al PC."
render voz_esperando_si    "Hay un sí esperando."
render voz_lista           "Hecho."
render voz_corta_sin_pc    "No llego al PC. Revisá Tailscale."
render voz_corta_hermes    "Estoy en Hermes. Puedo hablar. No puedo mover nada."
render voz_corta_sin_token "Me falta el token. Está en Ajustes."
render voz_hora            "Voy por la hora."
render voz_firefox         "Firefox va."
render voz_encolado        "Lo encolé."
render voz_ejecutado       "Listo. Mirá el PC."
render voz_fallo           "No salió."
render voz_timeout         "Tardé demasiado. No sé si llegó."

echo "Listo: $(ls -1 "$OUT"/voz_*.ogg | wc -l) frases en $OUT"
