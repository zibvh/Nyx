const fs=require('fs');
const path=require('path');
const root=path.resolve('android');
const pkg=path.join(root,'app','src','main','java','app','nyxvault');
fs.mkdirSync(pkg,{recursive:true});
for(const f of ['NyxVaultPlugin.java','NyxMediaViewerActivity.java','NyxUploadWorker.java']) fs.copyFileSync(path.join('android-template','app','src','main','java','app','nyxvault',f),path.join(pkg,f));
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
  if(!m.includes('android.permission.INTERNET')) m=m.replace('<manifest ', '<manifest ');
  if(!m.includes('android:name=\"android.permission.INTERNET\"')) m=m.replace(/<manifest([^>]*)>/, '<manifest$1>\n    <uses-permission android:name=\"android.permission.INTERNET\" />');
  if(!m.includes('android:name=\"android.permission.WRITE_EXTERNAL_STORAGE\"')) m=m.replace(/<manifest([^>]*)>/, '<manifest$1>\n    <uses-permission android:name=\"android.permission.WRITE_EXTERNAL_STORAGE\" android:maxSdkVersion=\"28\" />');
  m=m.replace(/android:icon="[^"]+"/,'android:icon="@drawable/nyx_logo"');
  m=m.replace(/android:roundIcon="[^"]+"/,'android:roundIcon="@drawable/nyx_logo"');
    if(!m.includes('NyxMediaViewerActivity')){
    const activity='<activity android:name="app.nyxvault.NyxMediaViewerActivity" android:exported="false" android:screenOrientation="portrait" />';
    m=m.replace('</application>',activity+'</application>');
  }
if(!m.includes('androidx.core.content.FileProvider')){
    const provider='<provider android:name="androidx.core.content.FileProvider" android:authorities="\${applicationId}.nyxfiles" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/nyx_file_paths" /></provider>';
    m=m.replace('</application>',provider+'</application>');
  }
  fs.writeFileSync(manifest,m);
}

const gradle=path.join(root,'app','build.gradle');
let g=fs.readFileSync(gradle,'utf8');
if(!g.includes('androidx.biometric:biometric')){
  g=g.replace(/dependencies \{/,`dependencies {\n    implementation 'androidx.biometric:biometric:1.1.0'\n    implementation 'androidx.media3:media3-exoplayer:1.5.1'
    implementation 'androidx.media3:media3-ui:1.5.1'
    implementation 'androidx.work:work-runtime:2.9.1'`);
}
// Configure release signing. GitHub Actions recreates nyx-release.jks inside android/
// from the NYX_KEYSTORE_BASE64 repository secret before running this script.
if(!g.includes('NYX_KEYSTORE_PASSWORD')){
  g=g.replace(/android \{/, `android {
    signingConfigs {
        release {
            storeFile file("$rootDir/nyx-release.jks")
            storePassword System.getenv("NYX_KEYSTORE_PASSWORD")
            keyAlias System.getenv("NYX_KEY_ALIAS")
            keyPassword System.getenv("NYX_KEY_PASSWORD")
        }
    }`);
  g=g.replace(/buildTypes \{\s*release \{/, `buildTypes {\n        release {\n            signingConfig signingConfigs.release`);
}
fs.writeFileSync(gradle,g);
