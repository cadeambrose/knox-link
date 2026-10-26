# Draws the Steam Workshop preview (512x512) of Knox Link: a screenshot of the second screen in a device frame
# over the title, in the style of the mod's poster. Usage: .\tools\make-workshop-preview.ps1 <out.png>
param([string]$Out = "$PSScriptRoot\..\dist\workshop-preview.png")
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$orange = [System.Drawing.Color]::FromArgb(242, 169, 59)
$bmp = New-Object System.Drawing.Bitmap 512, 512
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'; $g.InterpolationMode = 'HighQualityBicubic'
$rect = New-Object System.Drawing.Rectangle 0, 0, 512, 512
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.Color]::FromArgb(28, 32, 38)), ([System.Drawing.Color]::FromArgb(10, 11, 14)), 90
$g.FillRectangle($grad, $rect)

function Round-Rect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, 2 * $r, 2 * $r, 180, 90); $p.AddArc($x + $w - 2 * $r, $y, 2 * $r, 2 * $r, 270, 90)
    $p.AddArc($x + $w - 2 * $r, $y + $h - 2 * $r, 2 * $r, 2 * $r, 0, 90); $p.AddArc($x, $y + $h - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $p.CloseFigure(); return $p
}

# the second screen, in a dark rounded frame with an orange edge
$shot = [System.Drawing.Bitmap]::FromFile("$root\docs\screens\vitals-wound.png")
$fw = 372; $fh = [int]($fw * $shot.Height / $shot.Width)
$fx = (512 - $fw) / 2; $fy = 26
$frame = Round-Rect ($fx - 10) ($fy - 10) ($fw + 20) ($fh + 20) 22
$g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(5, 6, 8))), $frame)
$g.DrawPath((New-Object System.Drawing.Pen $orange, 3), $frame)
$clip = Round-Rect $fx $fy $fw $fh 12
$g.SetClip($clip)
$g.DrawImage($shot, $fx, $fy, $fw, $fh)
$g.ResetClip()
$shot.Dispose()

$fmt = New-Object System.Drawing.StringFormat; $fmt.Alignment = 'Center'
$white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(236, 238, 241))
$grey = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(140, 148, 160))
$g.DrawString('KNOX LINK', (New-Object System.Drawing.Font('Segoe UI', 48, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)), $white, 256, 376, $fmt)
$g.DrawString('Second screen for Project Zomboid', (New-Object System.Drawing.Font('Segoe UI', 22, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)), (New-Object System.Drawing.SolidBrush $orange), 256, 436, $fmt)
$dot = [char]0x00B7
$g.DrawString("phone $dot tablet $dot second monitor $dot dual-screen handheld", (New-Object System.Drawing.Font('Segoe UI', 16, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)), $grey, 256, 470, $fmt)
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"preview: $Out ($([math]::Round((Get-Item $Out).Length / 1KB)) KB)"
