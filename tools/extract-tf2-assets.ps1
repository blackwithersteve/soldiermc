<#
.SYNOPSIS
  Extracts the TF2 Soldier asset set from a TF2 install and converts the audio
  into the Minecraft resource layout (mono Ogg Vorbis + sounds.json).

.DESCRIPTION
  Extraction needs only vpk.exe, which ships with TF2. Conversion needs ffmpeg on PATH.
  Run with -SkipConvert to do extraction alone.

.EXAMPLE
  .\extract-tf2-assets.ps1 -Staging D:\tf2-assets
  .\extract-tf2-assets.ps1 -TF2Path "D:\SteamLibrary\steamapps\common\Team Fortress 2" -SkipConvert
#>
[CmdletBinding()]
param(
    [string] $TF2Path,
    [string] $Staging = "$PSScriptRoot\..\..\tf2-assets-staging",
    [string] $ModId   = "tf2soldier",
    [switch] $SkipConvert,
    [switch] $IncludeNonAudio,
    # Vorbis quality. 5 ~= 100 kbps mono. Use 4 for VO (already lossy 128k mp3).
    [int]    $QualitySfx   = 5,
    [int]    $QualityVoice = 4
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Info($m) { Write-Host "[*] $m" -ForegroundColor Cyan }
function Ok  ($m) { Write-Host "[+] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[!] $m" -ForegroundColor Yellow }

# ---- Stage 1: locate TF2 + vpk.exe ----
# Steam records every library root in libraryfolders.vdf.
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

# vpk.exe writes relative to the current directory and does not create folders, hence
# the Push-Location and the pre-created subdirs.
function Invoke-VpkExtract {
    param([string]$Vpk, [string[]]$Files, [string]$Dest)
    if (-not $Files -or $Files.Count -eq 0) { return }
    $Files | ForEach-Object { Split-Path $_ -Parent } | Sort-Object -Unique | ForEach-Object {
        $null = New-Item -ItemType Directory -Force -Path (Join-Path $Dest ($_ -replace '/', '\'))
    }
    Push-Location $Dest
    try {
        # Chunked: Windows caps a command line at ~32k chars.
        for ($i = 0; $i -lt $Files.Count; $i += 150) {
            $chunk = $Files[$i..([Math]::Min($i + 149, $Files.Count - 1))]
            & $VpkExe x $Vpk @chunk *>$null
        }
    } finally { Pop-Location }
}

function Get-VpkIndex {
    param([string]$Vpk)
    # 'l' prints one internal path per line, always lowercase, forward slashes.
    & $VpkExe l $Vpk 2>$null | Where-Object { $_ -match '\S' }
}

# ---- Stage 2: build the file list ----
Info 'Reading VPK indexes'
$VoVpk  = Join-Path $TfDir  'tf2_sound_vo_english_dir.vpk'
$SfxVpk = Join-Path $TfDir  'tf2_sound_misc_dir.vpk'
$MiscVpk= Join-Path $TfDir  'tf2_misc_dir.vpk'
$TexVpk = Join-Path $TfDir  'tf2_textures_dir.vpk'
$Hl2Sfx = Join-Path $Hl2Dir 'hl2_sound_misc_dir.vpk'

$voIndex  = Get-VpkIndex $VoVpk
$sfxIndex = Get-VpkIndex $SfxVpk

# Soldier voice. Excludes the MvM/comp/taunt lines, which live under sound/vo/<subdir>/.
$voFiles = $voIndex | Where-Object { $_ -match '^sound/vo/soldier_[^/]+\.mp3$' }

# Weapon / player / footstep SFX.
$sfxPatterns = @(
    # rocket launcher
    '^sound/weapons/rocket_(shoot|shoot_crit|reload)\.wav$'
    '^sound/weapons/draw_primary\.wav$'
    # explosion + debris (debris1 is NOT in tf2_sound_misc; see $hl2Files below)
    '^sound/weapons/(explode1|explode2|explode3|debris2|debris4)\.wav$'
    # shotgun. shotgun_cock.wav, referenced by Weapon_Shotgun.Pump, is in neither VPK;
    # the pump is cock_back + cock_forward.
    '^sound/weapons/shotgun_(shoot|shoot_crit|reload|worldreload|empty|cock_back|cock_forward)\.wav$'
    '^sound/weapons/draw_secondary\.wav$'
    # shovel (hits reuse the generic axe/crowbar impacts)
    '^sound/weapons/(shovel_swing|shovel_swing_crit|axe_hit_flesh[123]|cbar_hit[12])\.wav$'
    '^sound/weapons/draw_shovel_soldier\.wav$'
    # footsteps - all surfaces, 4 variants each
    '^sound/player/footsteps/.+\.wav$'
    # generic pain/death/crit feedback
    '^sound/player/(pain|death|fire|flame_out|crit_hit\d?|crit_hit_mini\d?|crit_received[123]|crit_death[1-5])\.wav$'
    # rocket-jump whistle + landing
    '^sound/misc/grenade_jump_(lp|fall)_01\.wav$'
)
$sfxFiles = $sfxIndex | Where-Object { $p = $_; $sfxPatterns | Where-Object { $p -match $_ } } | Sort-Object -Unique

# HL2 fallback: gameinfo.txt mounts hl2/*.vpk after tf/*.vpk, so BaseGrenade.Explode's
# debris1.wav resolves out of HL2.
$hl2Files = @('sound/weapons/debris1.wav')

Info ("voice {0} | sfx {1} | hl2 {2}" -f $voFiles.Count, $sfxFiles.Count, $hl2Files.Count)

# ---- Stage 3: extract audio ----
Info 'Extracting audio'
Invoke-VpkExtract -Vpk $VoVpk  -Files $voFiles  -Dest $Raw
Invoke-VpkExtract -Vpk $SfxVpk -Files $sfxFiles -Dest $Raw
Invoke-VpkExtract -Vpk $Hl2Sfx -Files $hl2Files -Dest $Raw

$missing = @(@($voFiles) + @($sfxFiles) + @($hl2Files) | Where-Object {
    -not (Test-Path (Join-Path $Raw ($_ -replace '/', '\'))) })
if ($missing.Count -gt 0) { Warn "missing after extract: $($missing -join ', ')" }
Ok ("extracted {0:N1} MB" -f ((Get-ChildItem $Raw -Recurse -File | Measure-Object Length -Sum).Sum / 1MB))

# ---- Stage 6: extract non-audio ----
if ($IncludeNonAudio) {
    Info 'Extracting models / materials / scripts'
    Invoke-VpkExtract -Vpk $MiscVpk -Dest $Raw -Files @(
        # rocket launcher viewmodel + rocket projectile (mdl needs vvd+vtx to decompile)
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
        # player model: animations live in a SEPARATE mdl with no vvd/vtx
        'models/player/soldier.mdl'
        'models/player/soldier.vvd'
        'models/player/soldier.dx90.vtx'
        'models/player/soldier_animations.mdl'
        # HUD material definitions
        'materials/hud/health_bg.vmt'
        'materials/hud/health_color.vmt'
        'materials/hud/health_over_bg.vmt'
        'materials/hud/ammo_red_bg.vmt'
        'materials/hud/ammo_blue_bg.vmt'
        # HUD layout + palette + fonts (exact geometry and colours)
        'resource/clientscheme.res'
        'resource/ui/hudplayerhealth.res'
        'resource/ui/hudammoweapons.res'
        'resource/ui/huddamageaccount.res'
        # soundscripts + response rules
        'scripts/game_sounds_weapons.txt'
        'scripts/game_sounds_player.txt'
        'scripts/game_sounds_vo.txt'
        'scripts/voicecommands.txt'
        'scripts/talker/response_rules.txt'
        'scripts/talker/soldier.txt'
        'scripts/talker/soldier_auto.txt'
        # weapon + class scripts ship ICE-encrypted as .ctx, never as .txt.
        # Decode with tools/DecryptCtx.java (or tools/extract_tf2_stats.py).
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
        # the ammo panel is a signed-distance-field material, not a bitmap:
        # ammo_red_bg.vmt = gradient_red (base) + ammo_area_mask (SDF detail)
        'materials/hud/gradient_red.vtf'
        'materials/hud/gradient_blue.vtf'
        'materials/hud/ammo_area_mask.vtf'
        'materials/vgui/crosshairs/crosshair1.vtf'
        'materials/vgui/crosshairs/default.vtf'
        'materials/models/player/soldier/soldier_red.vtf'
        'materials/models/player/soldier/soldier_blue.vtf'
        'materials/models/player/soldier/soldier_head.vtf'
    )
    # Fonts and the item schema ship LOOSE on disk, not inside any VPK.
    $null = New-Item -ItemType Directory -Force -Path "$Raw\resource", "$Raw\scripts\items"
    Get-ChildItem "$TfDir\resource" -Filter '*.ttf' -File |
        Copy-Item -Destination "$Raw\resource" -Force
    Copy-Item "$TfDir\scripts\items\items_game.txt" "$Raw\scripts\items\" -Force
    Ok 'non-audio staged'
}

if ($SkipConvert) { Ok "done (extract only): $Raw"; return }

# ---- Stage 4: convert to Ogg ----
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    throw "ffmpeg not on PATH. winget install --id Gyan.FFmpeg -e   (then reopen the shell)"
}

$SoundsRoot = Join-Path $Staging "assets\$ModId\sounds"
$null = New-Item -ItemType Directory -Force -Path $SoundsRoot

# Minecraft applies 3D distance attenuation only to mono streams, and 22 of the TF2 SFX
# are stereo, hence -ac 1. Sample rate is left alone: TF2 mixes 22.05k and 44.1k.
function Convert-One {
    param([string]$Src, [string]$Dst, [int]$Q)
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $Dst -Parent)
    & ffmpeg -hide_banner -loglevel error -y -i $Src -vn -map_metadata -1 `
             -ac 1 -c:a libvorbis -q:a $Q $Dst
    if ($LASTEXITCODE -ne 0) { Warn "ffmpeg failed: $Src" }
}

Info 'Converting to Ogg Vorbis (mono)'
$n = 0
# voice/soldier_battlecry01.ogg ...
foreach ($f in $voFiles) {
    $name = [IO.Path]::GetFileNameWithoutExtension($f)
    Convert-One (Join-Path $Raw ($f -replace '/', '\')) "$SoundsRoot\voice\$name.ogg" $QualityVoice
    $n++
}
# weapon/ , player/ , footstep/ , misc/
foreach ($f in @($sfxFiles + $hl2Files)) {
    $name = [IO.Path]::GetFileNameWithoutExtension($f)
    # switch -Regex runs every matching branch, so without 'break' a footstep matches
    # two of these and $cat becomes an array.
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

# ---- Stage 5: write sounds.json ----
Info 'Writing sounds.json'
$entries = [ordered]@{}

# Voice lines collapse into one event per response category, the way TF2's rndwave
# soundscripts behave: soldier_battlecry01..06 -> one "voice.battlecry" with six variants.
# Strips a trailing variant number, underscore optional:
# soldier_battlecry06 -> battlecry ; grenade_jump_fall_01 -> grenade_jump_fall
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
