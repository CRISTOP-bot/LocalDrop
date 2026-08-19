# LocalDrop roadmap por fases

## Fase 1 — robustez de red y publicación (actual)

- [x] Build, tests y lint en CI.
- [x] Publicación automática de APK debug con checksum.
- [x] Limpieza de ServerSocket, NSD y coroutines.
- [x] Reinicio automático del stack local al cambiar la red.
- [x] Validación de IP privada y parser QR versionado.

## Fase 2 — pairing autenticado

- [ ] Identidad persistente por dispositivo usando Android Keystore.
- [ ] Clave pública y fingerprint visible.
- [ ] Confirmación explícita de pairing desde ambos dispositivos.
- [ ] Nonce firmado para autenticar cada sesión.
- [ ] No aceptar automáticamente dispositivos solo descubiertos.

## Fase 3 — transferencias resilientes

- [ ] Sesión multiarchivo con una sola solicitud y consentimiento.
- [ ] Cola persistente en Room.
- [ ] Reanudación por offsets y reintentos controlados.
- [ ] Recepción dentro del Foreground Service.
- [ ] Recuperación después de cambios de red.

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
