package com.spectradesk.camera

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export measurements as CSV and as a self-contained HTML report.
 *
 * Both formats are written to the app's cache and shared through a
 * `FileProvider`, so no storage permission is needed on any supported API level.
 *
 * The HTML is deliberately plain - no CDN, no JavaScript, no web fonts - for the
 * same reason the desktop app's report is: a QC sheet has to open on a workshop
 * PC that may have no network at all.  Swatches are inline styles rather than
 * images, so the file is a few kilobytes and still shows the measured colours.
 */
object Exporter {

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Write a CSV of every measurement and return the file. */
    fun writeCsv(context: Context, state: UiState): File {
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        val file = File(dir, "measurements-${stamp.format(Date())}.csv")

        val header = listOf(
            "#", "label", "timestamp", "L*", "a*", "b*", "C*", "h",
            "CCT", "x", "y", "standard", "dE00", "verdict", "GC", "AE/AWB lock",
        ).joinToString(",")

        val rows = state.measurements.map { m ->
            listOf(
                m.index.toString(),
                csvEsc(m.label),
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(m.timestampMs)),
                // Locale.US for every number: on a German phone the default
                // locale writes "87,60", which the comma-separated file then
                // splits into two columns and corrupts the table.
                String.format(Locale.US, "%.3f", m.lab[0]),
                String.format(Locale.US, "%.3f", m.lab[1]),
                String.format(Locale.US, "%.3f", m.lab[2]),
                String.format(Locale.US, "%.3f", m.lch[1]),
                String.format(Locale.US, "%.2f", m.lch[2]),
                if (m.cct.isFinite() && m.cct > 0) String.format(Locale.US, "%.0f", m.cct) else "",
                String.format(Locale.US, "%.4f", m.xy[0]),
                String.format(Locale.US, "%.4f", m.xy[1]),
                csvEsc(m.standardName ?: ""),
                m.deltaE?.let { String.format(Locale.US, "%.3f", it) } ?: "",
                m.verdictText(),
                if (m.gammaCorrected) "on" else "off",
                // Per-record, not from the live state: after clearing the
                // calibration, exporting used to relabel every past record as
                // "unlocked", which rewrote history.
                if (m.exposureLocked) "locked" else "unlocked",
            ).joinToString(",")
        }

