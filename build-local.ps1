$ErrorActionPreference = 'Stop'
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin" } else { Join-Path $env:ProgramFiles "Android/Android Studio/jbr/bin" }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android/Sdk" }
$bt = "$sdk\build-tools\36.0.0"
$android = "$sdk\platforms\android-37.0\android.jar"
$env:JAVA_HOME = Split-Path $jdk
$env:PATH = "$jdk;$env:PATH"
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force build\classes,build\dex,build\keys,build\temp | Out-Null
$env:TEMP = "$PSScriptRoot\build\temp"
$env:TMP = $env:TEMP
function Run($program, $arguments) { & $program @arguments; if ($LASTEXITCODE -ne 0) { throw "Build step failed: $program" } }
$sources = @(Get-ChildItem 'app\src\main\java\com\leander\catisland\*.java' | ForEach-Object FullName)
Run "$jdk\javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$android,'-d','build\classes') + $sources)
Run "$jdk\jar.exe" @('cf','build\classes.jar','-C','build\classes','.')
Run "$bt\d8.bat" @('--lib',$android,'--min-api','26','--output','build\dex','build\classes.jar')
$manifest = [IO.File]::ReadAllText("$PSScriptRoot\app\src\main\AndroidManifest.xml").Replace('<manifest ', '<manifest package="com.leander.catisland" android:versionCode="1" android:versionName="0.1" ')
$manifest = $manifest.Replace('<application ', '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application ')
[IO.File]::WriteAllText("$PSScriptRoot\build\AndroidManifest.xml", $manifest)
Run "$bt\aapt.exe" @('package','-f','-M','build\AndroidManifest.xml','-S','app\src\main\res','-A','app\src\main\assets','-I',$android,'-F','build\unsigned.apk')
Run "$jdk\jar.exe" @('uf','build\unsigned.apk','-C','build\dex','classes.dex')
Run "$bt\zipalign.exe" @('-f','4','build\unsigned.apk','build\aligned.apk')
if (!(Test-Path 'build\keys\debug.keystore')) {
    Run "$jdk\keytool.exe" @('-genkeypair','-keystore','build\keys\debug.keystore','-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Cat Island Development','-keyalg','RSA','-validity','10000')
}
Run "$bt\apksigner.bat" @('sign','--ks','build\keys\debug.keystore','--ks-pass','pass:android','--out','build\CatIsland-demo.apk','build\aligned.apk')
Run "$bt\apksigner.bat" @('verify','build\CatIsland-demo.apk')
