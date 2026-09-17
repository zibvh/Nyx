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
const show=id=>{document.querySelectorAll(".view").forEach(x=>x.classList.remove("active"));$(id).classList.add("active");const settings=$("#settingsBtn");if(settings)settings.style.display=id==="#notesView"?"":"none"};
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
function renderNotes(){$("#notesList").innerHTML=notes.length?notes.map(n=>`<article class="note"><h3>${esc(n.title||"Untitled")}</h3><p>${esc(n.body)}</p><small>${new Date(n.createdAt).toLocaleString()}</small></article>`).join(""):`<p class="muted">No notes yet.</p>`}
const thumbCache={};
async function renderVault(){if(!Native){$("#vaultGrid").innerHTML='<p class="muted">Private storage is available in the Android build.</p>';return}try{const r=await Native.listMedia(),items=r.items||[];const grid=$("#vaultGrid");if(!items.length){grid.innerHTML='<p class="muted">No private media yet.</p>';return}const wanted=new Set(items.map(m=>String(m.id)));grid.querySelectorAll('.media').forEach(card=>{if(!wanted.has(String(card.dataset.id)))card.remove()});const existing=new Set([...grid.querySelectorAll('.media')].map(x=>String(x.dataset.id)));for(const m of items){if(existing.has(String(m.id))){const label=grid.querySelector(`[data-id="${CSS.escape(String(m.id))}"] span`);if(label)label.textContent=m.name||"MEDIA";continue;}const card=document.createElement('button');card.className='media media-enter';card.dataset.id=m.id;card.innerHTML=`<div class="media-placeholder" id="thumb-${esc(m.id)}">${m.mime?.startsWith("video/")?"VIDEO":"PHOTO"}</div><span>${esc(m.name||"MEDIA")}</span>`;grid.appendChild(card);requestAnimationFrame(()=>card.classList.add('ready'));loadThumb(m)}}catch{$("#vaultGrid").innerHTML='<p class="muted">No private media yet.</p>'}}
async function loadThumb(m){const key=String(m.id);if(thumbCache[key]){const holder=$("#thumb-"+CSS.escape(key));if(holder)holder.innerHTML=`<img src="${thumbCache[key]}" alt="">`;return}try{const r=await Native.getThumbnail({id:m.id});if(r.data){const uri=`data:${r.mime||"image/jpeg"};base64,${r.data}`;thumbCache[key]=uri;const holder=$("#thumb-"+CSS.escape(key));if(holder)holder.innerHTML=`<img src="${uri}" alt="">`}}catch{}}
async function unlock(){const pin=$("#pinInput").value.trim();if(!/^\d{6}$/.test(pin)||!pendingSecret){$("#unlockMsg").textContent="Enter your 6-digit PIN.";return}try{const r=await Native.verifyCredential({secret:pendingSecret,pin});if(r.ok){pendingSecret="";$("#pinInput").value="";show("#vaultView");await renderVault()}else{$("#unlockMsg").textContent="Wrong PIN.";$("#pinInput").select()}}catch{$("#unlockMsg").textContent="Could not unlock."}}
$("#noteForm").onsubmit=async e=>{e.preventDefault();const title=$("#noteTitle").value.trim(),body=$("#noteBody").value.trim();if(!title&&!body)return;if(state.setup&&Native&&title){try{const r=await Native.verifySecret({secret:title});if(r?.ok){e.target.reset();triggerSecret(title);return}}catch{}}notes.unshift({title,body,createdAt:Date.now()});localStorage.setItem("nyx_notes",JSON.stringify(notes));e.target.reset();renderNotes()};
$("#settingsBtn").onclick=()=>state.setup?show("#settingsView"):show("#setupView");$("#saveName").onclick=()=>{state.displayName=$("#displayName").value.trim()||"Notes";save();$("#visibleTitle").textContent=state.displayName;show("#notesView")};
document.querySelectorAll("[data-back]").forEach(b=>b.onclick=()=>show("#"+b.dataset.back));
let setupBusy=false;function setSetupBusy(busy,message){setupBusy=busy;const btn=$("#finishSetup");if(!btn)return;btn.disabled=busy;btn.textContent=busy?(message||"Setting up…"):"Finish setup"}
$("#finishSetup").onclick=async()=>{if(setupBusy)return;const secret=$("#secretName").value.trim(),pin=$("#pin").value.trim();$("#setupMsg").textContent="";if(!secret||!/^\d{6}$/.test(pin)){$("#setupMsg").textContent="Enter a secret name and a valid 6-digit PIN.";return}setSetupBusy(true,"Setting up…");try{Native=await waitForNative(8000);if(!Native)throw new Error("Private storage could not start.");await Native.saveCredential({secret,pin,removeOriginal:false});state.setup=true;save();show("#notesView")}catch(e){$("#setupMsg").textContent=e?.message||"Could not finish setup."}finally{setSetupBusy(false)}};
function triggerSecret(secret){if(!state.setup||!Native)return;pendingSecret=secret;$("#pinInput").value="";$("#unlockMsg").textContent="";show("#unlockView");setTimeout(()=>$("#pinInput").focus(),80)}
$("#pinGo").onclick=unlock;$("#pinInput").addEventListener("keydown",e=>{if(e.key==="Enter")unlock()});
$("#biometricBtn").onclick=async()=>{if(!Native)return;try{await Native.authenticateBiometric();pendingSecret="";show("#vaultView");await renderVault()}catch(e){$("#unlockMsg").textContent=e?.message||"Biometric authentication failed."}};
$("#lockBtn").onclick=async()=>{pendingSecret="";activeMediaId="";show("#notesView");Native?.clearTempCache?.().catch?.(()=>{})};
$("#importBtn").onclick=()=>show("#pickerView");
async function choosePicker(source){try{const r=await Native.pickMedia({source});if(r?.imported){await renderVault();setTimeout(()=>show("#vaultView"),200)}}catch(e){}}
$("#pickPhotos").onclick=()=>choosePicker("photos");$("#pickFiles").onclick=()=>choosePicker("files");
$("#vaultGrid").addEventListener("click",async e=>{const b=e.target.closest(".media");if(!b||!Native)return;try{await Native.openMedia({id:b.dataset.id})}catch(e){}});$("#visibleTitle").textContent=state.displayName||"Notes";$("#displayName").value=state.displayName||"Notes";renderNotes();
window.addEventListener("load",()=>{show(state.setup?"#notesView":"#setupView");setTimeout(()=>$("#splash")?.classList.add("hide"),1200)});
