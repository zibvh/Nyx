const fs=require('fs');
const path=require('path');
const root=path.resolve('android');
const pkg=path.join(root,'app','src','main','java','app','nyxvault');
fs.mkdirSync(pkg,{recursive:true});
for(const f of ['NyxVaultPlugin.java','NyxUploadWorker.java','CloudinaryConfig.java']) fs.copyFileSync(path.join('android-template','app','src','main','java','app','nyxvault',f),path.join(pkg,f));
function findMain(dir){
  for(const n of fs.readdirSync(dir,{withFileTypes:true})){
    const p=path.join(dir,n.name);
    if(n.isDirectory()){const hit=findMain(p);if(hit)return hit;}
    else if(n.name==='MainActivity.java'||n.name==='MainActivity.kt')return p;
  }
  return null;
}
const main=findMain(path.join(root,'app','src','main','java'));
if(!main) throw new Error('MainActivity not found');
let text=fs.readFileSync(main,'utf8');
const pkgMatch=text.match(/package\s+([^;\n]+);?/);
const javaPackage=pkgMatch ? pkgMatch[1].trim() : null;
if(!javaPackage) throw new Error('MainActivity package not found');

if(main.endsWith('.java')){
  // Replace the generated MainActivity with a deterministic custom-plugin host.
  // The plugin must be registered BEFORE BridgeActivity.onCreate() builds the bridge.
  text=`package ${javaPackage};\n\nimport android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;\nimport app.nyxvault.NyxVaultPlugin;\n\npublic class MainActivity extends BridgeActivity {\n    @Override\n    protected void onCreate(Bundle savedInstanceState) {\n        registerPlugin(NyxVaultPlugin.class);\n        super.onCreate(savedInstanceState);\n    }\n}\n`;
} else {
  text=`package ${javaPackage}\n\nimport android.os.Bundle\nimport com.getcapacitor.BridgeActivity\nimport app.nyxvault.NyxVaultPlugin\n\nclass MainActivity : BridgeActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        registerPlugin(NyxVaultPlugin::class.java)\n        super.onCreate(savedInstanceState)\n    }\n}\n`;
}
fs.writeFileSync(main,text);

// Use the supplied NYX logo as the launcher icon.
const xmlDir=path.join(root,'app','src','main','res','xml');
fs.mkdirSync(xmlDir,{recursive:true});
fs.copyFileSync(path.join('android-template','app','src','main','res','xml','nyx_file_paths.xml'),path.join(xmlDir,'nyx_file_paths.xml'));
const drawable=path.join(root,'app','src','main','res','drawable-nodpi');
fs.mkdirSync(drawable,{recursive:true});
fs.copyFileSync(path.join('android-template','app','src','main','res','drawable-nodpi','nyx_logo.png'),path.join(drawable,'nyx_logo.png'));
const manifest=path.join(root,'app','src','main','AndroidManifest.xml');
if(fs.existsSync(manifest)){
  let m=fs.readFileSync(manifest,'utf8');
  m=m.replace(/android:icon="[^"]+"/,'android:icon="@drawable/nyx_logo"');
  m=m.replace(/android:roundIcon="[^"]+"/,'android:roundIcon="@drawable/nyx_logo"');
  if(!m.includes('androidx.core.content.FileProvider')){
    const provider='<provider android:name="androidx.core.content.FileProvider" android:authorities="\${applicationId}.nyxfiles" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/nyx_file_paths" /></provider>';
    m=m.replace('</application>',provider+'</application>');
  }
  fs.writeFileSync(manifest,m);
}

const gradle=path.join(root,'app','build.gradle');
let g=fs.readFileSync(gradle,'utf8');
if(!g.includes('androidx.biometric:biometric')){
  g=g.replace(/dependencies \{/,`dependencies {\n    implementation 'androidx.biometric:biometric:1.1.0'\n    implementation 'androidx.work:work-runtime:2.10.1'`);
}
// v26 intentionally builds DEBUG only. No release signing or keystore configuration.
fs.writeFileSync(gradle,g);
