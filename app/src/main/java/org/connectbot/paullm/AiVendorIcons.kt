package org.connectbot.paullm

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.connectbot.R

enum class AiVendor(
    val label: String,
    val accent: Color,
    val badgeBg: Color,
    @DrawableRes val drawableRes: Int? = null,
) {
    CLAUDE("Claude", Color(0xFFD97757), Color(0x28D97757), R.drawable.ic_vendor_claude_sunburst),
    CODEX("Codex", Color(0xFF10A37F), Color(0x2810A37F), R.drawable.ic_vendor_codex),
    GROK("Grok", Color(0xFFF1F5F9), Color(0x28E2E8F0), R.drawable.ic_vendor_grok),
    OPENCODE("OpenCode", Color(0xFFA78BFA), Color(0x28A78BFA), R.drawable.ic_vendor_opencode),
    ANTIGRAVITY("Antigravity", Color(0xFF60A5FA), Color(0x2860A5FA), R.drawable.ic_vendor_antigravity),
    GEMINI("Gemini", Color(0xFF4285F4), Color(0x284285F4), R.drawable.ic_vendor_gemini),
    PI("Pi", Color(0xFF38BDF8), Color(0x2838BDF8), R.drawable.ic_vendor_pi),
    QWEN("Qwen", Color(0xFF818CF8), Color(0x28818CF8), R.drawable.ic_vendor_qwen),
    OLLAMA("Ollama", Color(0xFFE2E8F0), Color(0x26E2E8F0), R.drawable.ic_vendor_ollama),
    COPILOT("Copilot", Color(0xFF38BDF8), Color(0x2838BDF8), R.drawable.ic_vendor_copilot),
    AIDER("Aider", Color(0xFF4ADE80), Color(0x284ADE80), null),
    GOOSE("Goose", Color(0xFFFBBF24), Color(0x28FBBF24), R.drawable.ic_vendor_goose),
    TMUX("tmux", Color(0xFF38BDF8), Color(0x2638BDF8), null),
    SHELL("Shell", Color(0xFF94A3B8), Color(0x2294A3B8), null),
    ;

    companion object {
        fun detectFromName(name: String?, isTmux: Boolean = true): AiVendor {
            val lower = name?.lowercase().orEmpty()
            return when {
                lower.contains("claude") -> CLAUDE
                lower.contains("codex") || lower.contains("openai") -> CODEX
                lower.contains("grok") || lower.contains("xai") -> GROK
                lower.contains("opencode") -> OPENCODE
                lower.contains("agy") || lower.contains("antigravity") -> ANTIGRAVITY
                lower.contains("gemini") -> GEMINI
                lower == "pi" || lower.startsWith("pi-") || lower.contains("amie") -> PI
                lower.contains("qwen") -> QWEN
                lower.contains("ollama") -> OLLAMA
                lower.contains("copilot") -> COPILOT
                lower.contains("aider") -> AIDER
                lower.contains("goose") -> GOOSE
                isTmux -> TMUX
                else -> SHELL
            }
        }
    }
}

@Composable
fun AiVendorBadge(
    vendor: AiVendor,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(size)
            .background(vendor.badgeBg, shape)
            .border(1.dp, vendor.accent.copy(alpha = 0.45f), shape)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        AiVendorLogo(vendor = vendor, size = size - 8.dp)
    }
}

@Composable
fun AiVendorLogo(
    vendor: AiVendor,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = vendor.accent,
) {
    val resId = vendor.drawableRes
    if (resId != null) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = vendor.label,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
        return
    }

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.42f
        val stroke = Stroke(width = (w * 0.11f).coerceAtLeast(1.6f), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (vendor) {
            AiVendor.AIDER -> {
                val top = Path().apply {
                    moveTo(cx - r * 0.7f, cy - r * 0.15f)
                    lineTo(cx, cy - r * 0.85f)
                    lineTo(cx + r * 0.7f, cy - r * 0.15f)
                }
                val bottom = Path().apply {
                    moveTo(cx - r * 0.7f, cy + r * 0.7f)
                    lineTo(cx, cy)
                    lineTo(cx + r * 0.7f, cy + r * 0.7f)
                }
                drawPath(top, color = tint, style = stroke)
                drawPath(bottom, color = tint, style = stroke)
            }

            AiVendor.TMUX -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(cx - r, cy - r * 0.85f),
                    size = Size(r * 2f, r * 1.7f),
                    cornerRadius = CornerRadius(r * 0.25f, r * 0.25f),
                    style = stroke,
                )
                drawLine(
                    color = tint,
                    start = Offset(cx, cy - r * 0.85f),
                    end = Offset(cx, cy + r * 0.85f),
                    strokeWidth = stroke.width * 0.85f,
                )
                drawLine(
                    color = tint,
                    start = Offset(cx, cy),
                    end = Offset(cx + r, cy),
                    strokeWidth = stroke.width * 0.85f,
                )
            }

            else -> {
                val prompt = Path().apply {
                    moveTo(cx - r * 0.85f, cy - r * 0.55f)
                    lineTo(cx - r * 0.2f, cy)
                    lineTo(cx - r * 0.85f, cy + r * 0.55f)
                }
                drawPath(prompt, color = tint, style = stroke)
                drawLine(
                    color = tint,
                    start = Offset(cx + r * 0.05f, cy + r * 0.55f),
                    end = Offset(cx + r * 0.85f, cy + r * 0.55f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
