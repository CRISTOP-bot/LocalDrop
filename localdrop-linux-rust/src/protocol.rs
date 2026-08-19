use crate::{crypto::{b64_encode, sha256_hex}, error::{LocalDropError, Result}};
use std::{fs::File, io::{Read, Seek, SeekFrom}, path::{Path, PathBuf}};
use sha2::Digest;
use url::Url;

#[derive(Clone, Debug)] pub struct Remote { pub host: String, pub port: u16, pub name: String, pub public_key: String, pub fingerprint: String }
#[derive(Clone, Debug)] pub struct ManifestFile { pub path: PathBuf, pub size: u64, pub sha256: String }

pub fn parse_qr(raw: &str) -> Result<Remote> {
    let url = Url::parse(raw.trim()).map_err(|e| LocalDropError::Qr(e.to_string()))?;
    if url.scheme() != "localdrop" || url.host_str() != Some("connect") { return Err(LocalDropError::Qr("se esperaba localdrop://connect".into())); }
    let mut host=None; let mut port=None; let mut name=None; let mut pk=None; let mut fp=None;
    for (k,v) in url.query_pairs() { match k.as_ref() { "host"=>host=Some(v.into_owned()), "port"=>port=v.parse().ok(), "name"=>name=Some(v.into_owned()), "pk"=>pk=Some(v.into_owned()), "fp"=>fp=Some(v.into_owned()), _=>{} } }
    let host=host.filter(|x| !x.is_empty()).ok_or_else(|| LocalDropError::Qr("falta host".into()))?;
    let port=port.filter(|x: &u16| *x>0).ok_or_else(|| LocalDropError::Qr("puerto inválido".into()))?;
    let public_key=pk.filter(|x| !x.is_empty()).ok_or_else(|| LocalDropError::Qr("falta pk".into()))?;
    let fingerprint=fp.filter(|x| x.len()==64 && x.chars().all(|c| c.is_ascii_hexdigit())).ok_or_else(|| LocalDropError::Qr("fingerprint inválido".into()))?;
    Ok(Remote { host, port, name: name.unwrap_or_else(|| "Android".into()), public_key, fingerprint })
}

pub fn prepare_file(path: &Path) -> Result<ManifestFile> {
    let mut file=File::open(path)?; let mut hasher=sha2::Sha256::new(); let mut buf=[0u8;65536]; let mut size=0u64;
    loop { let n=file.read(&mut buf)?; if n==0 {break;} hasher.update(&buf[..n]); size=size.checked_add(n as u64).ok_or_else(|| LocalDropError::Manifest("tamaño desbordado".into()))?; }
    Ok(ManifestFile { path:path.to_path_buf(), size, sha256:hex::encode(hasher.finalize()) })
}

pub fn create_manifest(files: &[ManifestFile]) -> Result<String> {
    let mime=b64_encode(b"application/octet-stream"); let mut out=String::new();
    for f in files { let name=f.path.file_name().and_then(|x|x.to_str()).ok_or_else(|| LocalDropError::Manifest("nombre de archivo inválido".into()))?; out.push_str(&format!("{}\t{}\t{}\t{}\n",b64_encode(name.as_bytes()),f.size,mime,f.sha256)); }
    Ok(out)
}

pub fn read_chunk(file: &mut File, offset: u64, size: usize) -> Result<Vec<u8>> { file.seek(SeekFrom::Start(offset))?; let mut data=vec![0u8;size]; let mut read=0; while read<size { let n=file.read(&mut data[read..])?; if n==0 { data.truncate(read); break; } read+=n; } Ok(data) }

#[cfg(test)] mod tests { use super::*; #[test] fn manifest_format_is_exact(){ let f=ManifestFile{path:"a b.txt".into(),size:0,sha256:"00".into()}; assert_eq!(create_manifest(&[f]).unwrap(),"YSBiLnR4dA\t0\tYXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt\t00\n"); } }
