package dev.cannoli.scorza.di

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppFonts @Inject constructor(@ApplicationContext context: Context) {
    val plexMono: FontFamily
    val plexMonoBold: FontFamily
    val mplus1Code: FontFamily
    val bpReplay: FontFamily
    val plexMonoTypeface: Typeface
    val mplus1CodeTypeface: Typeface
    val bpReplayTypeface: Typeface

    init {
        val plex = Typeface.createFromAsset(context.assets, "fonts/IBMPlexMono-SemiBold.ttf")
        plexMonoTypeface = plex
        plexMono = FontFamily(ComposeTypeface(plex))
        plexMonoBold = FontFamily(ComposeTypeface(Typeface.createFromAsset(context.assets, "fonts/IBMPlexMono-Bold.ttf")))
        val mplus = Typeface.createFromAsset(context.assets, "fonts/MPlus-1c-NerdFont-Bold.ttf")
        mplus1CodeTypeface = mplus
        mplus1Code = FontFamily(ComposeTypeface(mplus))
        val bp = Typeface.createFromAsset(context.assets, "fonts/BPreplayBold-unhinted.otf")
        bpReplayTypeface = bp
        bpReplay = FontFamily(ComposeTypeface(bp))
    }
}
