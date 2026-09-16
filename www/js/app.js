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
let pendingSecret="", activeMediaId="";
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
async function unlock(){const pin=$("#pinInput").value.trim();if(!/^\d{6}$/.test(pin)||!pendingSecret){$("#unlockMsg").textContent="Enter your 6-digit PIN.";return}try{const r=await Native.verifyCredential({secret:pendingSecret,pin});if(r.ok){pendingSecret="";$("#pinInput").value="";show("#vaultView");await renderVault()}else{$("#unlockMsg").textContent="Wrong PIN.";$("#pinInput").select()}}catch{$("#unlockMsg").textContent="Could not unlock."}}
async function openMediaPage(id){activeMediaId=id;show("#mediaView");$("#mediaPreview").style.display="none";$("#mediaPreviewFallback").style.display="grid";$("#mediaMsg").textContent="Loading preview…";try{const r=await Native.listMedia();const m=(r.items||[]).find(x=>String(x.id)===String(id));if(!m)throw new Error("Media not found");$("#mediaName").textContent=m.name||"Private media";const t=await Native.getThumbnail({id});if(t?.data){$("#mediaPreview").src=`data:${t.mime||"image/jpeg"};base64,${t.data}`;$("#mediaPreview").style.display="block";$("#mediaPreviewFallback").style.display="none"}$("#mediaMsg").textContent=""}catch(e){$("#mediaMsg").textContent=e?.message||"Preview unavailable."}}
async function deleteActiveMedia(){if(!activeMediaId||!Native)return;const r=await Native.listMedia();const m=(r.items||[]).find(x=>String(x.id)===String(activeMediaId));$("#deleteConfirmName").textContent=m?.name||"Private media";show("#deleteConfirmView")}
$("#noteForm").onsubmit=async e=>{e.preventDefault();const title=$("#noteTitle").value.trim(),body=$("#noteBody").value.trim();if(!title&&!body)return;if(state.setup&&Native&&title){try{const r=await Native.verifySecret({secret:title});if(r?.ok){e.target.reset();triggerSecret(title);return}}catch{}}notes.unshift({title,body,createdAt:Date.now()});localStorage.setItem("nyx_notes",JSON.stringify(notes));e.target.reset();renderNotes()};
$("#settingsBtn").onclick=()=>state.setup?show("#settingsView"):show("#setupView");$("#saveName").onclick=()=>{state.displayName=$("#displayName").value.trim()||"Notes";save();$("#visibleTitle").textContent=state.displayName;show("#notesView")};
document.querySelectorAll("[data-back]").forEach(b=>b.onclick=()=>show("#"+b.dataset.back));
let setupBusy=false;function setSetupBusy(busy,message){setupBusy=busy;const btn=$("#finishSetup");if(!btn)return;btn.disabled=busy;btn.textContent=busy?(message||"Setting up…"):"Finish setup"}
$("#finishSetup").onclick=async()=>{if(setupBusy)return;const secret=$("#secretName").value.trim(),pin=$("#pin").value.trim();$("#setupMsg").textContent="";if(!secret||!/^\d{6}$/.test(pin)||!selectedRecovery){$("#setupMsg").textContent="Complete the secret name, PIN and recovery choice first.";return}setSetupBusy(true,"Setting up…");try{Native=await waitForNative(8000);if(!Native)throw new Error("Private storage could not start.");await Native.saveCredential({secret,pin,removeOriginal:false});state.setup=true;save();show("#notesView")}catch(e){$("#setupMsg").textContent=e?.message||"Could not finish setup."}finally{setSetupBusy(false)}};
function triggerSecret(secret){if(!state.setup||!Native)return;pendingSecret=secret;$("#pinInput").value="";$("#unlockMsg").textContent="";show("#unlockView");setTimeout(()=>$("#pinInput").focus(),80)}
$("#pinGo").onclick=unlock;$("#pinInput").addEventListener("keydown",e=>{if(e.key==="Enter")unlock()});
$("#biometricBtn").onclick=async()=>{if(!Native)return;try{await Native.authenticateBiometric();pendingSecret="";show("#vaultView");await renderVault()}catch(e){$("#unlockMsg").textContent=e?.message||"Biometric authentication failed."}};
$("#lockBtn").onclick=async()=>{pendingSecret="";activeMediaId="";show("#notesView");Native?.clearTempCache?.().catch?.(()=>{})};
$("#importBtn").onclick=()=>show("#pickerView");
async function choosePicker(source){$("#pickerMsg").textContent="Opening picker…";try{const r=await Native.pickMedia({source});if(r?.imported){$("#pickerMsg").textContent=`Added ${r.imported} media.`;await renderVault();setTimeout(()=>show("#vaultView"),350)}else{$("#pickerMsg").textContent=r?.warning||"No media was selected."}}catch(e){if(!/cancel/i.test(e?.message||""))$("#pickerMsg").textContent=e?.message||"Could not import media."}}
$("#pickPhotos").onclick=()=>choosePicker("photos");$("#pickFiles").onclick=()=>choosePicker("files");
let debugTimer=null;async function refreshDebugger(){if(!Native?.getDebugLog)return;try{const [log,status]=await Promise.all([Native.getDebugLog(),Native.getUploadStatus()]);const items=status.items||[],active=items.filter(x=>x.state==="uploading"),failed=items.filter(x=>x.state==="failed");$("#debugStatus").textContent=active.length?`${active.length} upload${active.length>1?"s":""} in progress${failed.length?` · ${failed.length} failed`:""}`:(failed.length?`${failed.length} upload${failed.length>1?"s":""} failed`:"Cloudinary backup ready.");$("#debugLog").textContent=log?.text||"No debug events yet."}catch(e){$("#debugLog").textContent="Debugger error: "+(e?.message||e)}}
$("#debugBtn").onclick=async()=>{show("#debugView");await refreshDebugger();clearInterval(debugTimer);debugTimer=setInterval(refreshDebugger,1000)};$("#closeDebug").onclick=()=>{clearInterval(debugTimer);debugTimer=null;show("#vaultView")};$("#retryUploads").onclick=async()=>{try{const r=await Native.retryUploads();$("#debugStatus").textContent=`Retry queued: ${r?.queued||0}`;await refreshDebugger()}catch(e){$("#debugStatus").textContent=e?.message||"Could not retry uploads."}};$("#clearDebug").onclick=async()=>{try{await Native.clearDebugLog();await refreshDebugger()}catch{}};
$("#vaultGrid").addEventListener("click",e=>{const b=e.target.closest(".media");if(b)openMediaPage(b.dataset.id)});$("#closeMedia").onclick=()=>show("#vaultView");$("#deleteMediaBtn").onclick=deleteActiveMedia;$("#confirmDelete").onclick=async()=>{try{await Native.deleteMedia({id:activeMediaId});activeMediaId="";show("#vaultView");await renderVault()}catch(e){$("#deleteMsg").textContent=e?.message||"Could not delete media."}};
// Private settings is a page, not a modal. It is intentionally not exposed as a button on the vault surface.
$("#privateBackBtn").onclick=()=>show("#vaultView");$("#savePrivateSettings").onclick=async()=>{try{await Native.setPrivateSettings({biometricEnabled:$("#bioToggle").checked,removeOriginal:false});$("#privateMsg").textContent="Private settings saved."}catch{$("#privateMsg").textContent="Could not save settings."}};
async function loadPrivateSettings(){if(!Native)return;try{const s=await Native.getPrivateSettings();$("#bioToggle").checked=!!s.biometricEnabled;$("#bioToggle").disabled=!s.biometricAvailable;$("#bioStatus").textContent=s.biometricAvailable?(s.biometricEnabled?"Enabled on this device.":"Available on this device."):"Biometric authentication is unavailable."}catch{}}
$("#changeCredentialBtn").onclick=()=>show("#credentialView");$("#saveCredential").onclick=async()=>{const oldSecret=$("#oldSecret").value.trim(),oldPin=$("#oldPin").value.trim(),newSecret=$("#newSecret").value.trim(),newPin=$("#newPin").value.trim();if(!oldSecret||!/^[0-9]{6}$/.test(oldPin)||!newSecret||!/^[0-9]{6}$/.test(newPin)){$("#credentialMsg").textContent="Use a secret and two valid 6-digit PINs.";return}try{await Native.changeCredential({oldSecret,oldPin,newSecret,newPin});$("#credentialMsg").textContent="Access details updated.";setTimeout(()=>show("#privateSettingsView"),400)}catch(e){$("#credentialMsg").textContent=e?.message||"Could not update access details."}};
// Keep the vault visible while uploads finish; status refresh never removes media cards.
setInterval(()=>{if(state.setup&&Native){refreshUploadStatus()}},1000);
$("#visibleTitle").textContent=state.displayName||"Notes";$("#displayName").value=state.displayName||"Notes";renderNotes();
window.addEventListener("load",()=>{show(state.setup?"#notesView":"#setupView");setTimeout(()=>$("#splash")?.classList.add("hide"),1200)});
