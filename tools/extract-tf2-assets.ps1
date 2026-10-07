# Usage: .\extract-tf2-assets.ps1 [-TF2Path <dir>] [-Staging <dir>] [-SkipConvert]
[CmdletBinding()]
param(
    [string] $TF2Path,
    [string] $Staging = "$PSScriptRoot\..\..\tf2-assets-staging",
    [string] $ModId   = "tf2soldier",
    [switch] $SkipConvert,
    [switch] $IncludeNonAudio,
    # Vorbis quality; voice gets 4 since its sources are already lossy MP3.
    [int]    $QualitySfx   = 5,
    [int]    $QualityVoice = 4
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Info($m) { Write-Host "[*] $m" -ForegroundColor Cyan }
function Ok  ($m) { Write-Host "[+] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[!] $m" -ForegroundColor Yellow }

if (-not $TF2Path) {
    $roots = @("${env:ProgramFiles(x86)}\Steam", "$env:ProgramFiles\Steam")
    $vdf = $roots | ForEach-Object { Join-Path $_ 'steamapps\libraryfolders.vdf' } |
           Where-Object { Test-Path $_ } | Select-Object -First 1
    $libs = @($roots)
    if ($vdf) {
        $libs += (Select-String -Path $vdf -Pattern '"path"\s+"(.+?)"' -AllMatches).Matches |
                 ForEach-Object { $_.Groups[1].Value -replace '\\\\', '\' }
    }
    $TF2Path = $libs | ForEach-Object { Join-Path $_ 'steamapps\common\Team Fortress 2' } |
               Where-Object { Test-Path (Join-Path $_ 'tf\tf2_misc_dir.vpk') } | Select-Object -First 1
}
if (-not $TF2Path -or -not (Test-Path $TF2Path)) { throw "TF2 not found. Pass -TF2Path." }

$VpkExe = Join-Path $TF2Path 'bin\vpk.exe'
$TfDir  = Join-Path $TF2Path 'tf'
$Hl2Dir = Join-Path $TF2Path 'hl2'
if (-not (Test-Path $VpkExe)) { throw "vpk.exe missing at $VpkExe" }
Ok "TF2: $TF2Path"

$Raw = Join-Path $Staging 'raw'
$null = New-Item -ItemType Directory -Force -Path $Raw

# vpk.exe extracts into the current directory and does not create folders.
function Invoke-VpkExtract {
    param([string]$Vpk, [string[]]$Files, [string]$Dest)
    if (-not $Files -or $Files.Count -eq 0) { return }
    $Files | ForEach-Object { Split-Path $_ -Parent } | Sort-Object -Unique | ForEach-Object {
        $null = New-Item -ItemType Directory -Force -Path (Join-Path $Dest ($_ -replace '/', '\'))
    }
    Push-Location $Dest
    try {
        # Windows caps a command line at about 32k chars.
        for ($i = 0; $i -lt $Files.Count; $i += 150) {
            $chunk = $Files[$i..([Math]::Min($i + 149, $Files.Count - 1))]
            & $VpkExe x $Vpk @chunk *>$null
        }
    } finally { Pop-Location }
}

function Get-VpkIndex {
    param([string]$Vpk)
    # 'l' lists one lowercase, forward-slash path per line.
    & $VpkExe l $Vpk 2>$null | Where-Object { $_ -match '\S' }
}

Info 'Reading VPK indexes'
$VoVpk  = Join-Path $TfDir  'tf2_sound_vo_english_dir.vpk'
$SfxVpk = Join-Path $TfDir  'tf2_sound_misc_dir.vpk'
$MiscVpk= Join-Path $TfDir  'tf2_misc_dir.vpk'
$TexVpk = Join-Path $TfDir  'tf2_textures_dir.vpk'
$Hl2Sfx = Join-Path $Hl2Dir 'hl2_sound_misc_dir.vpk'

$voIndex  = Get-VpkIndex $VoVpk
$sfxIndex = Get-VpkIndex $SfxVpk

# MvM, comp and taunt lines live in subdirectories and are excluded.
$voFiles = $voIndex | Where-Object { $_ -match '^sound/vo/soldier_[^/]+\.mp3$' }

$sfxPatterns = @(
    '^sound/weapons/rocket_(shoot|shoot_crit|reload)\.wav$'
    '^sound/weapons/draw_primary\.wav$'
    # debris1 is not in tf2_sound_misc; it comes from HL2 below.
    '^sound/weapons/(explode1|explode2|explode3|debris2|debris4)\.wav$'
    # shotgun_cock.wav is in neither VPK; the pump uses cock_back and cock_forward.
    '^sound/weapons/shotgun_(shoot|shoot_crit|reload|worldreload|empty|cock_back|cock_forward)\.wav$'
    '^sound/weapons/draw_secondary\.wav$'
    # Shovel hits reuse the axe and crowbar impacts.
    '^sound/weapons/(shovel_swing|shovel_swing_crit|axe_hit_flesh[123]|cbar_hit[12])\.wav$'
    '^sound/weapons/draw_shovel_soldier\.wav$'
    '^sound/player/footsteps/.+\.wav$'
    '^sound/player/(pain|death|fire|flame_out|crit_hit\d?|crit_hit_mini\d?|crit_received[123]|crit_death[1-5])\.wav$'
    # Rocket jump whistle and landing.
    '^sound/misc/grenade_jump_(lp|fall)_01\.wav$'
)
$sfxFiles = $sfxIndex | Where-Object { $p = $_; $sfxPatterns | Where-Object { $p -match $_ } } | Sort-Object -Unique

# gameinfo.txt mounts hl2 after tf, so debris1.wav resolves from HL2.
$hl2Files = @('sound/weapons/debris1.wav')

Info ("voice {0} | sfx {1} | hl2 {2}" -f $voFiles.Count, $sfxFiles.Count, $hl2Files.Count)

Info 'Extracting audio'
Invoke-VpkExtract -Vpk $VoVpk  -Files $voFiles  -Dest $Raw
Invoke-VpkExtract -Vpk $SfxVpk -Files $sfxFiles -Dest $Raw
Invoke-VpkExtract -Vpk $Hl2Sfx -Files $hl2Files -Dest $Raw

$missing = @(@($voFiles) + @($sfxFiles) + @($hl2Files) | Where-Object {
    -not (Test-Path (Join-Path $Raw ($_ -replace '/', '\'))) })
if ($missing.Count -gt 0) { Warn "missing after extract: $($missing -join ', ')" }
Ok ("extracted {0:N1} MB" -f ((Get-ChildItem $Raw -Recurse -File | Measure-Object Length -Sum).Sum / 1MB))

if ($IncludeNonAudio) {
    Info 'Extracting models / materials / scripts'
    Invoke-VpkExtract -Vpk $MiscVpk -Dest $Raw -Files @(
        # Each .mdl needs its .vvd and .vtx.
        'models/weapons/c_models/c_rocketlauncher/c_rocketlauncher.mdl'
        'models/weapons/c_models/c_rocketlauncher/c_rocketlauncher.vvd'
        'models/weapons/c_models/c_rocketlauncher/c_rocketlauncher.dx90.vtx'
        'models/weapons/c_models/c_rocketlauncher/c_rocketlauncher.phy'
        'models/weapons/c_models/c_soldier_arms.mdl'
        'models/weapons/c_models/c_soldier_arms.vvd'
        'models/weapons/c_models/c_soldier_arms.dx90.vtx'
        'models/weapons/c_models/c_shotgun/c_shotgun.mdl'
        'models/weapons/c_models/c_shotgun/c_shotgun.vvd'
        'models/weapons/c_models/c_shotgun/c_shotgun.dx90.vtx'
        'models/weapons/c_models/c_shovel/c_shovel.mdl'
        'models/weapons/c_models/c_shovel/c_shovel.vvd'
        'models/weapons/c_models/c_shovel/c_shovel.dx90.vtx'
        'models/weapons/w_models/w_rocket.mdl'
        'models/weapons/w_models/w_rocket.vvd'
        'models/weapons/w_models/w_rocket.dx90.vtx'
        # Animations live in a separate mdl with no vvd or vtx.
        'models/player/soldier.mdl'
        'models/player/soldier.vvd'
        'models/player/soldier.dx90.vtx'
        'models/player/soldier_animations.mdl'
        'materials/hud/health_bg.vmt'
        'materials/hud/health_color.vmt'
        'materials/hud/health_over_bg.vmt'
        'materials/hud/ammo_red_bg.vmt'
        'materials/hud/ammo_blue_bg.vmt'
        'resource/clientscheme.res'
        'resource/ui/hudplayerhealth.res'
        'resource/ui/hudammoweapons.res'
        'resource/ui/huddamageaccount.res'
        'scripts/game_sounds_weapons.txt'
        'scripts/game_sounds_player.txt'
        'scripts/game_sounds_vo.txt'
        'scripts/voicecommands.txt'
        'scripts/talker/response_rules.txt'
        'scripts/talker/soldier.txt'
        'scripts/talker/soldier_auto.txt'
        # ICE-encrypted; decode with tools/DecryptCtx.java.
        'scripts/tf_weapon_rocketlauncher.ctx'
        'scripts/tf_weapon_shotgun_soldier.ctx'
        'scripts/tf_weapon_shovel.ctx'
        'scripts/playerclasses/soldier.ctx'
    )
    Invoke-VpkExtract -Vpk $TexVpk -Dest $Raw -Files @(
        'materials/hud/health_bg.vtf'
        'materials/hud/health_color.vtf'
        'materials/hud/health_over_bg.vtf'
        'materials/hud/health_dead.vtf'
        # ammo_red_bg.vmt is gradient_red plus the ammo_area_mask SDF detail.
        'materials/hud/gradient_red.vtf'
        'materials/hud/gradient_blue.vtf'
        'materials/hud/ammo_area_mask.vtf'
        'materials/vgui/crosshairs/crosshair1.vtf'
        'materials/vgui/crosshairs/default.vtf'
        'materials/models/player/soldier/soldier_red.vtf'
        'materials/models/player/soldier/soldier_blue.vtf'
        'materials/models/player/soldier/soldier_head.vtf'
    )
    # Fonts and the item schema ship loose on disk, not in a VPK.
    $null = New-Item -ItemType Directory -Force -Path "$Raw\resource", "$Raw\scripts\items"
    Get-ChildItem "$TfDir\resource" -Filter '*.ttf' -File |
        Copy-Item -Destination "$Raw\resource" -Force
    Copy-Item "$TfDir\scripts\items\items_game.txt" "$Raw\scripts\items\" -Force
    Ok 'non-audio staged'
}

if ($SkipConvert) { Ok "done (extract only): $Raw"; return }

if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    throw "ffmpeg not on PATH. winget install --id Gyan.FFmpeg -e   (then reopen the shell)"
}

$SoundsRoot = Join-Path $Staging "assets\$ModId\sounds"
$null = New-Item -ItemType Directory -Force -Path $SoundsRoot

# Minecraft attenuates only mono sounds by distance, hence -ac 1.
function Convert-One {
    param([string]$Src, [string]$Dst, [int]$Q)
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $Dst -Parent)
    & ffmpeg -hide_banner -loglevel error -y -i $Src -vn -map_metadata -1 `
             -ac 1 -c:a libvorbis -q:a $Q $Dst
    if ($LASTEXITCODE -ne 0) { Warn "ffmpeg failed: $Src" }
}

Info 'Converting to Ogg Vorbis (mono)'
$n = 0
foreach ($f in $voFiles) {
    $name = [IO.Path]::GetFileNameWithoutExtension($f)
    Convert-One (Join-Path $Raw ($f -replace '/', '\')) "$SoundsRoot\voice\$name.ogg" $QualityVoice
    $n++
}
foreach ($f in @($sfxFiles + $hl2Files)) {
    $name = [IO.Path]::GetFileNameWithoutExtension($f)
    # switch -Regex runs every matching branch; break keeps $cat a single value.
    $cat = switch -Regex ($f) {
        '^sound/player/footsteps/' { 'footstep'; break }
        '^sound/player/'           { 'player';   break }
        '^sound/weapons/'          { 'weapon';   break }
        default                    { 'misc'           }
    }
    Convert-One (Join-Path $Raw ($f -replace '/', '\')) "$SoundsRoot\$cat\$name.ogg" $QualitySfx
    $n++
}
Ok ("converted {0} files, {1:N1} MB" -f $n,
    ((Get-ChildItem $SoundsRoot -Recurse -File | Measure-Object Length -Sum).Sum / 1MB))

Info 'Writing sounds.json'
$entries = [ordered]@{}

# Strips a trailing variant number so variants share one event, like rndwave.
$stripVariant = { param($n) $n -replace '_?\d+$', '' }

# Every asset is under 9 seconds, so nothing is streamed.
Get-ChildItem "$SoundsRoot\voice" -Filter '*.ogg' -File |
    Group-Object { & $stripVariant ($_.BaseName -replace '^soldier_', '') } |
    ForEach-Object {
        $entries["voice.$($_.Name)"] = [ordered]@{
            category = 'voice'
            subtitle = "subtitles.$ModId.voice.$($_.Name)"
            sounds   = @($_.Group | Sort-Object Name | ForEach-Object {
                [ordered]@{ name = "$ModId`:voice/$($_.BaseName)"; stream = $false } })
        }
    }

foreach ($cat in 'weapon', 'player', 'footstep', 'misc') {
    $dir = Join-Path $SoundsRoot $cat
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem $dir -Filter '*.ogg' -File |
        Group-Object { & $stripVariant $_.BaseName } |
        ForEach-Object {
            $entries["$cat.$($_.Name)"] = [ordered]@{
                category = 'player'
                subtitle = "subtitles.$ModId.$cat.$($_.Name)"
                sounds   = @($_.Group | Sort-Object Name | ForEach-Object {
                    [ordered]@{ name = "$ModId`:$cat/$($_.BaseName)"; stream = $false } })
            }
        }
}

$entries | ConvertTo-Json -Depth 6 |
    Set-Content (Join-Path $Staging "assets\$ModId\sounds.json") -Encoding utf8
Ok ("sounds.json: {0} events" -f $entries.Count)

Write-Host ''
Ok "staging: $Staging"
Write-Host "  copy $Staging\assets  ->  <mod>\src\main\resources\assets" -ForegroundColor DarkGray
