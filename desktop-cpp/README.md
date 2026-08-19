# LocalDrop para Linux en C++

Cliente de línea de comandos para Linux que se conecta con LocalDrop Android usando el protocolo local autenticado.

Incluye:

- C++17.
- CMake.
- libcurl para HTTP local.
- OpenSSL para claves EC P-256, firmas ECDSA y SHA-256.
- Pairing con desafío nonce y confirmación mutua.
- Envío multiarchivo por fragmentos de 1 MiB.
- Reanudación mediante offsets.

## Dependencias

Debian/Ubuntu:

```bash
sudo apt install build-essential cmake libcurl4-openssl-dev libssl-dev
```

## Compilar

```bash
cd desktop-cpp
cmake -S . -B build
cmake --build build -j
```

## Usar

Copia el texto `localdrop://...` del QR de Android y ejecuta:

```bash
./build/localdrop-linux-cpp 'localdrop://connect?...' archivo1.zip archivo2.jpg
```

La identidad privada se guarda en:

```text
~/.local/share/localdrop-linux-cpp/identity.pem
```

No se sube ninguna clave al repositorio.
