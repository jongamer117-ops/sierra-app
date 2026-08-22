# Sierra Voice App

App Android (Kotlin) que habla con **Sierra** en `sierra-pc` a través de la
IP de Tailscale de la PC. Usa el `SpeechRecognizer` nativo de Android para
voz → texto (sin librerías externas de STT), llama al endpoint `/comando`
del backend, y opcionalmente lee la respuesta en voz alta con el
`TextToSpeech` nativo.

## Estructura

- `MainActivity`: botón de micrófono, campo de transcripción editable,
  botón "Enviar", área de respuesta, switch de lectura en voz alta.
- `SettingsActivity`: IP, puerto y token del servidor (persistidos en
  `SharedPreferences` vía `SierraPrefs`), porque el backend todavía no
  está desplegado y esos valores pueden cambiar.
- `network/SierraApiClient`: cliente OkHttp para `POST /comando`.

## Permisos

- `INTERNET`: para hablar con Sierra por HTTP.
- `RECORD_AUDIO`: para el micrófono. Se pide en runtime al tocar el botón
  de hablar (Android 6+).

## Conectividad

La IP por defecto es `100.86.158.55` (Tailscale). El celular necesita la
app de Tailscale de Android instalada y conectada a la misma red para que
esa IP sea alcanzable. Como Sierra corre sin TLS dentro de la VPN, la app
permite tráfico HTTP en claro (`network_security_config.xml`); no está
pensada para hablar con servidores fuera de la red de Tailscale.

## Contrato asumido para `/comando`

El mensaje original con el JSON exacto del backend llegó sin el contenido
de los bloques de código (se perdió en el copiado). Mientras se termina de
construir el servidor, la app asume esto y es fácil de ajustar en un solo
lugar (`SierraApiClient.parseComando`):

**Request**
```
POST http://<ip>:<puerto>/comando
Content-Type: application/json
X-Sierra-Token-Poco: <token>   (si está configurado)

{ "texto": "lo que dijo el usuario" }
```

**Response esperada (200)** — se acepta cualquiera de estos campos para el
mensaje: `respuesta`, `mensaje`, `message`, `texto`. Opcionalmente un
booleano `matched`.
```json
{ "respuesta": "texto de la respuesta", "matched": true }
```

**Si no matchea nada** — se acepta un campo `error`, o `matched: false`:
```json
{ "error": "no encontré ningún comando" }
```

Si el backend termina usando otros nombres de campo, solo hay que tocar
`parseComando()` en `SierraApiClient.kt` — el resto de la app no depende
del shape exacto del JSON.

## Build

Requiere Android Studio (o Android SDK + `dl.google.com` accesible, ya que
el plugin de Android y las libs de androidx/material se descargan de ahí).
En este sandbox de desarrollo `dl.google.com` está bloqueado por política
de red, así que el proyecto **no se compiló localmente**; el código fue
revisado a mano. Para compilar:

```
./gradlew assembleDebug
```

## Pendiente del lado del servidor

- Endpoint `/comando` en sierra-pc (texto → GLM + keywords → Canal A).
- Token `X-Sierra-Token-Poco`.

Cuando eso esté listo, avisar para ajustar `SettingsActivity` (valores por
defecto) y `parseComando()` si el JSON real difiere de lo asumido acá.
