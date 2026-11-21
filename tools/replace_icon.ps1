# Заменяет иконку приложения из готовой картинки.
# Пример: powershell -File tools\replace_icon.ps1 -Source C:\путь\icon.png
param(
  [Parameter(Mandatory = $true)][string]$Source,
  [string]$Foreground = '',
  [string]$Background = '',
  [string]$OutDir = '',
  [string]$BackupDir = 'C:\Users\Xotabl4\Desktop\apk\_template_backup'
)

Add-Type -AssemblyName System.Drawing

$src = @"
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

public static class IconReplace {
  static GraphicsPath Rounded(RectangleF r, float radius) {
    float d = radius * 2f;
    var p = new GraphicsPath();
    p.AddArc(r.X, r.Y, d, d, 180, 90);
    p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
    p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
    p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
    p.CloseFigure();
    return p;
  }

  // Вписывает картинку в квадрат по принципу «cover» (без искажений, центр).
  static void DrawCover(Graphics g, Image image, Rectangle target) {
    float scale = Math.Max((float)target.Width / image.Width, (float)target.Height / image.Height);
    float w = image.Width * scale, h = image.Height * scale;
    float x = target.X + (target.Width - w) / 2f, y = target.Y + (target.Height - h) / 2f;
    g.DrawImage(image, x, y, w, h);
  }

  public static void Make(string sourcePath, string outPath, int size, bool round) {
    using (var original = Image.FromFile(sourcePath))
    using (var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
    using (var g = Graphics.FromImage(bmp)) {
      g.SmoothingMode = SmoothingMode.AntiAlias;
      g.InterpolationMode = InterpolationMode.HighQualityBicubic;
      g.PixelOffsetMode = PixelOffsetMode.HighQuality;
      g.Clear(Color.Transparent);
      var rect = new RectangleF(1, 1, size - 2, size - 2);
      using (var path = round ? null : Rounded(rect, size * 0.22f))
      using (var ellipse = round ? new GraphicsPath() : null) {
        if (round) ellipse.AddEllipse(rect);
        var clip = round ? ellipse : path;
        var state = g.Save();
        g.SetClip(clip);
        DrawCover(g, original, new Rectangle(0, 0, size, size));
        g.Restore(state);
      }
      bmp.Save(outPath, ImageFormat.Png);
    }
  }

  // Фон adaptive-иконки: картинка на всю площадь (система сама обрежет по маске).
  public static void MakeBackground(string sourcePath, string outPath, int size) {
    using (var original = Image.FromFile(sourcePath))
    using (var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
    using (var g = Graphics.FromImage(bmp)) {
      g.SmoothingMode = SmoothingMode.AntiAlias;
      g.InterpolationMode = InterpolationMode.HighQualityBicubic;
      g.PixelOffsetMode = PixelOffsetMode.HighQuality;
      g.Clear(Color.Black);
      DrawCover(g, original, new Rectangle(0, 0, size, size));
      bmp.Save(outPath, ImageFormat.Png);
    }
  }

  // Foreground для adaptive-иконки: картинка внутри safe zone (центр 66/108 площади).
  public static void MakeForeground(string sourcePath, string outPath, int size) {
    using (var original = Image.FromFile(sourcePath))
    using (var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
    using (var g = Graphics.FromImage(bmp)) {
      g.SmoothingMode = SmoothingMode.AntiAlias;
      g.InterpolationMode = InterpolationMode.HighQualityBicubic;
      g.Clear(Color.Transparent);
      int inset = (int)(size * (1 - 66.0 / 108.0) / 2);
      DrawCover(g, original, new Rectangle(inset, inset, size - inset * 2, size - inset * 2));
      bmp.Save(outPath, ImageFormat.Png);
    }
  }
}
"@
Add-Type -TypeDefinition $src -ReferencedAssemblies System.Drawing

if (-not (Test-Path -LiteralPath $Source)) { throw "нет файла: $Source" }
if ([string]::IsNullOrWhiteSpace($OutDir)) {
  $OutDir = 'C:\Users\Xotabl4\Desktop\apk\ytdlp_android\app\src\main\res'
}

$densities = [ordered]@{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($name in $densities.Keys) {
  $dir = Join-Path $OutDir "mipmap-$name"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $size = $densities[$name]
  [IconReplace]::Make($Source, (Join-Path $dir 'ic_launcher.png'), $size, $false)
  [IconReplace]::Make($Source, (Join-Path $dir 'ic_launcher_round.png'), $size, $true)
  Write-Output "mipmap-$name : $size px"
}

if (-not [string]::IsNullOrWhiteSpace($Foreground)) {
  if (-not (Test-Path -LiteralPath $Foreground)) { throw "нет файла foreground: $Foreground" }
  $fgDir = Join-Path $OutDir 'drawable-nodpi'
  New-Item -ItemType Directory -Force -Path $fgDir | Out-Null
  [IconReplace]::MakeForeground($Foreground, (Join-Path $fgDir 'ic_launcher_foreground.png'), 432)
  Write-Output 'drawable-nodpi/ic_launcher_foreground.png : 432 px'
  $vector = Join-Path $OutDir 'drawable\ic_launcher_foreground.xml'
  if (Test-Path -LiteralPath $vector) {
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    Move-Item -LiteralPath $vector -Destination (Join-Path $BackupDir 'ic_launcher_foreground.xml') -Force
    Write-Output 'старый вектор foreground убран в _template_backup'
  }
}

if (-not [string]::IsNullOrWhiteSpace($Background)) {
  if (-not (Test-Path -LiteralPath $Background)) { throw "нет файла background: $Background" }
  $bgDir = Join-Path $OutDir 'drawable-nodpi'
  New-Item -ItemType Directory -Force -Path $bgDir | Out-Null
  [IconReplace]::MakeBackground($Background, (Join-Path $bgDir 'ic_launcher_background.png'), 432)
  Write-Output 'drawable-nodpi/ic_launcher_background.png : 432 px'
  $vectorBg = Join-Path $OutDir 'drawable\ic_launcher_background.xml'
  if (Test-Path -LiteralPath $vectorBg) {
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    Move-Item -LiteralPath $vectorBg -Destination (Join-Path $BackupDir 'ic_launcher_background.xml') -Force
    Write-Output 'старый вектор background убран в _template_backup'
  }
}