        file.writeText((listOf(header) + rows).joinToString("\n"), Charsets.UTF_8)
        return file
    }

    /** Quote a CSV field when it contains a delimiter, quote or newline. */
    private fun csvEsc(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }

    /** Write a single-file HTML report and return the file. */
    fun writeHtml(context: Context, state: UiState): File {
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        val file = File(dir, "report-${stamp.format(Date())}.html")

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val ms = state.measurements
        val evaluated = ms.count { it.passed != null }
        val passed = ms.count { it.passed == true }
        val failed = ms.count { it.passed == false }
        val des = ms.mapNotNull { it.deltaE }
        val meanDe = if (des.isEmpty()) null else des.average()
        val maxDe = if (des.isEmpty()) null else des.max()

        val badge = when {
            evaluated == 0 -> "<span class='badge na'>未判定</span>"
            failed == 0 -> "<span class='badge pass'>PASS</span>"
            else -> "<span class='badge fail'>FAIL</span>"
        }

        val rows = ms.joinToString("\n") { m ->
            val swatch = swatchCss(m.lab)
            val verdictClass = when (m.passed) {
                true -> "pass"
                false -> "fail"
                null -> "na"
            }
            """
            <tr>
              <td>${m.index}</td>
              <td>${esc(m.label)}</td>
              <td><span class="swatch" style="background:$swatch"></span></td>
              <td class="num">${"%.2f".format(m.lab[0])}</td>
              <td class="num">${"%.2f".format(m.lab[1])}</td>
              <td class="num">${"%.2f".format(m.lab[2])}</td>
              <td class="num">${"%.2f".format(m.lch[1])}</td>
              <td class="num">${"%.1f".format(m.lch[2])}</td>
              <td class="num">${m.deltaE?.let { "%.2f".format(it) } ?: "—"}</td>
              <td>${esc(m.standardName ?: "—")}</td>
              <td class="$verdictClass">${m.verdictText()}</td>
            </tr>
            """.trimIndent()
        }

        val html = """
<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>色差检测报告</title>
<style>
  :root{--ink:#16202a;--muted:#5c6b7a;--line:#d8e0e8;--bg:#f6f8fa;
        --pass:#1f7a4d;--fail:#b4472f;--accent:#2b5d8c;}
  *{box-sizing:border-box}
  body{margin:0;padding:24px 20px 48px;background:var(--bg);color:var(--ink);
       font-family:-apple-system,"Segoe UI","Microsoft YaHei",Roboto,sans-serif}
  header{display:flex;justify-content:space-between;align-items:flex-start;
         border-bottom:2px solid var(--line);padding-bottom:14px;margin-bottom:20px}
  h1{font-size:20px;margin:0 0 6px}
  .meta{color:var(--muted);font-size:12px;line-height:1.7}
  .badge{font-size:12px;font-weight:700;letter-spacing:.1em;padding:7px 12px;border-radius:4px}
  .badge.pass{background:#e5f3eb;color:var(--pass);border:1px solid #b8ddc8}
  .badge.fail{background:#fbe9e4;color:var(--fail);border:1px solid #eec4b8}
  .badge.na{background:#eceff3;color:var(--muted);border:1px solid var(--line)}
  .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:10px;margin-bottom:20px}
  .card{background:#fff;border:1px solid var(--line);border-radius:6px;padding:10px 12px}
  .card .k{font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:.06em}
  .card .v{font-size:19px;font-weight:600;margin-top:3px;font-variant-numeric:tabular-nums}
  table{width:100%;border-collapse:collapse;background:#fff;font-size:12px;border:1px solid var(--line)}
  th,td{padding:6px 8px;border-bottom:1px solid var(--line);text-align:left}
  th{background:#eef2f6;font-weight:600;color:#33475b}
  td.num{text-align:right;font-variant-numeric:tabular-nums}
  td.pass{color:var(--pass);font-weight:600}
  td.fail{color:var(--fail);font-weight:600}
  td.na{color:var(--muted)}
  .swatch{display:inline-block;width:22px;height:14px;border-radius:2px;
          border:1px solid rgba(0,0,0,.15);vertical-align:middle}
  footer{margin-top:32px;padding-top:12px;border-top:1px solid var(--line);
         font-size:11px;color:var(--muted)}
  .note{margin-top:16px;padding:10px 12px;background:#fbf3e2;border:1px solid #ecdcb8;
        border-radius:6px;font-size:11.5px;color:#6b5626}
</style>
</head>
<body>
<header>
  <div>
    <h1>色差检测报告</h1>
    <div class="meta">
      生成时间 ${now}<br/>
      设备 手机相机 · 三点测色<br/>
      GC 伽马 ${if (state.settings.gammaCorrected) "开（sRGB→线性）" else "关（按线性处理）"} ·
      曝光 ${if (state.exposureLocked) "已锁" else "未锁"}<br/>
      当前标准 ${esc(state.activeStandardName ?: "—")}
    </div>
  </div>
  $badge
</header>

<div class="cards">
  <div class="card"><div class="k">测量数</div><div class="v">${ms.size}</div></div>
  <div class="card"><div class="k">通过</div><div class="v">$passed</div></div>
  <div class="card"><div class="k">超差</div><div class="v">$failed</div></div>
  <div class="card"><div class="k">平均 ΔE</div><div class="v">${meanDe?.let { "%.2f".format(it) } ?: "—"}</div></div>
  <div class="card"><div class="k">最大 ΔE</div><div class="v">${maxDe?.let { "%.2f".format(it) } ?: "—"}</div></div>
</div>

<table>
  <thead>
    <tr>
      <th>#</th><th>标签</th><th>颜色</th><th>L*</th><th>a*</th><th>b*</th>
      <th>C*</th><th>h</th><th>ΔE00</th><th>标准</th><th>判定</th>
    </tr>
  </thead>
  <tbody>
$rows
  </tbody>
</table>

<div class="note">
  <strong>测量条件说明</strong><br/>
  本报告由手机三通道相机测得。手机相机没有光谱分辨能力，报告只给出颜色值（CIE Lab）
  与色差（CIEDE2000），不代表光谱曲线。读数仅在<b>同一光源、同一次白平衡</b>下互相可比；
  更换光源或改变曝光后必须重新做白参考。
</div>

<footer>
  色差计 · Android · 色差按 CIE ΔE00 计算
</footer>
</body>
</html>
""".trimIndent()

        file.writeText(html, Charsets.UTF_8)
        return file
    }

    /** Share a file through the FileProvider declared in the manifest. */
    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * A CSS colour for a Lab value.
     *
     * Rendered through Lab -> XYZ -> linear sRGB -> sRGB, so the swatch shows the
     * measured colour rather than a nominal one.  Out-of-gamut values are clipped
     * by the transfer function, which is the correct behaviour for a preview.
     */
    private fun swatchCss(lab: DoubleArray): String {
        val xyz = Colorimetry.labToXyz(lab)
        val srgb = Colorimetry.xyzToSrgb(xyz)
        fun c(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).toInt()
        return "rgb(${c(srgb[0])},${c(srgb[1])},${c(srgb[2])})"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
