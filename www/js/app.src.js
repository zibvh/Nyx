let Native=null;
function initNativeBridge(){
  try{
    const cap=window.Capacitor;
    if(!cap) return false;
    if(typeof cap.registerPlugin === "function") Native=cap.registerPlugin("NyxVault");
    else if(cap.Plugins?.NyxVault) Native=cap.Plugins.NyxVault;
    return !!Native;
  }catch(e){ Native=null; return false; }
}
initNativeBridge();

const $=s=>document.querySelector(s);

if(window.Capacitor){

}
const notes=JSON.parse(localStorage.getItem("nyx_notes")||"[]");
const state=JSON.parse(localStorage.getItem("nyx_state")||'{"setup":false,"displayName":"Notes","recovery":null}');
let selectedRecovery=null, pendingSecret="", activeMediaId="";
const save=()=>localStorage.setItem("nyx_state",JSON.stringify(state));
async function waitForNative(timeout=5000){
  const started=Date.now();
  while(Date.now()-started < timeout){
    initNativeBridge();
    try {
      const r=Native && await Native.ping();
      if(r?.ok) return Native;
    } catch {}
    await new Promise(r=>setTimeout(r,100));
  }
  return null;
}
const show=id=>{document.querySelectorAll(".view").forEach(x=>x.classList.remove("active"));$(id).classList.add("active")};
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
function renderNotes(){$("#notesList").innerHTML=notes.length?notes.map(n=>`<article class="note"><h3>${esc(n.title||"Untitled")}</h3><p>${esc(n.body)}</p><small>${new Date(n.createdAt).toLocaleString()}</small></article>`).join(""):`<p class="muted">No notes yet.</p>`}
let uploadStatusCache={};
async function refreshUploadStatus(){if(!Native?.getUploadStatus)return;try{const r=await Native.getUploadStatus(),items=r.items||[];uploadStatusCache={};items.forEach(x=>uploadStatusCache[x.id]=x);document.querySelectorAll(".media").forEach(card=>{const st=uploadStatusCache[card.dataset.id];const box=card.querySelector(".upload-state");if(!st||st.state==="uploaded"){if(box)box.remove();return}const pct=Math.max(0,Math.min(100,Number(st.percent)||0));if(box){box.querySelector(".upload-pct").textContent=st.state==="failed"?"Upload failed":`${pct}%`;box.querySelector("i").style.width=pct+"%";box.querySelector(".upload-label").classList.toggle("upload-error",st.state==="failed")}else{const div=document.createElement("div");div.className="upload-state";div.innerHTML=`<div class="upload-label"><span>${st.state==="failed"?"Upload failed":"Uploading"}</span><span class="upload-pct">${st.state==="failed"?"Upload failed":pct+"%"}</span></div><div class="upload-bar"><i style="width:${pct}%"></i></div>`;card.appendChild(div)}})}catch{}}
const thumbCache={};
async function renderVault(){if(!Native){$("#vaultGrid").innerHTML='<p class="muted">Private storage is available in the Android build.</p>';return}try{const r=await Native.listMedia(),items=r.items||[];const grid=$("#vaultGrid");if(!items.length){grid.innerHTML='<p class="muted">No private media yet.</p>';return}const wanted=new Set(items.map(m=>String(m.id)));grid.querySelectorAll('.media').forEach(card=>{if(!wanted.has(String(card.dataset.id)))card.remove()});const existing=new Set([...grid.querySelectorAll('.media')].map(x=>String(x.dataset.id)));for(const m of items){if(existing.has(String(m.id))){const label=grid.querySelector(`[data-id="${CSS.escape(String(m.id))}"] span`);if(label)label.textContent=m.name||"MEDIA";continue;}const card=document.createElement('button');card.className='media media-enter';card.dataset.id=m.id;card.innerHTML=`<div class="media-placeholder" id="thumb-${esc(m.id)}">${m.mime?.startsWith("video/")?"VIDEO":"PHOTO"}</div><span>${esc(m.name||"MEDIA")}</span>`;grid.appendChild(card);requestAnimationFrame(()=>card.classList.add('ready'));loadThumb(m)}await refreshUploadStatus()}catch{$("#vaultGrid").innerHTML='<p class="muted">No private media yet.</p>'}}
async function loadThumb(m){const key=String(m.id);if(thumbCache[key]){const holder=$("#thumb-"+CSS.escape(key));if(holder)holder.innerHTML=`<img src="${thumbCache[key]}" alt="">`;return}try{const r=await Native.getThumbnail({id:m.id});if(r.data){const uri=`data:${r.mime||"image/jpeg"};base64,${r.data}`;thumbCache[key]=uri;const holder=$("#thumb-"+CSS.escape(key));if(holder)holder.innerHTML=`<img src="${uri}" alt="">`}}catch{}}
async function unlock(){const pin=$("#pinInput").value.trim();if(!/^[0-9]{6}$/.test(pin)||!pendingSecret){$("#unlockMsg").textContent="Enter your 6-digit PIN.";return}try{const r=await Native.verifyCredential({secret:pendingSecret,pin});if(r.ok){pendingSecret="";$("#pinInput").value="";$("#unlockModal").classList.remove("show");show("#vaultView");Native.setSecureScreen?.({enabled:true}).catch?.(()=>{});await renderVault();}else{$("#unlockMsg").textContent="Wrong PIN.";$("#pinInput").select()}}catch{$("#unlockMsg").textContent="Could not unlock."}}
function openMediaModal(id){activeMediaId=id;$("#mediaModal").classList.add("show");$("#mediaPreview").style.display="none";$("#mediaPreviewFallback").style.display="grid";$("#mediaMsg").textContent="Loading preview…";Native.listMedia().then(r=>{const m=(r.items||[]).find(x=>x.id===id);$("#mediaName").textContent=m?.name||"Private media";return Native.getThumbnail({id})}).then(r=>{if(r?.data){$("#mediaPreview").src=`data:${r.mime||"image/jpeg"};base64,${r.data}`;$("#mediaPreview").style.display="block";$("#mediaPreviewFallback").style.display="none"}$("#mediaMsg").textContent=""}).catch(()=>{$("#mediaMsg").textContent="Preview unavailable. You can still try Open."})}
async function closeMedia(){activeMediaId="";$("#mediaModal").classList.remove("show");if(Native?.clearTempCache)try{await Native.clearTempCache()}catch{}}
async function deleteActiveMedia(){if(!activeMediaId||!Native)return;if(!confirm("Delete this private copy? This cannot be undone."))return;try{await Native.deleteMedia({id:activeMediaId});await closeMedia();await renderVault()}catch{$("#mediaMsg").textContent="Could not delete media."}}
async function loadPrivateSettings(){if(!Native)return;try{const s=await Native.getPrivateSettings();$("#bioToggle").checked=!!s.biometricEnabled;$("#bioToggle").disabled=!s.biometricAvailable;$("#bioStatus").textContent=s.biometricAvailable?(s.biometricEnabled?"Enabled on this device.":"Available on this device."):"Fingerprint/face authentication is not available.";$("#removeOriginalToggle").checked=s.removeOriginal!==false}catch{$("#bioStatus").textContent="Could not read private settings."}}
$("#noteForm").onsubmit=async e=>{
  e.preventDefault();
  const title=$("#noteTitle").value.trim(),body=$("#noteBody").value.trim();
  if(!title&&!body)return;
if(state.setup && Native && title){
    try{
const r=await Native.verifySecret({secret:title});
if(r?.ok){ e.target.reset(); triggerSecret(title); return; }
    }catch(err){
}
  }
  notes.unshift({title,body,createdAt:Date.now()});
  localStorage.setItem("nyx_notes",JSON.stringify(notes));
  e.target.reset();
  renderNotes();
};
$("#settingsBtn").onclick=()=>state.setup?show("#settingsView"):show("#setupView");$("#backBtn").onclick=()=>show("#notesView");$("#saveName").onclick=()=>{state.displayName=$("#displayName").value.trim()||"Notes";save();$("#visibleTitle").textContent=state.displayName;show("#notesView")};
document.querySelectorAll("[data-recovery]").forEach(b=>b.onclick=()=>{selectedRecovery=b.dataset.recovery;document.querySelectorAll("[data-recovery]").forEach(x=>x.classList.remove("selected"));b.classList.add("selected")});
let setupBusy=false;
function setSetupBusy(busy,message){setupBusy=busy;const btn=$("#finishSetup");if(!btn)return;btn.disabled=busy;btn.classList.toggle("is-loading",busy);btn.innerHTML=busy?`<span class="btn-spinner" aria-hidden="true"></span><span>${message||"Setting up private storage…"}</span>`:`Finish private setup`;btn.setAttribute("aria-busy",busy?"true":"false")}
$("#finishSetup").onclick=async()=>{
 if(setupBusy)return;
 const secret=$("#secretName").value.trim(),pin=$("#pin").value.trim();
 $("#setupMsg").className="setup-status muted";
 if(!secret||!/^\d{6}$/.test(pin)||!selectedRecovery){$("#setupMsg").textContent="Complete the secret name, 6-digit PIN and recovery choice first.";$("#setupMsg").classList.add("setup-error");return}
 setSetupBusy(true,"Starting private setup…");$("#setupMsg").textContent="Preparing private media storage. Please wait…";
 try{
   Native=await waitForNative(8000);
   if(!Native)throw new Error("NYX private storage could not start. Close and reopen the app, then try again.");
   if(Native?.addListener){Promise.resolve(Native.addListener("deleteStatus",e=>{if(e?.manualDeleteRequired){alert("Original still exists. Your media is safely stored in NYX. Delete the original manually from Gallery/Files to complete the move.");}})).catch(()=>{});}
   $("#setupMsg").textContent="Preparing private media storage…";
   await Promise.race([Native.saveCredential({secret,pin,removeOriginal:false}),new Promise((_,reject)=>setTimeout(()=>reject(new Error("Private setup is taking too long. Please try again.")),15000))]);
   state.recovery=selectedRecovery;state.setup=true;save();
   $("#setupMsg").textContent="Private setup complete. Opening Notes…";$("#setupMsg").classList.add("setup-success");
   setTimeout(()=>show("#notesView"),500);
 }catch(e){
   $("#setupMsg").textContent=e?.message||"Could not finish private setup. Try again.";$("#setupMsg").classList.add("setup-error");
 }finally{setSetupBusy(false)}
};
function triggerSecret(secret){
if(!state.setup||!Native)return;
  Native.verifySecret({secret}).then(r=>{
if(r.ok){pendingSecret=secret;$("#pinInput").value="";$("#unlockMsg").textContent="";$("#unlockModal").classList.add("show");Native.getPrivateSettings?.().then(s=>{$("#biometricBtn").style.display=s?.biometricEnabled?"block":"none"}).catch(()=>{});setTimeout(()=>$("#pinInput").focus(),60)}
  }).catch(err=>{
});
}
$("#pinGo").onclick=unlock;$("#pinInput").addEventListener("keydown",e=>{if(e.key==="Enter")unlock()});$("#cancelUnlock").onclick=()=>{$("#unlockModal").classList.remove("show");pendingSecret="";$("#pinInput").value=""};
$("#biometricBtn").onclick=async()=>{if(!Native||!pendingSecret)return;try{await Native.authenticateBiometric();pendingSecret="";$("#unlockModal").classList.remove("show");show("#vaultView");Native.setSecureScreen?.({enabled:true}).catch?.(()=>{});await renderVault()}catch{}};
$("#lockBtn").onclick=async()=>{pendingSecret="";activeMediaId="";Native?.setSecureScreen?.({enabled:false}).catch?.(()=>{});Native?.clearTempCache?.().catch?.(()=>{});show("#notesView")};
$("#importBtn").onclick=async()=>{
  Native=await waitForNative();
  if(!Native){ alert("Private storage is not ready. Please close and reopen NYX."); return; }
  $("#pickerChoiceModal").classList.add("show");
};
async function choosePicker(source){
  $("#pickerChoiceModal").classList.remove("show");
  try{
    const r=await Native.pickMedia({source});
    if(r?.imported) {
      await renderVault();
    } else {
      throw new Error(r?.warning || "No media was imported");
    }
  }catch(e){
    if(e?.message && !/cancel/i.test(e.message)) alert(e.message);
  }
}
$("#pickPhotos").onclick=()=>choosePicker("photos");
$("#pickFiles").onclick=()=>choosePicker("files");
$("#closePickerChoice").onclick=()=>$("#pickerChoiceModal").classList.remove("show");
let debugTimer=null;
async function refreshDebugger(){if(!Native?.getDebugLog)return;try{const [log,status]=await Promise.all([Native.getDebugLog(),Native.getUploadStatus()]);const items=status.items||[];const active=items.filter(x=>x.state==="uploading"),failed=items.filter(x=>x.state==="failed");$("#debugStatus").textContent=active.length?`${active.length} upload${active.length>1?"s":""} in progress${failed.length?` · ${failed.length} failed`:""}`:("Local-only mode — cloud backup is disabled.");$("#debugLog").textContent=log?.text||"No debug events yet.";const el=$("#debugLog");el.scrollTop=el.scrollHeight}catch(e){$("#debugLog").textContent="Debugger error: "+(e?.message||e)}}
$("#debugBtn").onclick=async()=>{$("#debugModal").classList.add("show");await refreshDebugger();clearInterval(debugTimer);debugTimer=setInterval(refreshDebugger,1000)};$("#closeDebug").onclick=()=>{$("#debugModal").classList.remove("show");clearInterval(debugTimer);debugTimer=null};$("#retryUploads").onclick=async()=>{await refreshDebugger()};$("#clearDebug").onclick=async()=>{try{await Native.clearDebugLog();await refreshDebugger()}catch{}};
$("#vaultGrid").addEventListener("click",e=>{const b=e.target.closest(".media");if(b)openMediaModal(b.dataset.id)});$("#closeMedia").onclick=closeMedia;$("#deleteMediaBtn").onclick=deleteActiveMedia;$("#openMediaBtn").onclick=async()=>{if(!activeMediaId||!Native)return;try{await Native.openMedia({id:activeMediaId});closeMedia()}catch(e){$("#mediaMsg").textContent=e?.message||"Could not open media."}};
$("#privateSettingsBtn").onclick=async()=>{show("#privateSettingsView");await loadPrivateSettings()};$("#privateBackBtn").onclick=()=>show("#vaultView");$("#savePrivateSettings").onclick=async()=>{try{await Native.setPrivateSettings({biometricEnabled:$("#bioToggle").checked,removeOriginal:$("#removeOriginalToggle").checked});$("#privateMsg").textContent="Private settings saved."}catch{$("#privateMsg").textContent="Could not save settings."}};
$("#changeCredentialBtn").onclick=()=>{$("#credentialModal").classList.add("show");$("#credentialMsg").textContent=""};$("#closeCredential").onclick=()=>$("#credentialModal").classList.remove("show");$("#saveCredential").onclick=async()=>{const oldSecret=$("#oldSecret").value.trim(),oldPin=$("#oldPin").value.trim(),newSecret=$("#newSecret").value.trim(),newPin=$("#newPin").value.trim();if(!oldSecret||!/^[0-9]{6}$/.test(oldPin)||!newSecret||!/^[0-9]{6}$/.test(newPin)){$("#credentialMsg").textContent="Use a secret and two valid 6-digit PINs.";return}try{await Native.changeCredential({oldSecret,oldPin,newSecret,newPin});pendingSecret="";$("#credentialModal").classList.remove("show");$("#privateMsg").textContent="Access details updated."}catch(e){$("#credentialMsg").textContent=e?.message||"Could not update access details."}};
setInterval(()=>{if(state.setup && Native){refreshUploadStatus();}},1000);
$("#visibleTitle").textContent=state.displayName||"Notes";$("#displayName").value=state.displayName||"Notes";renderNotes();
window.addEventListener("load",()=>{
  show(state.setup ? "#notesView" : "#setupView");
  setTimeout(()=>$("#splash")?.classList.add("hide"),1600);
});
