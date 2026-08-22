# Sierra — Espacio de trabajo holográfico

App Android (Kotlin) que te conecta con **Sierra**, una IA con su propio cuarto / workspace visual.

La interfaz está rediseñada para sentirse como el espacio de operaciones de una IA: pantalla holográfica central, consola de entrada y nodo de activación por voz. Todo sigue hablando con el backend en `sierra-pc` a través de la IP de Tailscale.

## Concepto del rediseño

- **Cuarto / Workspace de la IA**: la UI simula un entorno 3D-estético (paneles flotantes, glow, profundidad) donde Sierra “habita” y trabaja.
- Header con presencia activa.
- Pantalla holográfica grande para las respuestas.
- Consola de texto + micrófono como punto de interacción directa.

> Nota: un verdadero entorno 3D interactivo (modelo de habitación navegable) requeriría un motor 3D (Filament / OpenGL / WebGL). Esta versión entrega la estética y el espacio de trabajo listos; se puede evolucionar después.

## Estructura técnica (sin cambios de lógica)

- `MainActivity`: micrófono, transcripción editable, envío, área de respuesta, TTS.
- `SettingsActivity`: IP, puerto y token (SharedPreferences vía `SierraPrefs`).
- `network/SierraApiClient`: OkHttp → `POST /comando`.

## Permisos

- `INTERNET`
- `RECORD_AUDIO` (runtime)

## Conectividad

IP por defecto: `100.86.158.55` (Tailscale). El celular debe estar en la misma red Tailscale. Tráfico HTTP permitido solo dentro de la VPN (`network_security_config.xml`).

## Contrato `/comando`

**Request**
```
POST http://<ip>:<puerto>/comando
Content-Type: application/json
X-Sierra-Token-Poco: <token>   (opcional)

{ "texto": "lo que dijo el usuario" }
```

**Response** — acepta `respuesta` / `mensaje` / `message` / `texto` y opcionalmente `matched`.

## Build

```
./gradlew assembleDebug
```

Requiere Android Studio / SDK con acceso a `dl.google.com`.

## Branch de este rediseño

`redesign/ai-room-workspace`
