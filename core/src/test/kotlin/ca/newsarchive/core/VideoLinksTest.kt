package ca.newsarchive.core

import ca.newsarchive.core.filter.VideoLinks
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoLinksTest {
    @Test fun recognisesVideoLinks() {
        listOf(
            "https://www.cbsnews.com/video/hero-dad-describes-split-second-decision/",
            "https://abcnews.com/video/138004494/",
            "https://www.cbc.ca/player/play/9.7416747?cmp=rss",
            "https://www.theglobeandmail.com/canada/video-opposition-leaders-react-to-2026-budget/",
            "https://www.aljazeera.com/video/newsfeed/2026/11/10/qatari-pms-visit",
            "https://www.france24.com/en/video/20261110-kristallnacht",
            "https://www.today.com/video/republicans-democrats-273189957601",
            "https://www.youtube.com/watch?v=abc",
            "https://abcnews.com/Live",
        ).forEach { assertTrue(VideoLinks.isVideo(it, null), it) }
        assertTrue(VideoLinks.isVideo("https://abcnews.com/US/story?id=1", "WATCH:  Officer shot 6 times celebrates wedding"))
    }

    @Test fun keepsArticles() {
        listOf(
            "https://www.cbc.ca/news/canada/saskatchewan/regina-police-officer-charged-9.7416492?cmp=rss",
            "https://globalnews.ca/news/12144337/glp1-vision-loss-warning-fda/",
            "https://www.theglobeandmail.com/business/article-ottawa-arctic-drilling-ban-decision-mark-carney/",
            "https://www.ctvnews.ca/news/video-game-industry-layoffs/",
            "https://www.npr.org/2026/11/10/nx-s1-6026659/average-vehicle-price-record-high",
        ).forEach { assertFalse(VideoLinks.isVideo(it, "Regina police officer charged"), it) }
        assertFalse(VideoLinks.isVideo("https://x.ca/a", "Watchdog says fines were never collected"))
    }
}
