use crate::{crypto::Identity, error::{LocalDropError, Result}, protocol::Remote};
use reqwest::{blocking::Client, header::{HeaderMap, HeaderName, HeaderValue}};
use std::time::Duration;

pub struct LocalHttp { client: Client }
impl LocalHttp {
    pub fn new() -> Result<Self> { Ok(Self { client: Client::builder().connect_timeout(Duration::from_secs(8)).timeout(Duration::from_secs(120)).build()? }) }
    fn url(remote:&Remote,path:&str)->String{format!("http://{}:{}{}",remote.host,remote.port,path)}
    pub fn pair(&self, remote:&Remote, identity:&Identity, device_name:&str) -> Result<()> {
        let challenge=self.client.get(Self::url(remote,"/pair-challenge")).send()?;
        if !challenge.status().is_success(){return Err(LocalDropError::HttpStatus(challenge.status()));}
        let nonce=challenge.headers().get("X-LocalDrop-Nonce").and_then(|v|v.to_str().ok()).ok_or_else(||LocalDropError::Crypto("falta nonce".into()))?.to_owned();
        let fp=challenge.headers().get("X-LocalDrop-Fingerprint").and_then(|v|v.to_str().ok()).unwrap_or(""); let sig=challenge.headers().get("X-LocalDrop-Signature").and_then(|v|v.to_str().ok()).unwrap_or("");
        if fp!=remote.fingerprint || !Identity::verify(&remote.public_key,&nonce,sig){return Err(LocalDropError::Crypto("desafío remoto inválido".into()));}
        let mut h=HeaderMap::new(); put(&mut h,"X-LocalDrop-Nonce",&nonce)?; put(&mut h,"X-LocalDrop-Device-Id",device_name)?; put(&mut h,"X-LocalDrop-Device-Name",device_name)?; put(&mut h,"X-LocalDrop-Port","0")?; put(&mut h,"X-LocalDrop-Public-Key",&identity.public_key)?; put(&mut h,"X-LocalDrop-Fingerprint",&identity.fingerprint)?; put(&mut h,"X-LocalDrop-Signature",&identity.sign(&nonce))?;
        let response=self.client.post(Self::url(remote,"/pair-confirm")).headers(h).body(Vec::new()).send()?; if !response.status().is_success(){return Err(LocalDropError::HttpStatus(response.status()));} Ok(())
    }
    pub fn chunk(&self, remote:&Remote, headers:HeaderMap, body:Vec<u8>) -> Result<Option<u64>> { let response=self.client.post(Self::url(remote,"/upload-chunk")).headers(headers).body(body).send()?; if !response.status().is_success(){return Err(LocalDropError::HttpStatus(response.status()));} Ok(response.headers().get("X-LocalDrop-Next-Offset").and_then(|v|v.to_str().ok()).and_then(|v|v.parse().ok())) }
}
fn put(map:&mut HeaderMap,name:&str,value:&str)->Result<()> { map.insert(HeaderName::from_bytes(name.as_bytes()).map_err(|e|LocalDropError::Crypto(e.to_string()))?,HeaderValue::from_str(value).map_err(|e|LocalDropError::Crypto(e.to_string()))?); Ok(()) }
pub fn headers(remote:&Remote, identity:&Identity, session:&str, manifest:&str, index:usize, offset:u64,size:u64,device:&str)->Result<HeaderMap>{let mut h=HeaderMap::new();put(&mut h,"Content-Type","application/octet-stream")?;put(&mut h,"X-LocalDrop-Session",session)?;put(&mut h,"X-LocalDrop-Device-Id",device)?;put(&mut h,"X-LocalDrop-Device-Name",device)?;put(&mut h,"X-LocalDrop-Public-Key",&identity.public_key)?;put(&mut h,"X-LocalDrop-Fingerprint",&identity.fingerprint)?;put(&mut h,"X-LocalDrop-Signature",&identity.sign(&format!("{session}|{manifest}")))?;put(&mut h,"X-LocalDrop-Manifest",&crate::crypto::b64_encode(manifest.as_bytes()))?;put(&mut h,"X-LocalDrop-Chunk","1")?;put(&mut h,"X-LocalDrop-File-Index",&index.to_string())?;put(&mut h,"X-LocalDrop-Offset",&offset.to_string())?;put(&mut h,"X-LocalDrop-File-Size",&size.to_string())?;let _=remote;Ok(h)}
