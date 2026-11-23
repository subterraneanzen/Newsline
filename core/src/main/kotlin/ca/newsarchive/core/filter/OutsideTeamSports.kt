package ca.newsarchive.core.filter

/**
 * Spots stories about a city's own sports team from outside Saskatchewan (a Maple Leafs game, a
 * Yankees trade, an Arsenal match), so the Canada, United States and Europe sections can leave them
 * out. Looks only at the headline and summary.
 *
 * Kept: anything that names a Saskatchewan team, national teams and international events (Team Canada,
 * Olympics, World Cup, World Juniors), and championship finals (Grey Cup, Stanley Cup Final, Super Bowl,
 * World Series, NBA Finals), which are national news rather than one city's.
 */
object OutsideTeamSports {
    /** Names that only ever mean the team. */
    private val teams = listOf(
        // NHL
        "maple leafs", "leafs", "canadiens", "habs", "canucks", "oilers", "edmonton oilers", "calgary flames", "ottawa senators",
        "winnipeg jets", "bruins", "blackhawks", "red wings", "penguins", "flyers", "capitals", "lightning", "panthers",
        "hurricanes", "blue jackets", "predators", "avalanche", "golden knights", "kraken", "sabres", "devils", "islanders",
        "new york rangers", "los angeles kings", "la kings", "anaheim ducks", "san jose sharks", "minnesota wild", "st. louis blues",
        "dallas stars", "utah mammoth", "utah hockey club", "seattle kraken",
        // CFL (the Roughriders are Saskatchewan's)
        "blue bombers", "stampeders", "elks", "redblacks", "tiger-cats", "ticats", "argonauts", "argos", "alouettes", "bc lions", "b.c. lions",
        // NBA / WNBA / MLB / MLS / PWHL in Canada
        "raptors", "blue jays", "whitecaps", "cf montréal", "cf montreal", "toronto fc", "pwhl", "toronto tempo", "valkyries",
        // NFL
        "patriots", "cowboys", "packers", "steelers", "49ers", "niners", "chiefs", "eagles", "broncos fans", "dolphins", "bills mafia",
        "buffalo bills", "new york giants", "new york jets", "seahawks", "buccaneers", "bucs", "commanders", "ravens", "bengals",
        "browns", "texans", "colts", "jaguars", "titans", "raiders", "chargers", "rams", "vikings", "detroit lions", "chicago bears",
        "saints", "falcons", "carolina panthers", "arizona cardinals",
        // NBA
        "lakers", "celtics", "knicks", "warriors fans", "golden state warriors", "76ers", "sixers", "bucks", "nuggets", "timberwolves",
        "mavericks", "mavs", "clippers", "trail blazers", "pelicans", "grizzlies", "spurs", "rockets", "cavaliers", "cavs", "pistons",
        "hornets", "miami heat", "okc thunder", "oklahoma city thunder", "phoenix suns", "sacramento kings", "brooklyn nets", "orlando magic",
        "chicago bulls", "atlanta hawks", "utah jazz", "indiana pacers", "washington wizards",
        // MLB
        "yankees", "red sox", "dodgers", "mets", "cubs", "white sox", "astros", "braves", "phillies", "padres", "mariners",
        "orioles", "guardians", "tigers fans", "detroit tigers", "twins", "royals", "athletics", "diamondbacks", "rockies",
        "marlins", "nationals", "brewers", "reds", "pirates", "cardinals fans", "st. louis cardinals", "giants fans", "san francisco giants",
        "texas rangers", "tampa bay rays", "los angeles angels",
        // European football
        "manchester united", "man united", "man utd", "manchester city", "man city", "liverpool fc", "chelsea fc", "arsenal fc",
        "tottenham", "spurs fans", "newcastle united", "aston villa", "west ham", "everton", "real madrid", "barcelona fc", "fc barcelona",
        "barça", "atlético madrid", "atletico madrid", "bayern munich", "bayern münchen", "borussia dortmund", "paris saint-germain", "psg",
        "juventus", "ac milan", "inter milan", "napoli", "as roma", "ajax", "celtic fc", "rangers fc",
        // WHL / CHL teams outside Saskatchewan
        "wheat kings", "oil kings", "hitmen", "medicine hat tigers", "red deer rebels", "lethbridge hurricanes", "kamloops blazers",
        "kelowna rockets", "vancouver giants", "victoria royals", "prince george cougars", "everett silvertips", "portland winterhawks",
        "seattle thunderbirds", "spokane chiefs", "tri-city americans", "wenatchee wild", "winnipeg ice", "penticton vees",
    )

