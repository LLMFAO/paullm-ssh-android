package org.connectbot.paullm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AiVendor(
    val label: String,
    val accent: Color,
    val badgeBg: Color,
) {
    CLAUDE("Claude", Color(0xFFD97757), Color(0x28D97757)),
    CODEX("Codex", Color(0xFF10A37F), Color(0x2810A37F)),
    GROK("Grok", Color(0xFFF1F5F9), Color(0x26E2E8F0)),
    OPENCODE("OpenCode", Color(0xFFA78BFA), Color(0x28A78BFA)),
    ANTIGRAVITY("Antigravity", Color(0xFF60A5FA), Color(0x2860A5FA)),
    AIDER("Aider", Color(0xFF4ADE80), Color(0x284ADE80)),
    GOOSE("Goose", Color(0xFFFBBF24), Color(0x28FBBF24)),
    TMUX("tmux", Color(0xFF38BDF8), Color(0x2638BDF8)),
    SHELL("Shell", Color(0xFF94A3B8), Color(0x2294A3B8)),
    ;

    companion object {
        fun detectFromName(name: String?, isTmux: Boolean = true): AiVendor {
            val lower = name?.lowercase().orEmpty()
            return when {
                lower.contains("claude") -> CLAUDE
                lower.contains("codex") || lower.contains("openai") -> CODEX
                lower.contains("grok") || lower.contains("xai") -> GROK
                lower.contains("opencode") -> OPENCODE
                lower.contains("agy") || lower.contains("antigravity") || lower.contains("gemini") -> ANTIGRAVITY
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
    size: Dp = 28.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(size)
            .background(vendor.badgeBg, shape)
            .border(1.dp, vendor.accent.copy(alpha = 0.45f), shape),
        contentAlignment = Alignment.Center,
    ) {
        AiVendorLogo(vendor = vendor, size = size * 0.64f)
    }
}

@Composable
fun AiVendorLogo(
    vendor: AiVendor,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = vendor.accent,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.42f
        val stroke = Stroke(width = (w * 0.11f).coerceAtLeast(1.6f), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (vendor) {
            AiVendor.CLAUDE -> {
                // Anthropic-inspired 8-ray starburst
                for (i in 0 until 8) {
                    val angle = (i * 45.0) * PI / 180.0
                    val inner = if (i % 2 == 0) r * 0.18f else r * 0.28f
                    val outer = if (i % 2 == 0) r * 1.05f else r * 0.82f
                    drawLine(
                        color = tint,
                        start = Offset(cx + (cos(angle) * inner).toFloat(), cy + (sin(angle) * inner).toFloat()),
                        end = Offset(cx + (cos(angle) * outer).toFloat(), cy + (sin(angle) * outer).toFloat()),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }

            AiVendor.CODEX -> {
                // OpenAI-inspired geometric hexagonal knot
                val hex = Path()
                for (i in 0 until 6) {
                    val angle = ((i * 60.0) - 30.0) * PI / 180.0
                    val x = cx + (cos(angle) * r).toFloat()
                    val y = cy + (sin(angle) * r).toFloat()
                    if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
                }
                hex.close()
                drawPath(hex, color = tint, style = stroke)
                drawCircle(color = tint, radius = r * 0.34f, center = Offset(cx, cy), style = stroke)
            }

            AiVendor.GROK -> {
                // xAI Grok-inspired broken orbital ring with diagonal slash
                drawArc(
                    color = tint,
                    startAngle = 35f,
                    sweepAngle = 285f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = stroke,
                )
                drawLine(
                    color = Color(0xFF38BDF8),
                    start = Offset(cx - r * 0.72f, cy + r * 0.72f),
                    end = Offset(cx + r * 0.95f, cy - r * 0.95f),
                    strokeWidth = stroke.width * 1.15f,
                    cap = StrokeCap.Round,
                )
            }

            AiVendor.OPENCODE -> {
                // Stylized < / > code emblem
                val left = Path().apply {
                    moveTo(cx - r * 0.35f, cy - r * 0.65f)
                    lineTo(cx - r * 0.95f, cy)
                    lineTo(cx - r * 0.35f, cy + r * 0.65f)
                }
                val right = Path().apply {
                    moveTo(cx + r * 0.35f, cy - r * 0.65f)
                    lineTo(cx + r * 0.95f, cy)
                    lineTo(cx + r * 0.35f, cy + r * 0.65f)
                }
                drawPath(left, color = tint, style = stroke)
                drawPath(right, color = tint, style = stroke)
                drawLine(
                    color = tint,
                    start = Offset(cx + r * 0.16f, cy - r * 0.78f),
                    end = Offset(cx - r * 0.16f, cy + r * 0.78f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }

            AiVendor.ANTIGRAVITY -> {
                // Orbital A-prism delta mark
                val delta = Path().apply {
                    moveTo(cx, cy - r * 0.95f)
                    lineTo(cx + r * 0.85f, cy + r * 0.78f)
                    lineTo(cx - r * 0.85f, cy + r * 0.78f)
                    close()
                }
                drawPath(delta, color = tint, style = stroke)
                drawCircle(color = Color(0xFF38BDF8), radius = r * 0.22f, center = Offset(cx, cy + r * 0.18f))
            }

            AiVendor.AIDER -> {
                // Dual interlocking chevrons
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

            AiVendor.GOOSE -> {
                // Geometric sweeping wing
                val wing = Path().apply {
                    moveTo(cx - r * 0.85f, cy + r * 0.45f)
                    lineTo(cx - r * 0.1f, cy - r * 0.75f)
                    lineTo(cx + r * 0.85f, cy - r * 0.2f)
                    lineTo(cx + r * 0.15f, cy + r * 0.65f)
                    close()
                }
                drawPath(wing, color = tint, style = stroke)
            }

            AiVendor.TMUX -> {
                // Classic tmux split-pane frame
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

            AiVendor.SHELL -> {
                // Crisp >_ prompt
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
