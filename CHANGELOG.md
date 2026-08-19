# LocalDrop 0.1.0

## MVP implementado

- NSD/mDNS para publicar y descubrir dispositivos LocalDrop en la Wi‑Fi.
- HTTP local con streaming de archivos, sin cargar archivos completos en memoria.
- Selección múltiple, recepción con consentimiento, SAF y protección contra duplicados.
- Room, historial, ajustes, QR, compartir desde Android, progreso, cancelación y notificación.

## Próxima iteración avanzada

- Cola persistente con WorkManager/Foreground Service para transferencias si la app pasa a segundo plano.
- Emparejamiento autenticado con claves públicas, hash SHA-256 negociado y reintentos por fragmentos.
- Transferencias multiarchivo en una sola sesión y selector de tema claro/oscuro/sistema.
