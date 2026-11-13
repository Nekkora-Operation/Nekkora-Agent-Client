<#
.SYNOPSIS
  Gera o pacote de TESTE do Nekkora Agent (Client) para Windows x64: imagem de aplicativo com runtime Java proprio.

.DESCRIPTION
  1. Construcao (mvnw clean verify; -SkipTests pula os testes).
  2. Icone do executavel a partir dos PNGs oficiais (tools\IconFromPng.java).
  3. Conferencia dos modulos do runtime (jdeps) contra a lista abaixo.
  4. Runtime reduzido (jlink).
  5. Imagem de aplicativo (jpackage), sem instalador.
  6. Licencas, LEIA-ME e VERSOES.txt (commit lido do Git AQUI, nesta pasta de trabalho).
  7. Conferencia do pacote: nada administrativo, de laboratorio ou de teste dentro dele.
  8. ZIP e SHA-256.

  Saida: target\dist\nekkora-agent-client-<versao>-windows-x64-teste.zip (+ .sha256) e target\dist\VERSOES.txt.
  Sem assinatura digital. Nao instala nada nem altera a maquina.
#>
param([switch]$SkipTests, [switch]$NoZip)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Nome do executavel e da pasta: proprio do Client, para nao se confundir com o "Nekkora Agent" administrativo ja
# instalado. Na tela (titulo da janela e descricao do arquivo) o nome e "Nekkora Agent".
$AppName = 'Nekkora Agent Client'
$MainClass = 'com.nekkora.client.ClientMain'
# Modulos do runtime: o que o jdeps encontra, mais o que e carregado por servico.
#  - java.desktop: Swing/AWT e ImageIO
#  - java.net.http: cliente HTTPS da entrada (so o pacote auth)
#  - jdk.httpserver: retorno local do login, so em 127.0.0.1 (so o pacote auth)
#  - jdk.crypto.ec: curvas elipticas do TLS no JDK 21 (provedor)
#  - jdk.accessibility: Java Access Bridge (leitores de tela no Windows)
#  - jdk.unsupported, java.logging, java.prefs: FlatLaf
$Modules = @('java.base', 'java.desktop', 'java.logging', 'java.prefs', 'java.net.http', 'jdk.httpserver', 'jdk.crypto.ec',
    'jdk.accessibility', 'jdk.unsupported')
$JvmOptions = @('-Dflatlaf.nativeLibraryPath=$APPDIR\lib', '-XX:+UseSerialGC', '-Xms16m', '-Xmx192m', '-XX:TieredStopAtLevel=1')

function Invoke-Checked([string]$what, [scriptblock]$block) {
    & $block
    if ($LASTEXITCODE -ne 0) { throw "$what falhou ($LASTEXITCODE)" }
}

$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source) }
$jbin = Join-Path $javaHome 'bin'
foreach ($t in 'jlink.exe', 'jpackage.exe', 'jdeps.exe') { if (-not (Test-Path (Join-Path $jbin $t))) { throw "JDK sem $t em $jbin" } }

# commit desta pasta de trabalho (clone ou worktree), lido aqui e nao por plugin
$commit = (& git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'git rev-parse falhou' }
$commitTime = (& git show -s --format=%cI HEAD).Trim()
$dirty = [bool](& git status --porcelain --untracked-files=no)

Write-Host '[1/8] Construcao (mvnw clean verify)'
$mvnArgs = '-B -q clean verify'
if ($SkipTests) { $mvnArgs = '-B -q -DskipTests clean verify' }
Invoke-Checked 'mvnw' { cmd.exe /d /c ".\mvnw.cmd $mvnArgs" }

[xml]$pom = Get-Content -Encoding UTF8 'pom.xml'
$version = $pom.project.version
$pkg = Join-Path $root 'target\package'
$dist = Join-Path $root 'target\dist'
foreach ($d in $pkg, $dist) { if (Test-Path $d) { Remove-Item -Recurse -Force $d } }
New-Item -ItemType Directory -Force (Join-Path $pkg 'input\lib'), $dist | Out-Null
Copy-Item 'target\nekkora-agent-client.jar' (Join-Path $pkg 'input')
Copy-Item 'target\lib\*.jar' (Join-Path $pkg 'input\lib')

