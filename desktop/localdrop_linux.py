#!/usr/bin/env python3
"""Cliente Linux ligero para LocalDrop: pairing por QR y envío por fragmentos."""
from __future__ import annotations
import base64, hashlib, json, os, queue, socket, threading, time, uuid
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, NoEncryption, PrivateFormat

CHUNK = 1024 * 1024
DATA = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "localdrop-linux"
KEY_FILE = DATA / "identity.pem"

def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")

def unb64(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))

def manifest(files):
    rows = []
    for path, digest in files:
        mime = "application/octet-stream"
        if path.suffix.lower() in {".jpg", ".jpeg"}: mime = "image/jpeg"
        elif path.suffix.lower() == ".png": mime = "image/png"
        elif path.suffix.lower() == ".pdf": mime = "application/pdf"
        rows.append("\t".join((b64(path.name.encode()), str(path.stat().st_size), b64(mime.encode()), b64(digest.encode()))))
    return "\n".join(rows)

class Identity:
    def __init__(self):
        DATA.mkdir(parents=True, exist_ok=True)
        if KEY_FILE.exists():
            self.key = serialization.load_pem_private_key(KEY_FILE.read_bytes(), password=None)
        else:
            self.key = ec.generate_private_key(ec.SECP256R1())
            KEY_FILE.write_bytes(self.key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
            os.chmod(KEY_FILE, 0o600)
        der = self.key.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
        self.public_key = b64(der)
        self.fingerprint = hashlib.sha256(der).hexdigest()

    def sign(self, text: str) -> str:
        return b64(self.key.sign(text.encode(), ec.ECDSA(hashes.SHA256())))

    @staticmethod
    def verify(public_key: str, text: str, signature: str) -> bool:
        try:
            key = serialization.load_der_public_key(unb64(public_key))
            key.verify(unb64(signature), text.encode(), ec.ECDSA(hashes.SHA256()))
            return hashlib.sha256(unb64(public_key)).hexdigest() == hashlib.sha256(key.public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).hexdigest()
        except Exception:
            return False

class LocalDropClient:
    def __init__(self, status):
        self.identity = Identity(); self.status = status; self.device = None

    def pair(self, qr: str):
        parsed = urlparse(qr.strip())
        if parsed.scheme != "localdrop" or parsed.hostname != "connect": raise ValueError("QR de LocalDrop inválido")
        q = {key: values[-1] for key, values in parse_qs(parsed.query).items()}
        host, port = q.get("host"), int(q.get("port", "0")); remote_pk, remote_fp = q.get("pk", ""), q.get("fp", "")
        if not host or not (1 <= port <= 65535) or len(remote_fp) != 64: raise ValueError("QR incompleto")
        response = urlopen(f"http://{host}:{port}/pair-challenge", timeout=8)
        nonce = response.headers.get("X-LocalDrop-Nonce", ""); signature = response.headers.get("X-LocalDrop-Signature", "")
        if response.headers.get("X-LocalDrop-Fingerprint") != remote_fp or not Identity.verify(remote_pk, nonce, signature): raise ValueError("No se pudo verificar la identidad Android")
        headers = {"X-LocalDrop-Nonce": nonce, "X-LocalDrop-Device-Id": socket.gethostname(), "X-LocalDrop-Device-Name": socket.gethostname(), "X-LocalDrop-Port": "0", "X-LocalDrop-Public-Key": self.identity.public_key, "X-LocalDrop-Fingerprint": self.identity.fingerprint, "X-LocalDrop-Signature": self.identity.sign(nonce)}
        request = Request(f"http://{host}:{port}/pair-confirm", data=b"", headers=headers, method="POST")
        with urlopen(request, timeout=8) as result:
            if result.status not in range(200, 300): raise ValueError("El dispositivo rechazó el pairing")
        self.device = (host, port, q.get("name", "Android"), remote_pk, remote_fp)

    def send(self, paths, progress):
        if not self.device: raise ValueError("Primero empareja un dispositivo")
        prepared = []
        for raw in paths:
            path = Path(raw); digestor = hashlib.sha256()
            with path.open("rb") as source:
                for block in iter(lambda: source.read(CHUNK), b""): digestor.update(block)
            prepared.append((path, digestor.hexdigest()))
        text = manifest(prepared); session = str(uuid.uuid4()); signature = self.identity.sign(session + "|" + text)
        host, port, _, _, _ = self.device; total = sum(p.stat().st_size for p, _ in prepared); completed = 0
        for index, (path, digest) in enumerate(prepared):
            size = path.stat().st_size; offset = 0
            while offset < size or size == 0:
                length = min(CHUNK, size - offset) if size else 0
                with path.open("rb") as source:
                    source.seek(offset); chunk = source.read(length)
                headers = {"Content-Type": "application/octet-stream", "X-LocalDrop-Session": session, "X-LocalDrop-Device-Id": socket.gethostname(), "X-LocalDrop-Device-Name": socket.gethostname(), "X-LocalDrop-Public-Key": self.identity.public_key, "X-LocalDrop-Fingerprint": self.identity.fingerprint, "X-LocalDrop-Signature": signature, "X-LocalDrop-Manifest": b64(text.encode()), "X-LocalDrop-Chunk": "1", "X-LocalDrop-File-Index": str(index), "X-LocalDrop-Offset": str(offset), "X-LocalDrop-File-Size": str(size)}
                request = Request(f"http://{host}:{port}/upload-chunk", data=chunk, headers=headers, method="POST")
                with urlopen(request, timeout=120) as result:
                    next_offset = int(result.headers.get("X-LocalDrop-Next-Offset", offset + len(chunk)))
                if next_offset <= offset or next_offset > size: raise ValueError("Offset inválido recibido del dispositivo")
                offset = next_offset; completed = sum(p.stat().st_size for p, _ in prepared[:index]) + offset; progress(completed, total, path.name)
                if size == 0: break

class App(tk.Tk):
    def __init__(self):
        super().__init__(); self.title("LocalDrop para Linux"); self.geometry("680x420"); self.client = LocalDropClient(self.set_status); self.files = []
        self.qr = tk.StringVar(); self.status_var = tk.StringVar(value="Pega el QR de LocalDrop para comenzar")
        ttk.Label(self, text="LocalDrop", font=("sans", 20, "bold")).pack(pady=(18, 4)); ttk.Label(self, text="Transferencias directas por la red local").pack()
        frame = ttk.Frame(self); frame.pack(fill="x", padx=20, pady=18); ttk.Label(frame, text="QR de Android:").pack(anchor="w"); ttk.Entry(frame, textvariable=self.qr).pack(fill="x", pady=5); ttk.Button(frame, text="Emparejar", command=self.start_pair).pack(anchor="e")
        self.file_label = ttk.Label(self, text="Ningún archivo seleccionado"); self.file_label.pack(pady=8); ttk.Button(self, text="Seleccionar archivos", command=self.choose).pack(); self.send_button = ttk.Button(self, text="Enviar", command=self.start_send, state="disabled"); self.send_button.pack(pady=10)
        self.progress = ttk.Progressbar(self, maximum=100); self.progress.pack(fill="x", padx=20); ttk.Label(self, textvariable=self.status_var).pack(pady=10)
    def set_status(self, text): self.after(0, self.status_var.set, text)
    def start_pair(self): threading.Thread(target=self.do_pair, daemon=True).start()
    def do_pair(self):
        try: self.client.pair(self.qr.get()); self.set_status("Emparejamiento confirmado"); self.after(0, lambda: self.send_button.configure(state="normal"))
        except Exception as error: self.set_status(f"Error: {error}")
    def choose(self):
        self.files = list(filedialog.askopenfilenames()); self.file_label.configure(text=f"{len(self.files)} archivo(s) seleccionado(s)")
    def start_send(self): threading.Thread(target=self.do_send, daemon=True).start()
    def do_send(self):
        try: self.client.send(self.files, self.update_progress); self.set_status("Transferencia completada")
        except Exception as error: self.set_status(f"Error: {error}")
    def update_progress(self, current, total, name): self.after(0, lambda: (self.progress.configure(value=(current / total * 100) if total else 100), self.status_var.set(f"Enviando {name} — {current}/{total} bytes")))

def main():
    App().mainloop()

if __name__ == "__main__": main()
