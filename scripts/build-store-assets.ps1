Add-Type -AssemblyName System.Drawing
$assetDirectory = Join-Path $PSScriptRoot '../release/store-assets'
[System.IO.Directory]::CreateDirectory($assetDirectory) | Out-Null
$green = [System.Drawing.ColorTranslator]::FromHtml('#27634B')
$paper = [System.Drawing.ColorTranslator]::FromHtml('#F4EFDF')
$ink = [System.Drawing.ColorTranslator]::FromHtml('#243028')
$greenBrush = New-Object System.Drawing.SolidBrush($green)
$paperBrush = New-Object System.Drawing.SolidBrush($paper)
$inkBrush = New-Object System.Drawing.SolidBrush($ink)
try {
    $icon = New-Object System.Drawing.Bitmap(512, 512)
    $drawing = [System.Drawing.Graphics]::FromImage($icon)
    try {
        $drawing.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $drawing.Clear($green)
        $drawing.ScaleTransform((512.0 / 108), (512.0 / 108))
        $drawing.FillRectangle($paperBrush, 22, 22, 64, 64)
        $points = [System.Drawing.PointF[]]@(
            [System.Drawing.PointF]::new(35,71), [System.Drawing.PointF]::new(35,37),
            [System.Drawing.PointF]::new(43,37), [System.Drawing.PointF]::new(65,60),
            [System.Drawing.PointF]::new(65,37), [System.Drawing.PointF]::new(73,37),
            [System.Drawing.PointF]::new(73,71), [System.Drawing.PointF]::new(65,71),
            [System.Drawing.PointF]::new(43,48), [System.Drawing.PointF]::new(43,71)
        )
        $drawing.FillPolygon($greenBrush, $points)
        $icon.Save((Join-Path $assetDirectory 'icon-512.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $drawing.Dispose(); $icon.Dispose() }

    $graphic = New-Object System.Drawing.Bitmap(1024, 500)
    $drawing = [System.Drawing.Graphics]::FromImage($graphic)
    $title = [System.Drawing.Font]::new('Segoe UI', 112, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $subtitle = [System.Drawing.Font]::new('Segoe UI', 34, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $detail = [System.Drawing.Font]::new('Segoe UI', 25, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    try {
        $drawing.Clear($paper)
        $drawing.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $drawing.FillRectangle($greenBrush, 0, 0, 20, 500)
        $drawing.DrawString('NPAT', $title, $greenBrush, 76, 72)
        $drawing.DrawString('Name. Place. Animal. Thing.', $subtitle, $inkBrush, 84, 222)
        $drawing.DrawString('One letter. Think fast.', $detail, $inkBrush, 87, 296)
        $drawing.DrawString('Online rooms + offline Bluetooth', $detail, $greenBrush, 87, 382)
        $graphic.Save((Join-Path $assetDirectory 'feature-graphic-1024x500.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $title.Dispose(); $subtitle.Dispose(); $detail.Dispose(); $drawing.Dispose(); $graphic.Dispose() }
} finally { $greenBrush.Dispose(); $paperBrush.Dispose(); $inkBrush.Dispose() }
Write-Output 'Created Play Store icon and feature graphic.'
