package io.github.pmaxai.cardscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pmaxai.cardscanner.vision.VersionResolver
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The printed script decides an Asian card's language: cards drawn with the phone's own
 * Japanese, Korean and Chinese fonts (the same card text in each), read by the real readers.
 * Western cards stay western.
 */
@RunWith(AndroidJUnit4::class)
class ScriptDetectionTest {
    companion object {
        private lateinit var resolver: VersionResolver
        @BeforeClass @JvmStatic fun start() { resolver = VersionResolver().also { it.warmUp() } }
        @AfterClass @JvmStatic fun stop() { resolver.close() }
    }

    /** A card 720×1006 like the scanner's straightened one: name bar, type line, rules, attack, footer, number. */
    private fun card(locale: Locale, name: String, stage: String, rules: List<String>, attack: String, footer: String, number: String): Bitmap {
        val bmp = Bitmap.createBitmap(720, 1006, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(222, 236, 150))
        fun paint(size: Float, bold: Boolean = false) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = size; textLocale = locale
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        c.drawText(stage, 40f, 55f, paint(22f))
        c.drawText(name, 40f, 105f, paint(46f, bold = true))
        c.drawText("HP160", 560f, 105f, paint(36f, bold = true))
        var y = 560f
        for (r in rules) {
            val layout = StaticLayout.Builder.obtain(r, 0, r.length, paint(26f), 640).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            c.save(); c.translate(40f, y); layout.draw(c); c.restore()
            y += layout.height + 18
        }
        c.drawText(attack, 40f, y + 40, paint(32f, bold = true))
        c.drawText(footer, 40f, 900f, paint(22f))
        c.drawText(number, 60f, 975f, paint(22f))
        return bmp
    }

    private fun check(lang: String?, bmp: Bitmap) {
        val t = resolver.read(bmp)
        Log.i("ScriptDetection", "expected $lang: got ${t.lang} · ${t.script} · number ${t.number}/${t.total} · name '${t.nameText}'")
        assertEquals("language (${t.script})", lang, t.lang)
        if (lang != null && lang != "en") assertTrue("the name bar is read in its script: '${t.nameText}'", t.nameText.isNotBlank())
    }

    @Test fun japanese() = check("ja", card(Locale.JAPANESE, "メガニウム", "2進化  ベイリーフから進化", listOf(
        "特性  おいしげる",
        "このポケモンがいるかぎり、自分のポケモン全員についている「基本草エネルギー」は、それぞれ草エネルギー2個ぶんとしてはたらく。この特性の効果は重ならない。"),
        "ソーラービーム    140", "弱点 ×2   抵抗力   にげる", "005/063"))

    @Test fun korean() = check("ko", card(Locale.KOREAN, "메가니움", "2진화  베이리프에서 진화", listOf(
        "특성  무성하다",
        "이 포켓몬이 있는 한, 자신의 포켓몬 전원에게 붙어 있는 「기본 풀 에너지」는 각각 풀 에너지 2개만큼으로 작동한다. 이 특성의 효과는 중복되지 않는다."),
        "솔라빔    140", "약점 ×2   저항력   후퇴", "005/063"))

    @Test fun chineseTraditional() = check("zh-tw", card(Locale.TRADITIONAL_CHINESE, "大竺葵", "2階進化  從月桂葉進化", listOf(
        "特性  茂盛",
        "只要這隻寶可夢在場上，己方所有寶可夢身上附著的「基本草能量」，每張都視為2個草能量。這個特性的效果無法重複。"),
        "日光束    140", "弱點 ×2   抵抗力   撤退", "005/063"))

    @Test fun chineseSimplified() = check("zh-cn", card(Locale.SIMPLIFIED_CHINESE, "大竺葵", "2阶进化  从月桂叶进化", listOf(
        "特性  茂盛",
        "只要这只宝可梦在场上，己方所有宝可梦身上附着的「基本草能量」，每张都视为2个草能量。这个特性的效果无法重复。"),
        "日光束    140", "弱点 ×2   抵抗力   撤退", "005/063"))

    @Test fun englishStaysEnglish() = check("en", card(Locale.ENGLISH, "Meganium", "Stage 2  Evolves from Bayleef", listOf(
        "Ability  Lush Growth",
        "As long as this Pokémon is in play, each Basic Grass Energy attached to your Pokémon provides GrassGrass Energy. You can't apply more than 1 Lush Growth Ability at a time."),
        "Solar Beam    140", "Weakness ×2   Resistance   Retreat", "010/132"))
}
