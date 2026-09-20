# Thin wrapper: downloads shaded JAR once, then runs java -jar.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AivArgs
)
$ErrorActionPreference = "Stop"
$Version = if ($env:AIV_VERSION) { $env:AIV_VERSION } else { "1.0.4" }
$Cache = if ($env:AIV_HOME) { $env:AIV_HOME } else { Join-Path $HOME ".aiv" }
$Jar = if ($env:AIV_CLI_JAR) { $env:AIV_CLI_JAR } else { Join-Path $Cache "aiv-cli-$Version.jar" }
New-Item -ItemType Directory -Force -Path (Split-Path $Jar) | Out-Null
if (-not (Test-Path $Jar)) {
    $url = "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/$Version/aiv-cli-$Version.jar"
    Invoke-WebRequest -Uri $url -OutFile $Jar
}
& java -jar $Jar @AivArgs
exit $LASTEXITCODE
