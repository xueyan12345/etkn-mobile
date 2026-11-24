package xyz.etkn.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.etkn.mobile.data.model.Rating
import xyz.etkn.mobile.data.model.RatingSource

/**
 * A score with its source's mark in place of the source's name. The marks are drawn here in
 * each source's own colours (brand colours, not theme colours, so they stay out of Tokens.kt).
 */
@Composable
fun RatingScore(
    rating: Rating,
    modifier: Modifier = Modifier,
    /**
     * 用户口径（2026-11-26）：详情页信息区垫了深色底板，
     * 分数文字需要浅色才能看清。默认 null = 跟主题（其它页面不受影响）。
     */
    textColor: androidx.compose.ui.graphics.Color? = null,
) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "${rating.source.label} ${rating.text}" }, verticalAlignment = Alignment.CenterVertically) {
        RatingMark(rating, MARK_HEIGHT)
        Text(
            rating.text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = textColor ?: Color.Unspecified,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun RatingMark(rating: Rating, height: Dp) {
    when (rating.source) {
        RatingSource.Imdb -> Wordmark("IMDb", SolidColor(IMDB), Color.Black, height)
        RatingSource.Tmdb -> Wordmark("TMDB", Brush.horizontalGradient(listOf(TMDB_GREEN, TMDB_BLUE)), TMDB_NAVY, height)
        RatingSource.Douban -> Box(
            Modifier.size(height).background(DOUBAN, RoundedCornerShape(height / 4)),
            contentAlignment = Alignment.Center,
        ) { Text("豆", style = markText(height, Color.White)) }
        RatingSource.RottenTomatoes -> if (rating.fresh) Tomato(height) else Splat(height)
    }
}

@Composable
private fun Wordmark(text: String, fill: Brush, ink: Color, height: Dp) {
    Box(
        Modifier.height(height).background(fill, RoundedCornerShape(height / 4)).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = markText(height, ink)) }
}

private fun markText(height: Dp, color: Color) = TextStyle(
    color = color,
    fontSize = (height.value * 0.62f).sp,
    lineHeight = (height.value * 0.62f).sp,
    fontWeight = FontWeight.Black,
    letterSpacing = (-0.02).em,
)

/** Fresh: a red tomato with its green calyx. */
@Composable
private fun Tomato(height: Dp) = Canvas(Modifier.size(height)) {
    val r = size.minDimension / 2
    drawCircle(TOMATO, radius = r * 0.86f, center = Offset(center.x, center.y + r * 0.12f))
    val leaf = Size(r * 0.62f, r * 0.34f)
    drawOval(CALYX, topLeft = Offset(center.x - leaf.width, r * 0.02f), size = leaf)
    drawOval(CALYX, topLeft = Offset(center.x, r * 0.02f), size = leaf)
}

/** Rotten: a green splat. */
@Composable
private fun Splat(height: Dp) = Canvas(Modifier.size(height)) {
    val r = size.minDimension / 2
    drawCircle(SPLAT, radius = r * 0.62f)
    for (i in 0 until 6) {
        val angle = Math.toRadians(i * 60.0 + 15)
        val at = Offset(center.x + (r * 0.68f * Math.cos(angle)).toFloat(), center.y + (r * 0.68f * Math.sin(angle)).toFloat())
        drawCircle(SPLAT, radius = r * (if (i % 2 == 0) 0.3f else 0.22f), center = at)
    }
}

private val MARK_HEIGHT = 16.dp
private val IMDB = Color(0xFFF5C518)
private val TMDB_NAVY = Color(0xFF0D253F)
private val TMDB_GREEN = Color(0xFF90CEA1)
private val TMDB_BLUE = Color(0xFF01B4E4)
private val DOUBAN = Color(0xFF00B51D)
private val TOMATO = Color(0xFFFA320A)
private val CALYX = Color(0xFF00912D)
private val SPLAT = Color(0xFF0AC855)
