# Script auxiliar para compilar o APK de Debug do Zangi Chat
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$gradleBat = "C:\Users\santw\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat"

Write-Host "=========================================" -ForegroundColor Green
Write-Host " Compilando Zangi Chat Android (Debug) " -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green

& $gradleBat assembleDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nAPK gerado com sucesso em:" -ForegroundColor Green
    Write-Host "app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Cyan
} else {
    Write-Host "`nFalha na compilação. Verifique os logs acima." -ForegroundColor Red
}
