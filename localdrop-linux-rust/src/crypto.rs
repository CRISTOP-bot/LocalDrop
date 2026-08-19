use crate::error::{LocalDropError, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use p256::ecdsa::{signature::{Signer, Verifier}, Signature, SigningKey, VerifyingKey};
use p256::pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePrivateKey, EncodePublicKey, LineEnding};
use rand_core::OsRng;
use sha2::{Digest, Sha256};
use std::{fs, path::{Path, PathBuf}};

pub fn b64_encode(bytes: &[u8]) -> String { URL_SAFE_NO_PAD.encode(bytes) }
pub fn b64_decode(value: &str) -> Result<Vec<u8>> { URL_SAFE_NO_PAD.decode(value).map_err(|e| LocalDropError::Crypto(format!("base64 inválido: {e}"))) }
pub fn sha256_hex(bytes: &[u8]) -> String { hex::encode(Sha256::digest(bytes)) }
pub fn fingerprint(der: &[u8]) -> String { sha256_hex(der) }

#[derive(Clone)]
pub struct Identity { pub signing: SigningKey, pub public_key: String, pub fingerprint: String }

impl Identity {
    pub fn load_or_create(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
        let signing = if path.exists() {
            let pem = fs::read_to_string(path)?;
            SigningKey::from_pkcs8_pem(&pem).map_err(|e| LocalDropError::Crypto(format!("clave PEM inválida: {e}")))?
        } else {
            let key = SigningKey::random(&mut OsRng);
            let pem = key.to_pkcs8_pem(LineEnding::LF).map_err(|e| LocalDropError::Crypto(format!("no se pudo serializar la clave: {e}")))?;
            fs::write(path, pem.as_bytes())?;
            #[cfg(unix)] { use std::os::unix::fs::PermissionsExt; fs::set_permissions(path, fs::Permissions::from_mode(0o600))?; }
            key
        };
        let der = signing.verifying_key().to_public_key_der().map_err(|e| LocalDropError::Crypto(format!("clave pública: {e}")))?;
        let public_key = b64_encode(der.as_bytes());
        let fp = fingerprint(der.as_bytes());
        Ok(Self { signing, public_key, fingerprint: fp })
    }
    pub fn sign(&self, message: &str) -> String { let signature: Signature = self.signing.sign(message.as_bytes()); b64_encode(&signature.to_der()) }
    pub fn verify(public_key: &str, message: &str, signature: &str) -> bool {
        let result = (|| -> Result<bool> {
            let der = b64_decode(public_key)?;
            let verifying = VerifyingKey::from_public_key_der(&der).map_err(|e| LocalDropError::Crypto(e.to_string()))?;
            if fingerprint(&der) != fingerprint(verifying.to_public_key_der().map_err(|e| LocalDropError::Crypto(e.to_string()))?.as_bytes()) { return Ok(false); }
            let sig = Signature::from_der(&b64_decode(signature)?).map_err(|e| LocalDropError::Crypto(e.to_string()))?;
            Ok(verifying.verify(message.as_bytes(), &sig).is_ok())
        })();
        result.unwrap_or(false)
    }
}

pub fn default_identity_path() -> PathBuf { PathBuf::from(std::env::var_os("XDG_DATA_HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(std::env::var_os("HOME").unwrap_or_default())).join("localdrop-linux-rust/identity.pem")) }

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn base64_is_url_safe_without_padding() { assert_eq!(b64_encode(b"?+/") , "Pysv"); assert_eq!(b64_decode("Pysv").unwrap(), b"?+/"); }
    #[test] fn signs_and_rejects_modified_messages() { let i = Identity::load_or_create(&tempfile::tempdir().unwrap().path().join("identity.pem")).unwrap(); let s=i.sign("nonce"); assert!(Identity::verify(&i.public_key,"nonce",&s)); assert!(!Identity::verify(&i.public_key,"other",&s)); }
    #[test] fn fingerprint_is_lowercase_sha256() { assert_eq!(fingerprint(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"); }
}
