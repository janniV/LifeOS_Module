# SMTC query script for LifeOS Scrobbler.
# Returns one JSON line describing the currently playing media session, or "{}".
# Optional -ThumbnailOut <path>: when supplied, the current thumbnail is written
# to that path as PNG bytes (best effort; no error if missing).
#
# Uses the public WinRT Windows.Media.Control namespace (a.k.a. GSMTC).

param(
    [string]$ThumbnailOut = $null
)

# Force UTF-8 output so Java can read non-ASCII artist/title/album names
# (ä, ö, ü, etc.) correctly regardless of the system code page.
# Setting [Console]::OutputEncoding and $OutputEncoding on its own isn't
# enough on PowerShell 5.1: Write-Output / ConvertTo-Json sometimes still
# write through a transcoded stream that loses non-ASCII bytes when the
# system code page is e.g. CP1252. We force the raw byte stream below.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding              = [System.Text.Encoding]::UTF8

$ErrorActionPreference = "Stop"

function Write-Utf8Json($obj) {
    # Serialise, then push raw UTF-8 bytes straight to stdout — bypasses the
    # encoding inheritance issues that caused intermittent ä/ö mojibake.
    $json = $obj | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $stdout = [System.Console]::OpenStandardOutput()
    $stdout.Write($bytes, 0, $bytes.Length)
    $stdout.Flush()
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null

    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]

    function Await($op, $type) {
        $asTask = $asTaskGeneric.MakeGenericMethod($type)
        $task = $asTask.Invoke($null, @($op))
        $task.Wait(-1) | Out-Null
        return $task.Result
    }

    [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
    [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
    [Windows.Storage.Streams.RandomAccessStreamReference,Windows.Storage.Streams,ContentType=WindowsRuntime] | Out-Null
    [Windows.Storage.Streams.DataReader,Windows.Storage.Streams,ContentType=WindowsRuntime] | Out-Null
    [Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime] | Out-Null

    $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
    $session = $mgr.GetCurrentSession()
    if ($null -eq $session) {
        Write-Utf8Json ([PSCustomObject]@{})
        return
    }

    $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    $info  = $session.GetPlaybackInfo()
    $tl    = $session.GetTimelineProperties()

    $statusName = switch ([int]$info.PlaybackStatus) {
        0 { "Closed" }
        1 { "Opened" }
        2 { "Changing" }
        3 { "Stopped" }
        4 { "Playing" }
        5 { "Paused" }
        default { "Unknown" }
    }

    $coverPath = ""
    if ($ThumbnailOut -and $props.Thumbnail) {
        try {
            $stream = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
            $size = [int]$stream.Size
            if ($size -gt 0) {
                $reader = New-Object Windows.Storage.Streams.DataReader $stream.GetInputStreamAt(0)
                Await ($reader.LoadAsync($size)) ([uint32]) | Out-Null
                $bytes = New-Object byte[] $size
                $reader.ReadBytes($bytes)
                [System.IO.File]::WriteAllBytes($ThumbnailOut, $bytes)
                $coverPath = $ThumbnailOut
            }
        } catch {
            $coverPath = ""
        }
    }

    $obj = [PSCustomObject]@{
        artist     = [string]$props.Artist
        title      = [string]$props.Title
        album      = [string]$props.AlbumTitle
        appId      = [string]$session.SourceAppUserModelId
        status     = $statusName
        positionMs = [int64]$tl.Position.TotalMilliseconds
        durationMs = [int64]$tl.EndTime.TotalMilliseconds
        coverPath  = $coverPath
    }

    Write-Utf8Json $obj
} catch {
    Write-Utf8Json ([PSCustomObject]@{})
}
