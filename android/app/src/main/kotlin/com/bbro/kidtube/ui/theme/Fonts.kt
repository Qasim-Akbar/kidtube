package com.bbro.kidtube.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.bbro.kidtube.R

/**
 * Bundled fonts (res/font, so they work offline). Both files are variable fonts; each weight is pinned with a
 * `wght` variation (API 26+, our minSdk). Latin: Nunito 500/700/800. Urdu: Noto Naskh Arabic 500/700
 * (a request for 800 resolves to the nearest, 700). Never Nastaliq.
 */
object KidTubeFonts {
    private fun nunito(weight: Int) = Font(
        resId = R.font.nunito,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    private fun naskh(weight: Int) = Font(
        resId = R.font.noto_naskh_arabic,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    val Latin: FontFamily = FontFamily(nunito(500), nunito(700), nunito(800))
    val Urdu: FontFamily = FontFamily(naskh(500), naskh(700))

    fun forLanguage(isUrdu: Boolean): FontFamily = if (isUrdu) Urdu else Latin
}
