package io.github.mangi.eta.agent.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleMaidSpeechTest {
    @Test fun satietyBandsMatchTheReserveThresholds() {
        assertEquals(WhaleMaidSatietyBand.STARVING, whaleMaidSatietyBand(0))
        assertEquals(WhaleMaidSatietyBand.HUNGRY, whaleMaidSatietyBand(2_000))
        assertEquals(WhaleMaidSatietyBand.PECKISH, whaleMaidSatietyBand(5_000))
        assertEquals(WhaleMaidSatietyBand.FULL, whaleMaidSatietyBand(10_000))
        assertEquals(WhaleMaidSatietyBand.STUFFED, whaleMaidSatietyBand(20_001))
        assertEquals(100, whaleMaidSatietyPercent(30_000))
    }

    @Test fun reactionKeepsAValidMoodAndStripsEmoji() {
        val reaction = parseWhaleMaidReaction(
            raw = """{"mood":"happy","speech":"主人辛苦啦"}""",
            eventType = "work_done",
            satiety = 8_000,
            foodName = "",
            sessionTitle = "改设置",
        )
        assertEquals("happy", reaction.mood)
        assertFalse(reaction.speech.contains("\uD83D"))
        assertTrue(reaction.speech.length <= 25)
    }

    @Test fun unknownMoodFallsBackWithoutLeavingTheEightActions() {
        val reaction = parseWhaleMaidReaction(
            raw = """{"mood":"dance","speech":""}""",
            eventType = "feed",
            satiety = 100,
            foodName = "一碗米饭",
            sessionTitle = "",
        )
        assertEquals("eating", reaction.mood)
        assertTrue(reaction.speech.contains("一碗米饭"))
    }

    @Test fun promptNamesTheChosenFoodAndForbidsEmoji() {
        val prompt = whaleMaidPrompt(
            eventType = "feed",
            satiety = 1_000,
            foodName = "一勺米饭",
            tokensGained = 1_000,
            sessionTitle = "",
            recentTasks = listOf("修浮窗"),
            memories = listOf("上次说过"),
        )
        assertTrue(prompt.contains("一勺米饭"))
        assertTrue(prompt.contains("禁止出现任何 emoji"))
        assertTrue(prompt.contains("代鱼"))
    }
    @Test fun workDoneFallbackDoesNotEchoALongTitle() {
        val title = "让代鱼支持dsh-web-whale-maid"
        val reaction = parseWhaleMaidReaction(
            raw = "",
            eventType = "work_done",
            satiety = 12_000,
            foodName = "",
            sessionTitle = title,
        )
        assertFalse(reaction.speech.contains(title))
        assertEquals("搞定了，主人真能干。", reaction.speech)
        val echoed = parseWhaleMaidReaction(
            raw = """{"mood":"happy","speech":"《$title》"}""",
            eventType = "work_done",
            satiety = 12_000,
            foodName = "",
            sessionTitle = title,
        )
        assertFalse(echoed.speech.contains(title))
    }
}