    /** Club names that are also ordinary words or cities: only count beside a sports word. */
    private val contextTeams = listOf(
        "arsenal", "liverpool", "chelsea", "barcelona", "flames", "senators", "jets", "kings", "giants", "rangers", "lions", "bears",
        "cardinals", "heat", "thunder", "suns", "nets", "magic", "bulls", "hawks", "jazz", "pacers", "wizards", "wild", "blues",
        "ducks", "sharks", "stars", "tigers", "angels", "rays", "warriors", "broncos", "bills", "jays",
    )

    private val sportsWords = listOf(
        "nhl", "nfl", "nba", "wnba", "mlb", "mls", "cfl", "whl", "ohl", "qmjhl", "ahl", "pwhl", "premier league", "la liga", "serie a",
        "bundesliga", "ligue 1", "champions league", "europa league", "fa cup", "game", "games", "match", "season", "playoff", "playoffs",
        "coach", "head coach", "manager", "quarterback", "goalie", "goaltender", "striker", "midfielder", "pitcher", "inning", "innings",
        "touchdown", "hat trick", "overtime", "shootout", "roster", "trade", "traded", "draft", "free agent", "signs", "signed", "contract",
        "injury", "injured", "beat", "beats", "defeat", "defeats", "win", "wins", "won", "loss", "loses", "lost", "victory", "rout",
        "score", "scores", "scored", "goal", "goals", "derby", "fixture", "transfer", "home opener", "series", "draw", "title race",
        "points", "standings", "sign", "lineup", "clinch", "clinches", "eliminated", "relegation", "kickoff", "stadium", "arena", "fans",
    )

    /** Saskatchewan teams: a story about them (even against an outside team) stays. */
    private val saskatchewan = listOf(
        "roughriders", "riders", "rider nation", "saskatchewan rush", "rush", "saskatoon blades", "blades", "regina pats", "pats",
        "swift current broncos", "moose jaw warriors", "prince albert raiders", "saskatoon berries", "regina red sox", "huskies",
        "regina cougars", "saskatchewan rattlers", "rattlers", "saskatoon", "regina", "saskatchewan", "sask.",
    )

    /** National teams, international events and championship finals: national news, not one city's. */
    private val national = listOf(
        "team canada", "canada's", "canadian team", "national team", "team usa", "olympic", "olympics", "paralympic", "world cup",
        "world juniors", "world junior", "4 nations", "four nations", "world championship", "world championships", "grey cup",
        "stanley cup final", "super bowl", "world series", "nba finals", "euro 2028", "commonwealth games", "pan am games",
    )

    private fun pattern(terms: List<String>) =
        Regex("(?<![\\p{L}\\p{N}])(" + terms.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    private val teamRe = pattern(teams)
    private val contextRe = pattern(contextTeams)
    private val sportsRe = pattern(sportsWords)
    private val saskRe = pattern(saskatchewan)
    private val nationalRe = pattern(national)

    fun isOutsideTeamStory(title: String, summary: String? = null): Boolean {
        val text = title + "\n" + (summary ?: "")
        if (saskRe.containsMatchIn(text) || nationalRe.containsMatchIn(text)) return false
        // The headline has to be about the team; a passing mention in the summary isn't enough.
        if (teamRe.containsMatchIn(title)) return true
        return contextRe.containsMatchIn(title) && sportsRe.containsMatchIn(text) &&
            (teamRe.containsMatchIn(text) || contextRe.findAll(text).count() >= 2 || sportsRe.findAll(text).count() >= 2)
    }

    /** The sections (Canada, United States, Europe) that leave these stories out. */
    val filteredSections = setOf("CA", "US", "C-EU")
}
