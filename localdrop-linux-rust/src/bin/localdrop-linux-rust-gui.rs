use eframe::egui;
use localdrop_linux_rust::{crypto::{default_identity_path, Identity}, http::LocalHttp, protocol::{parse_qr, Remote}, transfer};
use rfd::FileDialog;
use std::{path::PathBuf, sync::mpsc::{self, Receiver, Sender}, thread};

enum Event { Paired(Remote), Finished, Error(String) }
struct LocalDropGui { qr: String, files: Vec<PathBuf>, remote: Option<Remote>, identity: Identity, status: String, tx: Sender<Event>, rx: Receiver<Event>, working: bool }
impl Default for LocalDropGui {
    fn default() -> Self { let (tx,rx)=mpsc::channel(); Self { qr:String::new(),files:Vec::new(),remote:None,identity:Identity::load_or_create(&default_identity_path()).expect("no se pudo crear la identidad"),status:"Pega el QR de LocalDrop para comenzar".into(),tx,rx,working:false } }
}
impl LocalDropGui {
    fn poll(&mut self, ctx:&egui::Context) { while let Ok(event)=self.rx.try_recv(){self.working=false;match event{Event::Paired(r)=>{self.remote=Some(r);self.status="Pairing confirmado. Ya puedes enviar archivos.".into();},Event::Finished=>self.status="Transferencia completada".into(),Event::Error(e)=>self.status=format!("Error: {e}")}} if self.working{ctx.request_repaint_after(std::time::Duration::from_millis(100));} }
    fn pair(&mut self) { let qr=self.qr.clone();let identity=self.identity.clone();let tx=self.tx.clone();self.working=true;self.status="Verificando pairing...".into();thread::spawn(move||{let result=(||{let remote=parse_qr(&qr)?;let http=LocalHttp::new()?;http.pair(&remote,&identity,"Linux-gui")?;Ok::<_,localdrop_linux_rust::error::LocalDropError>(remote)})();match result{Ok(r)=>{let _=tx.send(Event::Paired(r));},Err(e)=>{let _=tx.send(Event::Error(e.to_string()));}}});}
    fn send(&mut self) { let Some(remote)=self.remote.clone() else{return};if self.files.is_empty(){self.status="Selecciona al menos un archivo".into();return;}let files=self.files.clone();let identity=self.identity.clone();let tx=self.tx.clone();self.working=true;self.status="Preparando transferencia...".into();thread::spawn(move||{let result=(||{let http=LocalHttp::new()?;transfer::send(&http,&remote,&identity,"Linux-gui",&files)?;Ok::<_,localdrop_linux_rust::error::LocalDropError>(())})();let _=tx.send(match result{Ok(())=>Event::Finished,Err(e)=>Event::Error(e.to_string())});});}
}
impl eframe::App for LocalDropGui {
    fn update(&mut self,ctx:&egui::Context,_frame:&mut eframe::Frame){self.poll(ctx);egui::CentralPanel::default().show(ctx,|ui|{ui.heading("LocalDrop");ui.label("Transferencias directas por la red local");ui.add_space(12.0);ui.group(|ui|{ui.label("Código QR de Android");ui.add(egui::TextEdit::singleline(&mut self.qr).hint_text("localdrop://connect?..."));if ui.add_enabled(!self.working,egui::Button::new("Emparejar")).clicked(){self.pair();}});ui.add_space(10.0);ui.horizontal(|ui|{if ui.add_enabled(!self.working,egui::Button::new("Seleccionar archivos")).clicked(){if let Some(files)=FileDialog::new().pick_files(){self.files=files;}}ui.label(format!("{} archivo(s)",self.files.len()));});egui::ScrollArea::vertical().max_height(130.0).show(ui,|ui|{for file in &self.files{ui.label(file.display().to_string());}});ui.add_space(10.0);if let Some(remote)=&self.remote{ui.label(format!("Conectado con {} ({}:{})",remote.name,remote.host,remote.port));}if ui.add_enabled(self.remote.is_some()&&!self.working,egui::Button::new("Enviar archivos")).clicked(){self.send();}ui.separator();ui.label(&self.status);if self.working{ui.spinner();}});}
}
fn main()->eframe::Result{eframe::run_native("LocalDrop",eframe::NativeOptions::default(),Box::new(|_|Ok(Box::new(LocalDropGui::default()))))}
