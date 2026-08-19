use localdrop_linux_rust::{crypto::{default_identity_path, Identity}, error::{LocalDropError, Result}, http::LocalHttp, protocol::parse_qr, transfer};
use std::{path::PathBuf, process::ExitCode};

fn run(args: Vec<String>) -> Result<()> { if args.len()<3{return Err(LocalDropError::Usage);} let remote=parse_qr(&args[1])?;let identity=Identity::load_or_create(&default_identity_path())?;let device=format!("Linux-{}",std::env::var("USER").unwrap_or_else(|_|"local".into()));let http=LocalHttp::new()?;http.pair(&remote,&identity,&device)?;let files:Vec<PathBuf>=args[2..].iter().map(PathBuf::from).collect();transfer::send(&http,&remote,&identity,&device,&files)}
fn main()->ExitCode{match run(std::env::args().collect()){Ok(())=>ExitCode::SUCCESS,Err(e)=>{eprintln!("Error: {e}");ExitCode::from(1)}}}
