const fs=require('fs'), path=require('path');
const ios=path.resolve('ios');
const appDir=path.join(ios,'App');
function find(name,dir){ if(!fs.existsSync(dir)) return null; for(const e of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name); if(e.isDirectory()){const x=find(name,p); if(x)return x;} else if(e.name===name)return p;} return null; }
for(const f of ['NyxVaultPlugin.swift','NyxMediaViewerViewController.swift']) fs.copyFileSync(path.join('ios-template',f),path.join(appDir,f));
let delegate=find('AppDelegate.swift',appDir);
if(!delegate) throw new Error('AppDelegate.swift not found');
let s=fs.readFileSync(delegate,'utf8');
if(!s.includes('NyxVaultPlugin')){
  if(s.includes('import Capacitor')) s=s.replace('import Capacitor','import Capacitor\n'); else s='import Capacitor\n'+s;
  const marker='public func application(_ app: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {';
  if(s.includes(marker)) s=s.replace(marker,marker+'\n        bridge?.registerPluginInstance(NyxVaultPlugin())');
  else s=s.replace(/class AppDelegate[^\{]*\{/,'$&\n    override func applicationDidBecomeActive(_ application: UIApplication) { bridge?.registerPluginInstance(NyxVaultPlugin()) }');
  fs.writeFileSync(delegate,s);
}
console.log('iOS NYX native plugin installed');