# biblioteca nativa do FlatLaf (barra de titulo): extraida do proprio JAR, para nao ser descompactada em pasta temporaria
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Export-Entry([string]$jar, [string]$entry, [string]$target) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $e = $z.GetEntry($entry)
        if ($null -eq $e) { throw "$entry ausente em $jar" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
    } finally { $z.Dispose() }
}
$flat = Get-ChildItem (Join-Path $pkg 'input\lib\flatlaf-*.jar') | Select-Object -First 1
$flatVersion = $flat.BaseName.Substring('flatlaf-'.Length)
Export-Entry $flat.FullName 'com/formdev/flatlaf/natives/flatlaf-windows-x86_64.dll' (Join-Path $pkg "input\lib\flatlaf-$flatVersion-windows-x86_64.dll")

Write-Host '[2/8] Icone (PNGs oficiais)'
$ico = Join-Path $pkg 'nekkora-agent-client.ico'
$brand = 'src\main\resources\com\nekkora\client\brand'
$pngs = 16, 24, 32, 48, 64, 128, 256 | ForEach-Object { Join-Path $brand "nekkora-app-icon-$_.png" }
Invoke-Checked 'IconFromPng' { & (Join-Path $jbin 'java.exe') '-Djava.awt.headless=true' 'tools\IconFromPng.java' $ico @pngs }

Write-Host '[3/8] Modulos (jdeps)'
$found = (& (Join-Path $jbin 'jdeps.exe') --print-module-deps --ignore-missing-deps --multi-release 21 `
        --class-path "$pkg\input\lib\*" "$pkg\input\nekkora-agent-client.jar").Trim()
if ($LASTEXITCODE -ne 0) { throw 'jdeps falhou' }
foreach ($m in $found.Split(',')) { if ($Modules -notcontains $m) { throw "jdeps encontrou o modulo '$m', ausente da lista do runtime" } }
Write-Host "      jdeps: $found"

Write-Host '[4/8] Runtime (jlink)'
$runtime = Join-Path $pkg 'runtime'
Invoke-Checked 'jlink' { & (Join-Path $jbin 'jlink.exe') --add-modules ($Modules -join ',') --strip-debug --no-header-files --no-man-pages `
        --compress=zip-6 --output $runtime }

Write-Host '[5/8] Imagem de aplicativo (jpackage)'
$image = Join-Path $pkg 'image'
$jp = @('--type', 'app-image', '--name', $AppName, '--app-version', $version, '--vendor', 'Nekkora', '--description', 'Nekkora Agent',
    '--copyright', 'Nekkora', '--icon', $ico, '--input', (Join-Path $pkg 'input'), '--main-jar', 'nekkora-agent-client.jar',
    '--main-class', $MainClass, '--runtime-image', $runtime, '--dest', $image)
foreach ($o in $JvmOptions) { $jp += @('--java-options', $o) }
Invoke-Checked 'jpackage' { & (Join-Path $jbin 'jpackage.exe') @jp }
$app = Join-Path $image $AppName

Write-Host '[6/8] Licencas, LEIA-ME e versoes'
$legal = Join-Path $app 'legal'
New-Item -ItemType Directory -Force $legal | Out-Null
Copy-Item 'THIRD-PARTY-NOTICES.md' $legal
Copy-Item 'tools\distribution\LEIA-ME.txt' $app
Export-Entry $flat.FullName 'META-INF/LICENSE' (Join-Path $legal 'FlatLaf-LICENSE-Apache-2.0.txt')
$release = Get-Content (Join-Path $runtime 'release') | Where-Object { $_ -match '^(JAVA_VERSION|IMPLEMENTOR|OS_ARCH|MODULES)=' }
$lines = @("Nekkora Agent (Client) $version - pacote de TESTE (Windows x64, sem assinatura digital)",
    "commit: $commit$(if ($dirty) { ' (com alteracoes locais)' })",
    "data do commit: $commitTime",
    "gerado em: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))",
    '',
    'Bibliotecas (app) com SHA-256:')
