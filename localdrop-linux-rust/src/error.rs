use thiserror::Error;

#[derive(Debug, Error)]
pub enum LocalDropError {
    #[error("uso: localdrop-linux-rust 'localdrop://connect?...' archivo1 archivo2 ...")]
    Usage,
    #[error("URL QR inválida: {0}")]
    Qr(String),
    #[error("error de entrada/salida: {0}")]
    Io(#[from] std::io::Error),
    #[error("error HTTP: {0}")]
    Http(#[from] reqwest::Error),
    #[error("respuesta HTTP inesperada: {0}")]
    HttpStatus(reqwest::StatusCode),
    #[error("error criptográfico: {0}")]
    Crypto(String),
    #[error("manifest inválido: {0}")]
    Manifest(String),
    #[error("offset inválido: {0}")]
    Offset(String),
}

pub type Result<T> = std::result::Result<T, LocalDropError>;
