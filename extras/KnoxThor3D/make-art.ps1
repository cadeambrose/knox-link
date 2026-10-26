# Draws the mod list icon (128x128) and the poster (512x512) of "Viewpoint on Android", in Knox Link's style:
# a handheld with a 3D cube on its screen. Run from anywhere; writes into mod\42 and mod (Build 41 copy).
Add-Type -AssemblyName System.Drawing
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$orange = [System.Drawing.Color]::FromArgb(242, 169, 59)
$bgTop = [System.Drawing.Color]::FromArgb(28, 32, 38)
$bgBottom = [System.Drawing.Color]::FromArgb(10, 11, 14)
$iconBg = [System.Drawing.Color]::FromArgb(21, 24, 29)

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.InterpolationMode = 'HighQualityBicubic'
    return $bmp, $g
}

function Round-Rect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, 2 * $r, 2 * $r, 180, 90)
    $p.AddArc($x + $w - 2 * $r, $y, 2 * $r, 2 * $r, 270, 90)
    $p.AddArc($x + $w - 2 * $r, $y + $h - 2 * $r, 2 * $r, 2 * $r, 0, 90)
    $p.AddArc($x, $y + $h - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $p.CloseFigure()
    return $p
}

# The glyph: a handheld (rounded body, screen outline, two sticks) with a cube drawn on its screen.
function Draw-Glyph($g, [float]$cx, [float]$cy, [float]$w) {
    $h = $w * 0.5
    $x = $cx - $w / 2; $y = $cy - $h / 2
    $stroke = [float]($w * 0.055)
    $pen = New-Object System.Drawing.Pen $orange, $stroke
    $pen.LineJoin = 'Round'
    $brush = New-Object System.Drawing.SolidBrush $orange
    $g.DrawPath($pen, (Round-Rect $x $y $w $h ($h * 0.42)))
    # screen
    $sw = $w * 0.46; $sh = $h * 0.62
    $sx = $cx - $sw / 2; $sy = $cy - $sh / 2
    $g.DrawPath($pen, (Round-Rect $sx $sy $sw $sh ($sh * 0.12)))
    # sticks
    $r = $h * 0.11
    $g.FillEllipse($brush, $x + $w * 0.13 - $r, $cy - $r - $h * 0.08, 2 * $r, 2 * $r)
    $g.FillEllipse($brush, $x + $w * 0.87 - $r, $cy - $r + $h * 0.08, 2 * $r, 2 * $r)
    # a cube on the screen (isometric): the top face solid, the two sides as outlines
    $c = $sh * 0.36
    $ccx = $cx; $ccy = $cy + $c * 0.05
    $dx = $c * 0.87; $dy = $c * 0.5
    $pt = { param($px, $py) New-Object System.Drawing.PointF ([float]$px), ([float]$py) }
    $topFace = [System.Drawing.PointF[]]@((& $pt $ccx ($ccy - $c)), (& $pt ($ccx + $dx) ($ccy - $c + $dy)), (& $pt $ccx ($ccy - $c + 2 * $dy)), (& $pt ($ccx - $dx) ($ccy - $c + $dy)))
    $outline = [System.Drawing.PointF[]]@((& $pt $ccx ($ccy - $c)), (& $pt ($ccx + $dx) ($ccy - $c + $dy)), (& $pt ($ccx + $dx) ($ccy + $dy)), (& $pt $ccx ($ccy + $c)), (& $pt ($ccx - $dx) ($ccy + $dy)), (& $pt ($ccx - $dx) ($ccy - $c + $dy)))
    $thin = New-Object System.Drawing.Pen $orange, ([float]($stroke * 0.8))
    $thin.LineJoin = 'Round'
    $g.FillPolygon($brush, $topFace)
    $g.DrawPolygon($thin, $outline)
    $g.DrawLine($thin, $ccx, ($ccy - $c + 2 * $dy), $ccx, ($ccy + $c))
}

# icon
$bmp, $g = New-Canvas 128
$g.Clear($iconBg)
Draw-Glyph $g 64 64 104
$g.Dispose()
$bmp.Save("$here\mod\42\icon.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

# poster
$bmp, $g = New-Canvas 512
$rect = New-Object System.Drawing.Rectangle 0, 0, 512, 512
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $bgTop, $bgBottom, 90
$g.FillRectangle($grad, $rect)
Draw-Glyph $g 256 200 300
$white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(236, 238, 241))
$grey = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(110, 118, 130))
$orangeBrush = New-Object System.Drawing.SolidBrush $orange
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = 'Center'
$g.DrawString('VIEWPOINT', (New-Object System.Drawing.Font('Segoe UI', 40, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)), $white, 256, 330, $fmt)
$g.DrawString('ON ANDROID', (New-Object System.Drawing.Font('Segoe UI', 40, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)), $white, 256, 372, $fmt)
$g.DrawString('Project Viewpoint 3D on Zomdroid', (New-Object System.Drawing.Font('Segoe UI', 21, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)), $orangeBrush, 256, 428, $fmt)
$g.DrawString("a Knox Link extra $([char]0x00B7) AYN Thor tested",(New-Object System.Drawing.Font('Segoe UI', 17, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)), $grey, 256, 462, $fmt)
$g.Dispose()
$bmp.Save("$here\mod\42\poster.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"art written"