foreach ($j in Get-ChildItem "$pkg\input\nekkora-agent-client.jar", "$pkg\input\lib\*.jar", "$pkg\input\lib\*.dll") {
    $lines += "  $($j.Name)  $((Get-FileHash -Algorithm SHA256 $j.FullName).Hash.ToLowerInvariant())"
}
$lines += ''
$lines += 'Runtime Java incluido (jlink):'
$lines += ($release | ForEach-Object { "  $_" })
$lines += ''
$lines += "Opcoes da JVM: $($JvmOptions -join ' ')"
$lines += 'Tokens e logos: Nekkora-lib 0.2.0, commit 0a29d95fbadb19da74cfa90a55542fb14885458d (docs/design/tokens-e-ativos.md)'
[System.IO.File]::WriteAllLines((Join-Path $app 'VERSOES.txt'), [string[]]$lines, (New-Object System.Text.UTF8Encoding $false))

Write-Host '[7/8] Conferencia do pacote'
# so as bibliotecas esperadas
$libs = @(Get-ChildItem (Join-Path $app 'app') -Recurse -File -Include '*.jar', '*.dll' | ForEach-Object { $_.Name } | Sort-Object)
$expected = @("flatlaf-$flatVersion-windows-x86_64.dll", "flatlaf-$flatVersion.jar", 'nekkora-agent-client.jar') | Sort-Object
if (Compare-Object $libs $expected) { throw "bibliotecas inesperadas no pacote: $($libs -join ', ')" }
# nada administrativo, de teste ou de laboratorio dentro do JAR do aplicativo
$z = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $app 'app\nekkora-agent-client.jar'))
try {
    $names = @($z.Entries | ForEach-Object { $_.FullName })
} finally { $z.Dispose() }
$bad = @($names | Where-Object {
        $_ -like 'com/nekkora/agent/*' -or $_ -match '(?i)(Fake|Mock|Stub|Test)[A-Za-z]*\.class$' -or
        $_ -match '(?i)(Import|Catalog|Execution|Pairing|Worker|Approval|Package|Supervis)[A-Za-z]*\.class$' -or
        $_ -match '(?i)\.(p12|pem|jks|pfx|cfg)$' -or $_ -like 'nekkora-lib/*' })
if ($bad.Count -gt 0) { throw "conteudo indevido no JAR: $($bad -join ', ')" }
foreach ($n in $names | Where-Object { $_ -like '*.class' }) {
    if ($n -notlike 'com/nekkora/client/*') { throw "classe fora de com.nekkora.client: $n" }
}
# nenhum arquivo de configuracao aponta para laboratorio nem relaxa o TLS
foreach ($c in Get-ChildItem -Recurse -File -Filter '*.cfg' $app) {
    $text = Get-Content -Raw $c.FullName
    if ($text -match '(?i)localhost|127\.0\.0\.1|trustStore|nekkora\.client\.platformOrigin|frontlab') { throw "configuracao de laboratorio em $($c.Name)" }
}
if (Get-ChildItem -Recurse -File $app -Include '*.p12', '*.pem', '*.jks', '*.pfx') { throw 'certificado ou repositorio de chaves dentro do pacote' }
Write-Host "      conferido: $($libs.Count) bibliotecas, $(@($names | Where-Object { $_ -like '*.class' }).Count) classes, sem conteudo administrativo, de teste ou de laboratorio"

if ($NoZip) {
    Copy-Item -Recurse $app (Join-Path $dist $AppName)
    Copy-Item (Join-Path $app 'VERSOES.txt') (Join-Path $dist 'VERSOES.txt')
    Write-Host "[8/8] sem ZIP (-NoZip): pasta $(Join-Path $dist $AppName)"
    exit 0
}
Write-Host '[8/8] ZIP e SHA-256'
$zipName = "nekkora-agent-client-$version-windows-x64-teste.zip"
$zip = Join-Path $dist $zipName
Copy-Item -Recurse $app (Join-Path $dist $AppName)
[System.IO.Compression.ZipFile]::CreateFromDirectory($app, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)
$hash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText("$zip.sha256", "$hash  $zipName`n", (New-Object System.Text.UTF8Encoding $false))
Copy-Item (Join-Path $app 'VERSOES.txt') (Join-Path $dist 'VERSOES.txt')
$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host ''
Write-Host "Pacote: $zip ($size MiB)"
Write-Host "SHA-256: $hash"
Write-Host "Commit: $commit$(if ($dirty) { ' (com alteracoes locais)' })"
