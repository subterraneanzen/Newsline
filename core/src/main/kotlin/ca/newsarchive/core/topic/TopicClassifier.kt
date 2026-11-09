package ca.newsarchive.core.topic

data class TopicTag(val topic: String, val confidence: Double)

/** Transparent keyword-based topic tagging. Users can add/remove topics manually in the app. */
object TopicClassifier {
    val topics: Map<String, List<String>> = linkedMapOf(
        "Politics" to listOf("government", "parliament", "minister", "premier", "prime minister", "president", "senate", "senator", "legislature",
            "mla", "mp", "mps", "cabinet", "opposition", "policy", "politics", "political", "liberal", "conservative", "ndp", "democrat",
            "democrats", "republican", "republicans", "bill", "legislation", "governor", "mayor", "city council", "council"),
        "Elections" to listOf("election", "elections", "vote", "voters", "voting", "ballot", "poll", "polls", "campaign", "candidate", "candidates", "byelection", "by-election", "referendum"),
        "Business & Economy" to listOf("economy", "economic", "inflation", "interest rate", "interest rates", "bank of canada", "federal reserve", "gdp", "recession",
            "stocks", "stock market", "tsx", "dow", "nasdaq", "earnings", "revenue", "profit", "company", "companies", "ceo", "layoffs", "jobs report",
            "unemployment", "trade", "tariff", "tariffs", "market", "markets", "investors", "housing market", "mortgage", "budget"),
        "Technology" to listOf("technology", "tech", "software", "app", "apps", "smartphone", "iphone", "android", "google", "apple", "microsoft", "meta",
            "amazon", "cybersecurity", "hack", "hackers", "data breach", "semiconductor", "chip", "chips", "internet", "startup", "startups", "computer"),
        "Artificial Intelligence" to listOf("artificial intelligence", "ai", "a.i.", "machine learning", "chatbot", "chatgpt", "openai", "anthropic", "claude",
            "gemini", "large language model", "llm", "deepfake", "generative ai", "neural network"),
        "Science" to listOf("science", "scientists", "scientist", "study", "research", "researchers", "physics", "chemistry", "biology", "genome",
            "fossil", "species", "evolution", "university study", "peer-reviewed", "journal nature", "discovery"),
        "Space" to listOf("nasa", "space", "astronaut", "rocket", "spacex", "satellite", "orbit", "mars", "moon", "lunar", "telescope", "asteroid", "galaxy", "planet"),
        "Climate & Environment" to listOf("climate", "climate change", "global warming", "emissions", "carbon", "greenhouse", "environment", "environmental",
            "wildfire", "wildfires", "drought", "flood", "flooding", "pollution", "biodiversity", "conservation", "renewable", "net zero", "carbon tax"),
        "Health" to listOf("health", "hospital", "hospitals", "doctor", "doctors", "nurses", "patients", "disease", "virus", "covid", "vaccine", "vaccines",
            "cancer", "mental health", "public health", "outbreak", "measles", "medical", "health care", "healthcare", "drug", "overdose"),
        "Sports" to listOf("nhl", "nfl", "nba", "mlb", "cfl", "mls", "fifa", "olympics", "olympic", "hockey", "football", "soccer", "basketball", "baseball",
            "curling", "tennis", "golf", "championship", "playoffs", "roughriders", "game", "season", "coach", "team", "tournament", "world cup", "stanley cup"),
        "Arts & Culture" to listOf("film", "movie", "music", "album", "concert", "festival", "museum", "art", "artist", "book", "novel", "author", "theatre",
            "theater", "television", "tv series", "actor", "actress", "celebrity", "culture"),
        "Crime & Justice" to listOf("police", "rcmp", "arrest", "arrested", "charged", "charges", "court", "trial", "judge", "jury", "murder", "homicide",
            "shooting", "stabbing", "crime", "criminal", "sentenced", "lawsuit", "supreme court", "prison", "investigation", "fraud"),
        "Conflict & Security" to listOf("war", "military", "troops", "army", "missile", "missiles", "drone strike", "airstrike", "airstrikes", "ceasefire",
            "invasion", "nato", "defence", "defense", "attack", "attacks", "soldiers", "conflict", "terrorism", "terrorist", "hostages", "sanctions"),
        "Energy" to listOf("oil", "gas", "pipeline", "pipelines", "energy", "electricity", "power grid", "nuclear", "uranium", "solar", "wind power",
            "opec", "oilsands", "oil sands", "lng", "hydro", "potash"),
        "Agriculture" to listOf("farm", "farmers", "farming", "agriculture", "crop", "crops", "harvest", "canola", "wheat", "grain", "cattle", "livestock", "ranchers", "seeding"),
        "Education" to listOf("school", "schools", "students", "teachers", "university", "universities", "college", "education", "tuition", "classroom", "school board"),
        "Indigenous Affairs" to listOf("indigenous", "first nation", "first nations", "métis", "metis", "inuit", "treaty", "reconciliation", "residential school", "fsin", "chief"),
        "Weather" to listOf("weather", "storm", "blizzard", "snowstorm", "tornado", "hurricane", "heat wave", "heatwave", "forecast", "environment canada", "rainfall", "snowfall", "freezing rain"),
        "Transportation" to listOf("highway", "traffic", "airline", "airlines", "airport", "flight", "flights", "rail", "railway", "transit", "bus", "collision", "crash"),
    )

    private val patterns: Map<String, Regex> = topics.mapValues { (_, terms) ->
        Regex("(?<![\\p{L}\\p{N}])(" + terms.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    fun classify(title: String, paragraphs: List<String>, categories: List<String> = emptyList()): List<TopicTag> {
        val scores = HashMap<String, Double>()
        val distinct = HashMap<String, MutableSet<String>>()
        fun scan(text: String, w: Double) {
            for ((topic, re) in patterns) re.findAll(text).forEach {
                scores.merge(topic, w, Double::plus)
                distinct.getOrPut(topic) { HashSet() } += it.value.lowercase()
            }
        }
        scan(title, 3.0)
        paragraphs.take(12).forEachIndexed { i, p -> scan(p, if (i < 3) 1.0 else 0.5) }
        for (c in categories) {
            val cl = c.lowercase()
            topics.keys.firstOrNull { t -> t.lowercase() == cl || t.lowercase().split(" & ").any { it == cl } }?.let {
                scores.merge(it, 4.0, Double::plus); distinct.getOrPut(it) { HashSet() } += "category:$cl"
            }
        }
        return scores.filter { (t, s) -> s >= 4.0 && (distinct[t]?.size ?: 0) >= 2 }
            .map { (t, s) -> TopicTag(t, minOf(1.0, s / 10.0)) }
            .sortedByDescending { it.confidence }
            .take(4)
    }
}
