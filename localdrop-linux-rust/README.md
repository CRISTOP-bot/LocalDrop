# localdrop-linux-rust

Implementación nativa en Rust del cliente Linux de LocalDrop. Mantiene el protocolo existente del cliente Android y del cliente C++:

- `GET /pair-challenge`
- `POST /pair-confirm`
- `POST /upload-chunk`
- mismos headers `X-LocalDrop-*`;
- mismo manifest de cuatro columnas separadas por TAB;
- Base64 URL-safe sin padding;
- ECDSA P-256 con SHA-256;
- SHA-256(DER SubjectPublicKeyInfo) como fingerprint;
- chunks de 1 MiB y offsets reanudables.

## Dependencias

Solo se requieren Rust/Cargo y las dependencias descargadas por Cargo. `reqwest` usa Rustls, por lo que no requiere OpenSSL del sistema.

```bash
sudo apt install cargo rustc
```

## Compilar

```bash
cargo build --release
```

Los binarios quedan en:

```text
target/release/localdrop-linux-rust
target/release/localdrop-linux-rust-gui
```

## Interfaz gráfica

La interfaz gráfica está inspirada en la app Android: muestra el QR, los archivos seleccionados, el dispositivo conectado, el estado de pairing y el botón de envío.

```bash
./target/release/localdrop-linux-rust-gui
```

Requiere un entorno gráfico Linux y soporte de portal XDG para el selector de archivos.

## Ejecutar CLI

Copia el contenido de un QR `localdrop://connect?...` de LocalDrop Android:

```bash
./target/release/localdrop-linux-rust \
  'localdrop://connect?...' archivo1.zip archivo2.jpg
```

El dispositivo Linux se identifica como `Linux-$USER`. La clave privada PKCS#8 PEM se guarda en:

```text
~/.local/share/localdrop-linux-rust/identity.pem
```

con permisos `0600` en sistemas Unix.

## Estructura

- `src/crypto.rs`: identidad, PEM, DER, Base64, fingerprint y ECDSA.
- `src/protocol.rs`: QR, manifest, hashing y lectura de chunks.
- `src/http.rs`: pairing y solicitudes HTTP.
- `src/transfer.rs`: transferencia multiarchivo reanudable.
- `src/error.rs`: errores tipados.
- `src/main.rs`: CLI y códigos de salida.

La implementación no es un wrapper del cliente C++; usa Rust, ownership y `Result` directamente.
