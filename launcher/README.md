# Nodecount TV : le launcher

Appli Android TV : une WebView qui affiche `app/src/main/assets/index.html` (heure, date, message doux, liste des applis).

## Fichiers à modifier
- `app/src/main/assets/messages.txt` : les mots doux, un par ligne (`#` = commentaire). Un message au hasard, changé toutes les 10 min.
- `app/src/main/assets/index.html` : l'affichage.

## Compiler
```powershell
$env:JAVA_HOME = "D:\Android\Android Studio\jbr"
cd launcher
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat" assembleDebug
```
APK : `app\build\outputs\apk\debug\app-debug.apk` (ou ouvrir le dossier dans Android Studio).

## Installer sur la TV
```powershell
& 'D:\Android\platform-tools\adb.exe' -s IP_TV:5555 install -r app\build\outputs\apk\debug\app-debug.apk
```
