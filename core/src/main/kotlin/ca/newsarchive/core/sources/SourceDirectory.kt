package ca.newsarchive.core.sources

data class DirectorySource(
    val name: String,
    val feedUrl: String,
    val homepage: String,
    val category: String,
    /** Publisher's home place (ISO country / region / city code). Not applied to articles automatically. */
    val placeCode: String?,
    val language: String,
    /** Local/regional publisher: its home place may be used as a low-confidence fallback tag. */
    val local: Boolean,
    /** Enabled on first launch so the app has something to show. */
    val starter: Boolean,
    /** RSS (default, also covers Atom/RDF), SITEMAP, or WEBSITE (page scanning when no feed exists). */
    val kind: String = "RSS",
)

/**
 * Built-in directory of publisher feeds. Entries are NOT guaranteed to stay valid — publishers move
 * feeds. Each one is validated when enabled and its status is shown in Source management.
 * No political classification is assigned to any publisher.
 */
object SourceDirectory {
    private const val DATA = """
International|BBC News – World|https://feeds.bbci.co.uk/news/world/rss.xml|https://www.bbc.com/news/world|GB|en|N|Y
International|BBC News – Top Stories|https://feeds.bbci.co.uk/news/rss.xml|https://www.bbc.com/news|GB|en|N|N
International|Al Jazeera English|https://www.aljazeera.com/xml/rss/all.xml|https://www.aljazeera.com|QA|en|N|Y
International|The Guardian – World|https://www.theguardian.com/world/rss|https://www.theguardian.com/world|GB|en|N|Y
International|Deutsche Welle (English)|https://rss.dw.com/rdf/rss-en-all|https://www.dw.com/en|DE|en|N|Y
International|France 24 (English)|https://www.france24.com/en/rss|https://www.france24.com/en|FR|en|N|Y
International|RFI (English)|https://www.rfi.fr/en/rss|https://www.rfi.fr/en|FR|en|N|N
International|UN News|https://news.un.org/feed/subscribe/en/news/all/rss.xml|https://news.un.org|-|en|N|N
International|Euronews|https://www.euronews.com/rss?format=mrss&level=theme&name=news|https://www.euronews.com|FR|en|N|N
International|Christian Science Monitor|https://rss.csmonitor.com/feeds/all|https://www.csmonitor.com|US|en|N|N
International|Global Voices|https://globalvoices.org/feed/|https://globalvoices.org|-|en|N|N
Canada – National|CBC News – Top Stories|https://www.cbc.ca/webfeed/rss/rss-topstories|https://www.cbc.ca/news|CA|en|N|Y
Canada – National|CBC News – Canada|https://www.cbc.ca/webfeed/rss/rss-canada|https://www.cbc.ca/news/canada|CA|en|N|N
Canada – National|CBC News – Politics|https://www.cbc.ca/webfeed/rss/rss-politics|https://www.cbc.ca/news/politics|CA|en|N|N
Canada – National|CBC News – World|https://www.cbc.ca/webfeed/rss/rss-world|https://www.cbc.ca/news/world|CA|en|N|N
Canada – National|CBC News – Business|https://www.cbc.ca/webfeed/rss/rss-business|https://www.cbc.ca/news/business|CA|en|N|N
Canada – National|CBC News – Indigenous|https://www.cbc.ca/webfeed/rss/rss-indigenous|https://www.cbc.ca/news/indigenous|CA|en|N|N
Canada – National|Global News|https://globalnews.ca/feed/|https://globalnews.ca|CA|en|N|Y
Canada – National|National Post|https://nationalpost.com/feed|https://nationalpost.com|CA|en|N|N
Canada – National|The Globe and Mail – Canada|https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/canada/|https://www.theglobeandmail.com|CA|en|N|Y
Canada – National|The Globe and Mail – Politics|https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/politics/|https://www.theglobeandmail.com|CA|en|N|N
Canada – National|Toronto Sun|https://torontosun.com/feed|https://torontosun.com|CA-ON:toronto|en|N|N
Canada – National|CityNews Toronto|https://toronto.citynews.ca/feed/|https://toronto.citynews.ca|CA-ON:toronto|en|Y|N
Canada – National|The Canadian Press|https://www.thecanadianpressnews.ca/|https://www.thecanadianpressnews.ca|CA|en|N|Y|WEBSITE
Canada – National|CTV News|https://www.ctvnews.ca/arc/outboundfeeds/rss/?outputType=xml|https://www.ctvnews.ca|CA|en|N|Y
United States|CNBC – Top News|https://www.cnbc.com/id/100003114/device/rss/rss.html|https://www.cnbc.com|US|en|N|Y
United States|MS NOW|https://www.ms.now/feed|https://www.ms.now|US|en|N|Y
Asia|CGTN – World|https://www.cgtn.com/subscribe/rss/section/world.xml|https://www.cgtn.com|CN|en|N|Y
Canada – National|The Hub|https://thehub.ca/feed/|https://thehub.ca|CA|en|N|N
Canada – National|The Tyee|https://thetyee.ca/rss2.xml|https://thetyee.ca|CA-BC|en|N|N
Canada – National|The Narwhal|https://thenarwhal.ca/feed/|https://thenarwhal.ca|CA|en|N|N
Canada – National|APTN News|https://www.aptnnews.ca/feed/|https://www.aptnnews.ca|CA|en|N|Y
Canada – National|Canadaland|https://www.canadaland.com/feed/|https://www.canadaland.com|CA|en|N|N
Canada – National|Radio-Canada Info|https://ici.radio-canada.ca/rss/4159|https://ici.radio-canada.ca|CA|fr|N|N
Canada – National|Le Devoir|https://www.ledevoir.com/rss/manchettes.xml|https://www.ledevoir.com|CA-QC|fr|N|N
Saskatchewan|CBC Saskatchewan|https://www.cbc.ca/webfeed/rss/rss-canada-saskatchewan|https://www.cbc.ca/news/canada/saskatchewan|CA-SK|en|Y|Y
Saskatchewan|CBC Saskatoon|https://www.cbc.ca/webfeed/rss/rss-canada-saskatoon|https://www.cbc.ca/news/canada/saskatoon|CA-SK:saskatoon|en|Y|N
Saskatchewan|Regina Leader-Post|https://leaderpost.com/news-sitemap.xml|https://leaderpost.com|CA-SK:regina|en|Y|Y|SITEMAP
Saskatchewan|Saskatoon StarPhoenix|https://thestarphoenix.com/news-sitemap.xml|https://thestarphoenix.com|CA-SK:saskatoon|en|Y|Y|SITEMAP
Saskatchewan|Global News Regina|https://globalnews.ca/regina/feed/|https://globalnews.ca/regina|CA-SK:regina|en|Y|N
Saskatchewan|Global News Saskatoon|https://globalnews.ca/saskatoon/feed/|https://globalnews.ca/saskatoon|CA-SK:saskatoon|en|Y|N
Saskatchewan|SASKTODAY.ca|https://www.sasktoday.ca/rss|https://www.sasktoday.ca|CA-SK|en|Y|N
Saskatchewan|paNOW (Prince Albert)|https://panow.com/feed/|https://panow.com|CA-SK:prince-albert|en|Y|Y
Canada – Provincial|CBC Manitoba|https://www.cbc.ca/webfeed/rss/rss-canada-manitoba|https://www.cbc.ca/news/canada/manitoba|CA-MB|en|Y|N
Canada – Provincial|CBC Calgary|https://www.cbc.ca/webfeed/rss/rss-canada-calgary|https://www.cbc.ca/news/canada/calgary|CA-AB:calgary|en|Y|N
Canada – Provincial|CBC Edmonton|https://www.cbc.ca/webfeed/rss/rss-canada-edmonton|https://www.cbc.ca/news/canada/edmonton|CA-AB:edmonton|en|Y|N
Canada – Provincial|CBC British Columbia|https://www.cbc.ca/webfeed/rss/rss-canada-britishcolumbia|https://www.cbc.ca/news/canada/british-columbia|CA-BC|en|Y|N
Canada – Provincial|CBC Toronto|https://www.cbc.ca/webfeed/rss/rss-canada-toronto|https://www.cbc.ca/news/canada/toronto|CA-ON:toronto|en|Y|N
Canada – Provincial|CBC Ottawa|https://www.cbc.ca/webfeed/rss/rss-canada-ottawa|https://www.cbc.ca/news/canada/ottawa|CA-ON:ottawa|en|Y|N
Canada – Provincial|CBC Montreal|https://www.cbc.ca/webfeed/rss/rss-canada-montreal|https://www.cbc.ca/news/canada/montreal|CA-QC:montreal|en|Y|N
Canada – Provincial|CBC Nova Scotia|https://www.cbc.ca/webfeed/rss/rss-canada-novascotia|https://www.cbc.ca/news/canada/nova-scotia|CA-NS|en|Y|N
Canada – Provincial|CBC New Brunswick|https://www.cbc.ca/webfeed/rss/rss-canada-newbrunswick|https://www.cbc.ca/news/canada/new-brunswick|CA-NB|en|Y|N
Canada – Provincial|CBC Newfoundland & Labrador|https://www.cbc.ca/webfeed/rss/rss-canada-newfoundland|https://www.cbc.ca/news/canada/newfoundland-labrador|CA-NL|en|Y|N
Canada – Provincial|CBC North|https://www.cbc.ca/webfeed/rss/rss-canada-north|https://www.cbc.ca/news/canada/north|CA|en|Y|N
Canada – Provincial|Calgary Herald|https://calgaryherald.com/feed|https://calgaryherald.com|CA-AB:calgary|en|Y|N
Canada – Provincial|Edmonton Journal|https://edmontonjournal.com/feed|https://edmontonjournal.com|CA-AB:edmonton|en|Y|N
Canada – Provincial|Vancouver Sun|https://vancouversun.com/feed|https://vancouversun.com|CA-BC:vancouver|en|Y|N
Canada – Provincial|Ottawa Citizen|https://ottawacitizen.com/feed|https://ottawacitizen.com|CA-ON:ottawa|en|Y|N
Canada – Provincial|Montreal Gazette|https://montrealgazette.com/feed|https://montrealgazette.com|CA-QC:montreal|en|Y|N
United States|NPR News|https://feeds.npr.org/1001/rss.xml|https://www.npr.org|US|en|N|Y
United States|PBS NewsHour|https://www.pbs.org/newshour/feeds/rss/headlines|https://www.pbs.org/newshour|US|en|N|N
United States|The New York Times – Home|https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml|https://www.nytimes.com|US|en|N|N
United States|The Washington Post – World|https://feeds.washingtonpost.com/rss/world|https://www.washingtonpost.com|US|en|N|N
United States|The Wall Street Journal – World|https://feeds.a.dj.com/rss/RSSWorldNews.xml|https://www.wsj.com|US|en|N|N
United States|CBS News|https://www.cbsnews.com/latest/rss/main|https://www.cbsnews.com|US|en|N|Y
United States|ABC News (US)|https://abcnews.go.com/abcnews/topstories|https://abcnews.go.com|US|en|N|Y
United States|NBC News|https://feeds.nbcnews.com/nbcnews/public/news|https://www.nbcnews.com|US|en|N|Y
United States|Fox News|https://moxie.foxnews.com/google-publisher/latest.xml|https://www.foxnews.com|US|en|N|N
United States|Politico|https://rss.politico.com/politics-news.xml|https://www.politico.com|US|en|N|N
United States|The Hill|https://thehill.com/feed/|https://thehill.com|US|en|N|N
United States|The Atlantic|https://www.theatlantic.com/feed/all/|https://www.theatlantic.com|US|en|N|N
United States|National Review|https://www.nationalreview.com/feed/|https://www.nationalreview.com|US|en|N|N
United States|Reason|https://reason.com/feed/|https://reason.com|US|en|N|N
United States|The Federalist|https://thefederalist.com/feed/|https://thefederalist.com|US|en|N|N
United States|The Nation|https://www.thenation.com/feed/?post_type=article|https://www.thenation.com|US|en|N|N
United States|Mother Jones|https://www.motherjones.com/feed/|https://www.motherjones.com|US|en|N|N
Europe|Sky News – World|https://feeds.skynews.com/feeds/rss/world.xml|https://news.sky.com|GB|en|N|N
Europe|The Independent – World|https://www.independent.co.uk/news/world/rss|https://www.independent.co.uk|GB|en|N|N
Europe|RTÉ News|https://www.rte.ie/feeds/rss/?index=/news/|https://www.rte.ie/news|IE|en|N|N
Europe|Politico Europe|https://www.politico.eu/feed/|https://www.politico.eu|C-EU|en|N|N
Europe|Der Spiegel International|https://www.spiegel.de/international/index.rss|https://www.spiegel.de/international|DE|en|N|N
Europe|Le Monde (English)|https://www.lemonde.fr/en/rss/une.xml|https://www.lemonde.fr/en|FR|en|N|N
Europe|The Kyiv Independent|https://kyivindependent.com/rss/|https://kyivindependent.com|UA|en|N|N
Europe|Meduza (English)|https://meduza.io/rss/en/all|https://meduza.io/en|RU|en|N|N
Europe|El País|https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/portada|https://elpais.com|ES|es|N|N
Asia|South China Morning Post – World|https://www.scmp.com/rss/91/feed|https://www.scmp.com|CN|en|N|N
Asia|The Japan Times|https://www.japantimes.co.jp/feed/|https://www.japantimes.co.jp|JP|en|N|N
Asia|CNA (Singapore)|https://www.channelnewsasia.com/rssfeeds/8395986|https://www.channelnewsasia.com|SG|en|N|N
Asia|The Hindu – International|https://www.thehindu.com/news/international/feeder/default.rss|https://www.thehindu.com|IN|en|N|N
Asia|Times of India – Top Stories|https://timesofindia.indiatimes.com/rssfeedstopstories.cms|https://timesofindia.indiatimes.com|IN|en|N|N
Asia|Dawn (Pakistan)|https://www.dawn.com/feeds/home|https://www.dawn.com|PK|en|N|N
Asia|Yonhap (English)|https://en.yna.co.kr/RSS/news.xml|https://en.yna.co.kr|KR|en|N|N
Asia|Taipei Times|https://www.taipeitimes.com/xml/index.rss|https://www.taipeitimes.com|TW|en|N|N
Asia|Bangkok Post|https://www.bangkokpost.com/rss/data/topstories.xml|https://www.bangkokpost.com|TH|en|N|N
Asia|Rappler|https://www.rappler.com/feed/|https://www.rappler.com|PH|en|N|N
Asia|The Diplomat|https://thediplomat.com/feed/|https://thediplomat.com|C-AS|en|N|N
Oceania|ABC News (Australia) – Just In|https://www.abc.net.au/news/feed/51120/rss.xml|https://www.abc.net.au/news|AU|en|N|N
Oceania|RNZ – World|https://www.rnz.co.nz/rss/world.xml|https://www.rnz.co.nz|NZ|en|N|N
Africa|AllAfrica – Latest|https://allafrica.com/tools/headlines/rdf/latest/headlines.rdf|https://allafrica.com|C-AF|en|N|N
Africa|Daily Maverick|https://www.dailymaverick.co.za/dmrss/|https://www.dailymaverick.co.za|ZA|en|N|N
Africa|Mail & Guardian|https://mg.co.za/feed/|https://mg.co.za|ZA|en|N|N
Africa|News24 – Top Stories|https://feeds.news24.com/articles/news24/TopStories/rss|https://www.news24.com|ZA|en|N|N
Africa|Premium Times (Nigeria)|https://www.premiumtimesng.com/feed|https://www.premiumtimesng.com|NG|en|N|N
South America|MercoPress|https://en.mercopress.com/rss|https://en.mercopress.com|C-SA|en|N|N
South America|Buenos Aires Times|https://www.batimes.com.ar/feed|https://www.batimes.com.ar|AR|en|N|N
South America|The Rio Times|https://www.riotimesonline.com/feed/|https://www.riotimesonline.com|BR|en|N|N
Middle East|The Times of Israel|https://www.timesofisrael.com/feed/|https://www.timesofisrael.com|IL|en|N|N
Middle East|The Jerusalem Post|https://www.jpost.com/rss/rssfeedsfrontpage.aspx|https://www.jpost.com|IL|en|N|N
Middle East|Middle East Eye|https://www.middleeasteye.net/rss|https://www.middleeasteye.net|C-ME|en|N|N
Middle East|Al-Monitor|https://www.al-monitor.com/rss|https://www.al-monitor.com|C-ME|en|N|N
Middle East|The National (UAE)|https://www.thenationalnews.com/arc/outboundfeeds/rss/?outputType=xml|https://www.thenationalnews.com|AE|en|N|N
Investigative & Independent|ProPublica|https://www.propublica.org/feeds/propublica/main|https://www.propublica.org|US|en|N|Y
Investigative & Independent|Bellingcat|https://www.bellingcat.com/feed/|https://www.bellingcat.com|-|en|N|N
Investigative & Independent|ICIJ|https://www.icij.org/feed/|https://www.icij.org|-|en|N|N
Investigative & Independent|The Intercept|https://theintercept.com/feed/?rss|https://theintercept.com|US|en|N|N
Investigative & Independent|The Conversation (Canada)|https://theconversation.com/ca/articles.atom|https://theconversation.com/ca|CA|en|N|N
Science|ScienceDaily – All|https://www.sciencedaily.com/rss/all.xml|https://www.sciencedaily.com|-|en|N|Y
Science|Nature – News|https://www.nature.com/nature.rss|https://www.nature.com|-|en|N|N
Science|Science (AAAS) – News|https://www.science.org/rss/news_current.xml|https://www.science.org|-|en|N|N
Science|New Scientist|https://www.newscientist.com/feed/home/|https://www.newscientist.com|-|en|N|N
Science|Phys.org|https://phys.org/rss-feed/|https://phys.org|-|en|N|N
Science|Quanta Magazine|https://www.quantamagazine.org/feed/|https://www.quantamagazine.org|-|en|N|N
Science|Scientific American|http://rss.sciam.com/ScientificAmerican-Global|https://www.scientificamerican.com|-|en|N|N
Technology|Ars Technica|https://feeds.arstechnica.com/arstechnica/index|https://arstechnica.com|-|en|N|Y
Technology|The Verge|https://www.theverge.com/rss/index.xml|https://www.theverge.com|-|en|N|N
Technology|Wired|https://www.wired.com/feed/rss|https://www.wired.com|-|en|N|N
Technology|TechCrunch|https://techcrunch.com/feed/|https://techcrunch.com|-|en|N|N
Technology|MIT Technology Review|https://www.technologyreview.com/feed/|https://www.technologyreview.com|-|en|N|N
Technology|Engadget|https://www.engadget.com/rss.xml|https://www.engadget.com|-|en|N|N
Technology|IEEE Spectrum|https://spectrum.ieee.org/feeds/feed.rss|https://spectrum.ieee.org|-|en|N|N
Technology|404 Media|https://www.404media.co/rss/|https://www.404media.co|-|en|N|N
Space & Astronomy|NASA – News Releases|https://www.nasa.gov/news-release/feed/|https://www.nasa.gov|-|en|N|Y
Space & Astronomy|Space.com|https://www.space.com/feeds/all|https://www.space.com|-|en|N|Y
Space & Astronomy|SpaceNews|https://spacenews.com/feed/|https://spacenews.com|-|en|N|N
Space & Astronomy|ESA – Space News|https://www.esa.int/rss/news.rss|https://www.esa.int|-|en|N|N
Space & Astronomy|Universe Today|https://www.universetoday.com/feed/|https://www.universetoday.com|-|en|N|N
Space & Astronomy|Sky & Telescope|https://skyandtelescope.org/feed/|https://skyandtelescope.org|-|en|N|N
Space & Astronomy|NASASpaceflight.com|https://www.nasaspaceflight.com/feed/|https://www.nasaspaceflight.com|-|en|N|N
Space & Astronomy|Spaceflight Now|https://spaceflightnow.com/feed/|https://spaceflightnow.com|-|en|N|N
Space & Astronomy|The Planetary Society|https://www.planetary.org/rss/articles|https://www.planetary.org|-|en|N|N
Space & Astronomy|Canadian Space Agency|https://api.io.canada.ca/io-server/gc/news/en/v2?dept=spaceagency&sort=publishedDate&orderBy=desc&pick=50&format=atom&atomtitle=Canadian%20Space%20Agency|https://www.asc-csa.gc.ca|CA|en|N|N
Space & Astronomy|SpacePolicyOnline|https://spacepolicyonline.com/feed/|https://spacepolicyonline.com|US|en|N|N
Space & Astronomy|Payload|https://payloadspace.com/feed/|https://payloadspace.com|US|en|N|N
Defense & Military|Defense News|https://www.defensenews.com/feed/|https://www.defensenews.com|US|en|N|N
Defense & Military|Defense News – Space|https://www.defensenews.com/space/feed/|https://www.defensenews.com/space|US|en|N|N
Defense & Military|Breaking Defense|https://breakingdefense.com/feed/|https://breakingdefense.com|US|en|N|N
Defense & Military|Defense One|https://www.defenseone.com/rss/all/|https://www.defenseone.com|US|en|N|N
Defense & Military|DefenseScoop|https://defensescoop.com/feed/|https://defensescoop.com|US|en|N|N
Defense & Military|Military Times|https://www.militarytimes.com/feed/|https://www.militarytimes.com|US|en|N|N
Defense & Military|The War Zone|https://www.twz.com/feed|https://www.twz.com|US|en|N|N
Defense & Military|USNI News|https://news.usni.org/feed|https://news.usni.org|US|en|N|N
Defense & Military|Naval News|https://www.navalnews.com/feed/|https://www.navalnews.com|-|en|N|N
Defense & Military|Air & Space Forces Magazine|https://www.airandspaceforces.com/feed/|https://www.airandspaceforces.com|US|en|N|N
Defense & Military|Task & Purpose|https://taskandpurpose.com/feed/|https://taskandpurpose.com|US|en|N|N
Defense & Military|War on the Rocks|https://warontherocks.com/feed/|https://warontherocks.com|-|en|N|N
Defense & Military|U.S. Department of War – News|https://www.war.gov/DesktopModules/ArticleCS/RSS.ashx?ContentType=1&Site=945&max=10|https://www.war.gov|US|en|N|N
Defense & Military|U.S. Space Force – News|https://www.spaceforce.mil/DesktopModules/ArticleCS/RSS.ashx?ContentType=1&Site=1060&max=10|https://www.spaceforce.mil|US|en|N|N
Defense & Military|Canadian Defence Review|https://canadiandefencereview.com/feed/|https://canadiandefencereview.com|CA|en|N|N
Defense & Military|CDA Institute|https://cdainstitute.ca/feed/|https://cdainstitute.ca|CA|en|N|N
Defense & Military|Legion Magazine|https://legionmagazine.com/feed/|https://legionmagazine.com|CA|en|N|N
Defense & Military|UK Defence Journal|https://ukdefencejournal.org.uk/feed/|https://ukdefencejournal.org.uk|GB|en|N|N
Defense & Military|RUSI – Commentary|https://www.rusi.org/rss/latest-commentary.xml|https://www.rusi.org|GB|en|N|N
Defense & Military|Arms Control Association|https://www.armscontrol.org/rss.xml|https://www.armscontrol.org|US|en|N|N
"""

    val all: List<DirectorySource> by lazy {
        DATA.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { l ->
            val f = l.split('|')
            DirectorySource(
                category = f[0], name = f[1], feedUrl = f[2], homepage = f[3],
                placeCode = f[4].takeUnless { it == "-" }, language = f[5],
                local = f[6] == "Y", starter = f[7] == "Y", kind = f.getOrElse(8) { "RSS" },
            )
        }
    }

    val categories: List<String> get() = all.map { it.category }.distinct()
}
