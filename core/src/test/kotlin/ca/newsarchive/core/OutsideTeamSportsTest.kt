package ca.newsarchive.core

import ca.newsarchive.core.filter.OutsideTeamSports.isOutsideTeamStory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutsideTeamSportsTest {
    @Test fun dropsOtherCitiesTeams() {
        assertTrue(isOutsideTeamStory("Maple Leafs beat Bruins 4-2 behind Matthews hat trick"))
        assertTrue(isOutsideTeamStory("Yankees trade for veteran reliever ahead of deadline"))
        assertTrue(isOutsideTeamStory("Arsenal held to draw as title race tightens", "The Premier League leaders dropped points at home."))
        assertTrue(isOutsideTeamStory("Flames sign defenceman to three-year contract", "Calgary's NHL club made the move Tuesday."))
        assertTrue(isOutsideTeamStory("Oilers' McDavid out with injury"))
    }

    @Test fun keepsSaskatchewanNationalAndNonSports() {
        assertFalse(isOutsideTeamStory("Roughriders edge Blue Bombers in West Final"))
        assertFalse(isOutsideTeamStory("Blades top Wheat Kings in overtime"))
        assertFalse(isOutsideTeamStory("Team Canada names world juniors roster, two Oilers prospects included"))
        assertFalse(isOutsideTeamStory("Alouettes and Argonauts set for Grey Cup"))
        assertFalse(isOutsideTeamStory("Senators debate border bill as deadline looms"))
        assertFalse(isOutsideTeamStory("Flames force evacuation of B.C. town as wildfire grows"))
        assertFalse(isOutsideTeamStory("Liverpool council approves new housing plan"))
        assertFalse(isOutsideTeamStory("Bank of Canada holds interest rate"))
    }
}
