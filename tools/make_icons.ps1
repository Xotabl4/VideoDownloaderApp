# Иконки «Загрузчик видео»: синий градиент + белая стрелка скачивания.
# Нужны для API 24–25 (адаптивная иконка работает только с API 26).
Add-Type -AssemblyName System.Drawing

$src = @"
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

public static class IconMaker {
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

  public static void Draw(string path, int size, bool round) {
    using (var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
    using (var g = Graphics.FromImage(bmp)) {
      g.SmoothingMode = SmoothingMode.AntiAlias;
      g.Clear(Color.Transparent);

      var rect = new Rectangle(1, 1, size - 2, size - 2);
      using (var brush = new LinearGradientBrush(rect, Color.FromArgb(27, 58, 107), Color.FromArgb(20, 36, 63), 55f)) {
        if (round) g.FillEllipse(brush, rect);
        else { using (var bg = Rounded(new RectangleF(1, 1, size - 2, size - 2), size * 0.22f)) g.FillPath(brush, bg); }
      }

      float cx = size / 2f;
      float tailW = size * 0.14f;
      float tailTop = size * 0.25f;
      float tailBottom = size * 0.49f;
      using (var white = new SolidBrush(Color.White)) {
        g.FillRectangle(white, cx - tailW / 2f, tailTop, tailW, tailBottom - tailTop);
        g.FillPolygon(white, new PointF[] {
          new PointF(cx - size * 0.20f, tailBottom),
          new PointF(cx + size * 0.20f, tailBottom),
          new PointF(cx, size * 0.74f)
        });
        float shelfW = size * 0.42f, shelfH = size * 0.08f;
        using (var shelf = Rounded(new RectangleF(cx - shelfW / 2f, size * 0.80f, shelfW, shelfH), shelfH / 2f)) {
          g.FillPath(white, shelf);
        }
      }
      bmp.Save(path, ImageFormat.Png);
    }
  }
}
"@
Add-Type -TypeDefinition $src -ReferencedAssemblies System.Drawing

$res = 'C:\Users\Xotabl4\Desktop\apk\ytdlp_android\app\src\main\res'
$backup = 'C:\Users\Xotabl4\Desktop\apk\_template_backup'
$densities = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }

foreach ($name in $densities.Keys) {
  $dir = Join-Path $res "mipmap-$name"
  $size = $densities[$name]
  # Сохраняем дефолтные иконки шаблона перед заменой
  New-Item -ItemType Directory -Force -Path (Join-Path $backup "mipmap-$name") | Out-Null
  Get-ChildItem -LiteralPath $dir -Filter 'ic_launcher*.webp' | ForEach-Object {
    Move-Item -LiteralPath $_.FullName -Destination (Join-Path $backup "mipmap-$name\$($_.Name)") -Force
  }
  [IconMaker]::Draw((Join-Path $dir 'ic_launcher.png'), $size, $false)
  [IconMaker]::Draw((Join-Path $dir 'ic_launcher_round.png'), $size, $true)
  Write-Output "mipmap-$name : $size px"
}
