$testId = 'tt1234567'
$baseUrl = 'https://playpaste.link/player/'
$html = Invoke-RestMethod -Uri "$baseUrl/embed.php?id=$testId"
Write-Host '✅ HTML obtenido' -ForegroundColor Green

if ($html -match 'details\.php\?id=([A-Za-z0-9_\-]+)') {
    $token = $Matches[1]
    Write-Host "🎫 Token: $token" -ForegroundColor Green
    
    $json = Invoke-RestMethod -Uri "$baseUrl/api/details.php?id=$token"
    Write-Host '✅ JSON obtenido' -ForegroundColor Green
    Write-Host ''
    Write-Host 'JSON completo:' -ForegroundColor Gray
    Write-Host $json -ForegroundColor White
} else {
    Write-Host '❌ Token no encontrado' -ForegroundColor Red
}
