# LocalDrop roadmap por fases

## Fase 1 — robustez de red y publicación (actual)

- [x] Build, tests y lint en CI.
- [x] Publicación automática de APK debug con checksum.
- [x] Limpieza de ServerSocket, NSD y coroutines.
- [x] Reinicio automático del stack local al cambiar la red.
- [x] Validación de IP privada y parser QR versionado.

## Fase 2 — pairing autenticado (en progreso)

- [x] Identidad persistente por dispositivo usando Android Keystore.
- [x] Clave pública y fingerprint visible en el QR y en solicitudes.
- [x] Sesiones de subida firmadas con `SHA256withECDSA`.
- [x] Un dispositivo emparejado exige clave pública, fingerprint y firma válidos.
- [ ] Confirmación explícita de pairing desde ambos dispositivos.
- [ ] Nonce de desafío firmado por sesión de recepción.
- [ ] UI para revocar dispositivos emparejados.
- [ ] No aceptar automáticamente dispositivos solo descubiertos.

## Fase 3 — transferencias resilientes (en progreso)

- [ ] Sesión multiarchivo con una sola solicitud y consentimiento.
- [x] Cola persistente en Room; las tareas sobreviven al cierre del proceso.
- [x] Recuperación de tareas que quedaron `RUNNING` al reiniciar.
- [x] Reintentos controlados con backoff y máximo de tres intentos.
- [ ] Reanudación por offsets.
- [ ] Recepción dentro del Foreground Service.
- [ ] Recuperación de una transferencia activa después de cambios de red.

## Fase 4 — release de producción

- [ ] Keystore de release fuera del repositorio.
- [ ] Secrets de GitHub para firmar APK/AAB.
- [ ] Release estable firmada al crear tags.
- [ ] Verificación de firma y checksum en CI.

## Fase 5 — calidad de producto

- [ ] Tests instrumentados de NSD, SAF, MediaStore y dos dispositivos.
- [ ] Tema claro/oscuro/sistema.
- [ ] UI optimizada para tablet y horizontal.
- [ ] Métricas locales de transferencia sin datos externos.
