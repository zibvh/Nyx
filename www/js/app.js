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
const state=JSON.parse(localStorage.getItem("nyx_state")||'{"setup":false,"displayName":"Notes"}');
let pendingSecret="", activeMediaId="";
const VAULT_VIEWS=new Set(["#vaultView","#vaultSettingsView","#uploadDebugView","#pickerView","#deleteConfirmView"]);
let vaultLockPending=false;
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
let lastShownWasVault=false;
const show=id=>{
  document.querySelectorAll(".view").forEach(x=>x.classList.remove("active"));
  $(id).classList.add("active");
  const settings=$("#settingsBtn");if(settings)settings.style.display=id==="#notesView"?"":"none";
  const nowVault=VAULT_VIEWS.has(id);
  if(nowVault&&!lastShownWasVault)Native?.enterVaultSecurity?.().catch?.(()=>{});
  else if(!nowVault&&lastShownWasVault)Native?.exitVaultSecurity?.().catch?.(()=>{});
  lastShownWasVault=nowVault;
};
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
function renderNotes(){$("#notesList").innerHTML=notes.length?notes.map(n=>`<article class="note"><h3>${esc(n.title||"Untitled")}</h3><p>${esc(n.body)}</p><small>${new Date(n.createdAt).toLocaleString()}</small></article>`).join(""):`<p class="muted">No notes yet.</p>`}
const thumbCache={};
let selectMode=false, selectedIds=new Set();
function setSelectMode(on){
  selectMode=on; if(!on) selectedIds.clear();
  $("#vaultHeadNormal").classList.toggle("hidden",on);
  $("#vaultHeadSelect").classList.toggle("hidden",!on);
  $("#selectActionBar").classList.toggle("hidden",!on);
  document.querySelectorAll("#vaultGrid .media").forEach(el=>{
    el.classList.toggle("selectable",on);
    el.classList.toggle("selected",on&&selectedIds.has(el.dataset.id));
    if(on&&!el.querySelector(".select-dot")){const dot=document.createElement("span");dot.className="select-dot";el.appendChild(dot)}
  });
  updateSelectCount();
}
function updateSelectCount(){$("#selectCount").textContent=`${selectedIds.size} selected`}
function toggleSelected(id,card){
  if(selectedIds.has(id)){selectedIds.delete(id);card.classList.remove("selected")}
  else{selectedIds.add(id);card.classList.add("selected")}
  updateSelectCount();
}
async function renderVault(){
  if(!Native){$("#vaultGrid").innerHTML="";$("#vaultEmpty").textContent="Private storage is unavailable.";$("#vaultEmpty").style.display="block";return}
  try{
    const r=await Native.listMedia(),items=r.items||[];
    const grid=$("#vaultGrid"),empty=$("#vaultEmpty");
    grid.innerHTML="";
    empty.style.display=items.length?"none":"block";
    for(const m of items){
      const card=document.createElement("button");
      card.className="media media-enter"+(selectMode?" selectable":"")+(selectMode&&selectedIds.has(m.id)?" selected":"");card.dataset.id=m.id;
      card.innerHTML=`<div class="media-placeholder" id="thumb-${esc(m.id)}">${m.mime?.startsWith("video/")?"VIDEO":"PHOTO"}</div><span>${esc(m.name||"MEDIA")}</span>`+(selectMode?`<span class="select-dot"></span>`:"");
      grid.appendChild(card);requestAnimationFrame(()=>card.classList.add("ready"));loadThumb(m);
    }
  }catch(e){$("#vaultGrid").innerHTML="";$("#vaultEmpty").textContent="Private storage is unavailable.";$("#vaultEmpty").style.display="block"}
}
async function loadThumb(m){const key=String(m.id);if(thumbCache[key]){const holder=$("#thumb-"+CSS.escape(key));if(holder)holder.innerHTML=`<img src="${thumbCache[key]}" alt="">`;return}try{const r=await Native.getThumbnail({id:m.id});if(r.data){const uri=`data:${r.mime||"image/jpeg"};base64,${r.data}`;thumbCache[key]=uri;const holder=$("#thumb-"+CSS.escape(key));if(holder)holder.innerHTML=`<img src="${uri}" alt="">`}}catch{}}
async function unlock(){const pin=$("#pinInput").value.trim();if(!/^\d{6}$/.test(pin)||!pendingSecret){$("#unlockMsg").textContent="Enter your 6-digit PIN.";return}try{const r=await Native.verifyCredential({secret:pendingSecret,pin});if(r.ok){pendingSecret="";$("#pinInput").value="";show("#vaultView");await renderVault()}else{$("#unlockMsg").textContent="Wrong PIN.";$("#pinInput").select()}}catch{$("#unlockMsg").textContent="Could not unlock."}}
$("#noteForm").onsubmit=async e=>{e.preventDefault();const title=$("#noteTitle").value.trim(),body=$("#noteBody").value.trim();if(!title&&!body)return;if(state.setup&&Native&&title){try{const r=await Native.verifySecret({secret:title});if(r?.ok){e.target.reset();triggerSecret(title);return}}catch{}}notes.unshift({title,body,createdAt:Date.now()});localStorage.setItem("nyx_notes",JSON.stringify(notes));e.target.reset();renderNotes()};
$("#settingsBtn").onclick=()=>state.setup?show("#settingsView"):show("#setupView");
$("#saveName").onclick=()=>{state.displayName=$("#displayName").value.trim()||"Notes";save();$("#visibleTitle").textContent=state.displayName;show("#notesView")};
$("#vaultSettingsBtn").onclick=async()=>{
  $("#currentSecret").value="";$("#currentPin").value="";$("#newSecret").value="";$("#newPin").value="";$("#securityMsg").textContent="";
  $("#bioConfirmSecret").value="";$("#bioConfirmPin").value="";$("#biometricSettingsMsg").textContent="";$("#biometricConfirmBox").classList.add("hidden");
  show("#vaultSettingsView");
  await refreshBiometricToggle();
  await refreshSecureScreenToggle();
};
async function refreshBiometricToggle(){
  const toggle=$("#biometricToggle");
  if(!Native){toggle.checked=false;toggle.disabled=true;return}
  try{const s=await Native.getPrivateSettings();toggle.disabled=!s?.biometricAvailable;toggle.checked=!!s?.biometricEnabled}
  catch(e){toggle.checked=false}
}
async function refreshSecureScreenToggle(){
  const toggle=$("#secureScreenToggle");
  if(!Native){toggle.checked=true;toggle.disabled=true;return}
  try{const s=await Native.getPrivateSettings();toggle.checked=s?.blockScreenCapture!==false}
  catch(e){toggle.checked=true}
}
$("#secureScreenToggle").addEventListener("change",async e=>{
  const on=e.target.checked;
  $("#secureScreenMsg").textContent="";
  try{await Native.setSecureScreen({blockScreenCapture:on});$("#secureScreenMsg").textContent=on?"Screenshots and screen recording are now blocked.":"Screenshots and screen recording are now allowed."}
  catch(err){$("#secureScreenMsg").textContent=err?.message||"Could not change this setting.";e.target.checked=!on}
});
$("#biometricToggle").addEventListener("change",async e=>{
  const on=e.target.checked;
  $("#biometricSettingsMsg").textContent="";
  if(on){
    e.target.checked=false; // stays off until PIN is confirmed
    $("#bioConfirmSecret").value="";$("#bioConfirmPin").value="";
    $("#biometricConfirmBox").classList.remove("hidden");
  }else{
    try{await Native.disableBiometric();$("#biometricSettingsMsg").textContent="Fingerprint unlock turned off."}
    catch(err){$("#biometricSettingsMsg").textContent=err?.message||"Could not turn off fingerprint unlock.";e.target.checked=true}
  }
});
$("#bioConfirmCancelBtn").onclick=()=>{$("#biometricConfirmBox").classList.add("hidden");$("#biometricSettingsMsg").textContent=""};
$("#bioConfirmBtn").onclick=async()=>{
  const secret=$("#bioConfirmSecret").value.trim(),pin=$("#bioConfirmPin").value.trim();
  $("#biometricSettingsMsg").textContent="";
  if(!secret||!/^[0-9]{6}$/.test(pin)){$("#biometricSettingsMsg").textContent="Enter your secret name and 6-digit PIN.";return}
  try{
    await Native.enrollBiometric({secret,pin});
    $("#biometricConfirmBox").classList.add("hidden");
    $("#biometricToggle").checked=true;
    $("#biometricSettingsMsg").textContent="Fingerprint unlock enabled.";
  }catch(e){$("#biometricSettingsMsg").textContent=e?.message||"Could not enable fingerprint unlock."}
};
$("#changeCredentialBtn").onclick=async()=>{
  const oldSecret=$("#currentSecret").value.trim(),oldPin=$("#currentPin").value.trim(),newSecret=$("#newSecret").value.trim(),newPin=$("#newPin").value.trim();
  $("#securityMsg").textContent="";
  if(!oldSecret||!/^[0-9]{6}$/.test(oldPin)){$("#securityMsg").textContent="Enter your current secret name and PIN.";return}
  if(!newSecret&&!newPin){$("#securityMsg").textContent="Enter a new secret name or PIN.";return}
  if(newPin&&!/^[0-9]{6}$/.test(newPin)){$("#securityMsg").textContent="New PIN must be exactly 6 digits.";return}
  try{
    Native=await waitForNative(8000);if(!Native)throw new Error("Private storage could not start.");
    await Native.changeCredential({oldSecret,oldPin,newSecret,newPin});
    $("#securityMsg").textContent="Security details changed.";
    $("#currentSecret").value="";$("#currentPin").value="";$("#newSecret").value="";$("#newPin").value="";
  }catch(e){$("#securityMsg").textContent=e?.message||"Could not change security details."}
};
document.querySelectorAll("[data-back]").forEach(b=>b.onclick=()=>show("#"+b.dataset.back));
$("#openUploadDebugBtn").onclick=async()=>{show("#uploadDebugView");await refreshUploadDebug()};
async function refreshUploadDebug(){if(!Native)return;try{const r=await Native.getUploadDebugLog();$("#uploadDebugText").textContent=r?.log||"(empty)"}catch(e){$("#uploadDebugText").textContent="Could not read log."}}
$("#refreshUploadDebugBtn").onclick=refreshUploadDebug;
$("#clearUploadDebugBtn").onclick=async()=>{if(!Native)return;try{await Native.clearUploadDebugLog();await refreshUploadDebug()}catch(e){}};
let setupBusy=false;function setSetupBusy(busy,message){setupBusy=busy;const btn=$("#finishSetup");if(!btn)return;btn.disabled=busy;btn.textContent=busy?(message||"Setting up…"):"Finish setup"}
$("#finishSetup").onclick=async()=>{if(setupBusy)return;const secret=$("#secretName").value.trim(),pin=$("#pin").value.trim();$("#setupMsg").textContent="";if(!secret||!/^\d{6}$/.test(pin)){$("#setupMsg").textContent="Enter a secret name and a valid 6-digit PIN.";return}setSetupBusy(true,"Setting up…");try{Native=await waitForNative(8000);if(!Native)throw new Error("Private storage could not start.");await Native.saveCredential({secret,pin,removeOriginal:false});state.setup=true;save();pendingSetupCred={secret,pin};let canBio=false;try{const s=await Native.getPrivateSettings();canBio=!!s?.biometricAvailable}catch(e){}show(canBio?"#biometricSetupView":"#notesView")}catch(e){$("#setupMsg").textContent=e?.message||"Could not finish setup."}finally{setSetupBusy(false)}};
let pendingSetupCred=null;
$("#enableBiometricBtn").onclick=async()=>{if(!Native||!pendingSetupCred)return;$("#biometricSetupMsg").textContent="";try{await Native.enrollBiometric(pendingSetupCred);pendingSetupCred=null;show("#notesView")}catch(e){$("#biometricSetupMsg").textContent=e?.message||"Could not set up fingerprint. You can try again later in Settings."}};
$("#skipBiometricBtn").onclick=()=>{pendingSetupCred=null;show("#notesView")};
function triggerSecret(secret){if(!state.setup||!Native)return;pendingSecret=secret;$("#pinInput").value="";$("#unlockMsg").textContent="";show("#unlockView");setTimeout(()=>$("#pinInput").focus(),80)}
$("#pinGo").onclick=unlock;$("#pinInput").addEventListener("keydown",e=>{if(e.key==="Enter")unlock()});
$("#biometricBtn").onclick=async()=>{if(!Native)return;try{await Native.authenticateBiometric();pendingSecret="";show("#vaultView");await renderVault()}catch(e){$("#unlockMsg").textContent=e?.message||"Biometric authentication failed."}};
$("#lockBtn").onclick=async()=>{pendingSecret="";activeMediaId="";setSelectMode(false);show("#notesView");Native?.clearTempCache?.().catch?.(()=>{})};
$("#importBtn").onclick=()=>show("#pickerView");
async function syncUploads(){try{if(Native) await Native.syncUploads()}catch(e){}}
async function choosePicker(source){try{const r=await Native.pickMedia({source});if(r?.imported){await syncUploads();await renderVault();setTimeout(()=>show("#vaultView"),200)}}catch(e){}}
$("#pickPhotos").onclick=()=>choosePicker("photos");$("#pickFiles").onclick=()=>choosePicker("files");
let pressTimer=null,pressFiredLongPress=false;
$("#vaultGrid").addEventListener("pointerdown",e=>{
  const card=e.target.closest(".media"); if(!card||!Native)return;
  pressFiredLongPress=false;
  pressTimer=setTimeout(()=>{
    pressFiredLongPress=true;
    if(!selectMode)setSelectMode(true);
    toggleSelected(card.dataset.id,card);
    if(navigator.vibrate)navigator.vibrate(15);
  },500);
});
["pointerup","pointerleave","pointercancel"].forEach(evt=>$("#vaultGrid").addEventListener(evt,()=>{clearTimeout(pressTimer)}));
$("#vaultGrid").addEventListener("click",async e=>{
  const card=e.target.closest(".media"); if(!card||!Native)return;
  if(pressFiredLongPress){pressFiredLongPress=false;return}
  if(selectMode){toggleSelected(card.dataset.id,card);return}
  try{await Native.openMedia({id:card.dataset.id})}catch(e){}
});
$("#selectCancelBtn").onclick=()=>setSelectMode(false);
$("#selectAllBtn").onclick=()=>{
  const cards=document.querySelectorAll("#vaultGrid .media");
  const allSelected=selectedIds.size===cards.length&&cards.length>0;
  if(allSelected){selectedIds.clear();cards.forEach(c=>c.classList.remove("selected"))}
  else{cards.forEach(c=>{selectedIds.add(c.dataset.id);c.classList.add("selected")})}
  updateSelectCount();
};
$("#deleteSelectedBtn").onclick=async()=>{
  if(!Native||selectedIds.size===0)return;
  const ids=Array.from(selectedIds);
  const label=ids.length===1?"this item":`these ${ids.length} items`;
  if(!confirm(`Delete ${label} from the vault? This cannot be undone. The Cloudinary backup, if any, is not affected.`))return;
  try{await Native.deleteMediaBatch({ids})}catch(e){}
  setSelectMode(false);await renderVault();
};
$("#restoreSelectedBtn").onclick=async()=>{
  if(!Native||selectedIds.size===0)return;
  const ids=Array.from(selectedIds);
  const label=ids.length===1?"this item":`these ${ids.length} items`;
  if(!confirm(`Move ${label} back to your regular device storage and remove from the vault? The Cloudinary backup, if any, is not affected.`))return;
  try{await Native.restoreMediaBatch({ids})}catch(e){}
  setSelectMode(false);await renderVault();
};
$("#visibleTitle").textContent=state.displayName||"Notes";$("#displayName").value=state.displayName||"Notes";renderNotes();
function currentViewId(){const active=document.querySelector(".view.active");return active?"#"+active.id:null}
function isVaultView(id){return id&&VAULT_VIEWS.has(id)}
function forceLock(){
  pendingSecret="";activeMediaId="";vaultLockPending=false;setSelectMode?.(false);
  $("#biometricConfirmBox")?.classList.add("hidden");
  show("#notesView");
  Native?.clearTempCache?.().catch?.(()=>{});
}
function setupAppStateAutoLock(){
  const AppPlugin=window.Capacitor?.Plugins?.App;
  if(!AppPlugin?.addListener)return;
  AppPlugin.addListener("appStateChange",({isActive})=>{
    if(!isActive){
      if(isVaultView(currentViewId()))vaultLockPending=true;
    }else{
      if(vaultLockPending)forceLock();
    }
  });
}
window.addEventListener("load",async()=>{show(state.setup?"#notesView":"#setupView");if(state.setup){Native=await waitForNative(8000);await syncUploads()}setupAppStateAutoLock();setTimeout(()=>$("#splash")?.classList.add("hide"),1200)});
