# LocalDrop para Linux

Cliente de escritorio ligero para enviar archivos desde Linux a LocalDrop Android usando únicamente la red local.

## Características

- GUI con Tkinter.
- Pairing mediante el QR generado por LocalDrop Android.
- Confirmación mutua con nonce y firmas ECDSA.
- Envío multiarchivo.
- Fragmentos de 1 MiB.
- Reanudación usando offsets del receptor.
- SHA-256 por archivo.
- La clave privada se guarda en `~/.local/share/localdrop-linux/identity.pem` con permisos `0600`.

## Instalación

Requiere Python 3.10+, Tkinter y `cryptography`:

```bash
sudo apt install python3-tk
python3 -m pip install -r requirements.txt
python3 localdrop_linux.py
```

En Fedora, instala `python3-tkinter`; en Arch Linux, `tk`.

## Uso

1. Abre la pantalla QR de LocalDrop en Android.
2. Copia el contenido del QR o usa una herramienta de lectura QR.
3. Pega el texto `localdrop://...` en la aplicación Linux.
4. Pulsa **Emparejar**.
5. Selecciona archivos y pulsa **Enviar**.

Este primer cliente Linux funciona como emisor hacia Android. El receptor Linux puede agregarse después sin cambiar el protocolo.
