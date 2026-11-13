package ca.newsarchive.core.geo

/**
 * Embedded place data. Format per line: code|name|parent|aliases(comma separated)
 * Aliases prefixed with '?' are ambiguous (common words or names) and only count when
 * the parent place is also mentioned.
 */
internal object GazetteerData {
    val continents = """
        AF|Africa||African,Africans,sub-Saharan
        AS|Asia||Asian,Asia-Pacific
        EU|Europe||European,Europeans,EU,European Union,Brussels
        ME|Middle East||Middle Eastern,Mideast,Gulf states
        NA|North America||North American
        SA|South America||South American,Latin America,Latin American
        OC|Oceania||Pacific Islands
        AN|Antarctica||Antarctic
    """.trimIndent()

    val countries = """
        CA|Canada|NA|Canadian,Canadians,Ottawa,Parliament Hill
        US|United States|NA|U.S.,USA,United States of America,American,Americans,Washington D.C.,White House,Pentagon,Capitol Hill,Congress
        MX|Mexico|NA|Mexican,Mexicans,Mexico City
        GT|Guatemala|NA|Guatemalan
        BZ|Belize|NA|Belizean
        HN|Honduras|NA|Honduran
        SV|El Salvador|NA|Salvadoran
        NI|Nicaragua|NA|Nicaraguan
        CR|Costa Rica|NA|Costa Rican
        PA|Panama|NA|Panamanian,Panama Canal
        CU|Cuba|NA|Cuban,Havana
        HT|Haiti|NA|Haitian,Port-au-Prince
        DO|Dominican Republic|NA|Dominican Republic,Santo Domingo
        JM|Jamaica|NA|Jamaican,Kingston
        TT|Trinidad and Tobago|NA|Trinidad,Tobago
        BS|Bahamas|NA|Bahamian
        BB|Barbados|NA|Barbadian
        PR|Puerto Rico|NA|Puerto Rican,San Juan
        GL|Greenland|NA|Greenlandic,Nuuk
        BR|Brazil|SA|Brazilian,Brazilians,Brasilia,Brasília,Rio de Janeiro,São Paulo,Sao Paulo
        AR|Argentina|SA|Argentine,Argentinian,Buenos Aires
        CL|Chile|SA|Chilean,Santiago
        CO|Colombia|SA|Colombian,Bogotá,Bogota
        PE|Peru|SA|Peruvian,Lima
        VE|Venezuela|SA|Venezuelan,Caracas
        EC|Ecuador|SA|Ecuadorian,Quito
        BO|Bolivia|SA|Bolivian,La Paz
        PY|Paraguay|SA|Paraguayan,Asunción
        UY|Uruguay|SA|Uruguayan,Montevideo
        GY|Guyana|SA|Guyanese
        SR|Suriname|SA|Surinamese
        GB|United Kingdom|EU|UK,U.K.,Britain,British,Great Britain,England,English,Scotland,Scottish,Wales,Welsh,Northern Ireland,London,Downing Street,Westminster,Manchester,Birmingham,Glasgow,Edinburgh,Cardiff,Belfast,Liverpool
        IE|Ireland|EU|Irish,Dublin,Republic of Ireland
        FR|France|EU|French,Paris,Élysée,Elysee,Marseille,Lyon
        DE|Germany|EU|German,Germans,Berlin,Munich,Hamburg,Frankfurt,Bundestag
        IT|Italy|EU|Italian,Italians,Rome,Milan,Naples,Vatican
        ES|Spain|EU|Spanish,Madrid,Barcelona,Catalonia,Catalan
        PT|Portugal|EU|Portuguese,Lisbon
        NL|Netherlands|EU|Dutch,Amsterdam,The Hague,Rotterdam,Holland
        BE|Belgium|EU|Belgian,Antwerp
        LU|Luxembourg|EU|Luxembourgish
        CH|Switzerland|EU|Swiss,Geneva,Zurich,Bern
        AT|Austria|EU|Austrian,Vienna
        DK|Denmark|EU|Danish,Danes,Copenhagen
        NO|Norway|EU|Norwegian,Oslo
        SE|Sweden|EU|Swedish,Swedes,Stockholm
        FI|Finland|EU|Finnish,Finns,Helsinki
        IS|Iceland|EU|Icelandic,Reykjavik
        PL|Poland|EU|Polish,Warsaw
        CZ|Czech Republic|EU|Czech,Czechia,Prague
        SK|Slovakia|EU|Slovak,Bratislava
        HU|Hungary|EU|Hungarian,Budapest
        RO|Romania|EU|Romanian,Bucharest
        BG|Bulgaria|EU|Bulgarian,Sofia
        GR|Greece|EU|Greek,Athens
        CY|Cyprus|EU|Cypriot,Nicosia
        MT|Malta|EU|Maltese,Valletta
        SI|Slovenia|EU|Slovenian,Ljubljana
        HR|Croatia|EU|Croatian,Zagreb
        RS|Serbia|EU|Serbian,Serbs,Belgrade
        BA|Bosnia and Herzegovina|EU|Bosnia,Bosnian,Sarajevo
        ME|Montenegro|EU|Montenegrin
        MK|North Macedonia|EU|Macedonian,Skopje
        AL|Albania|EU|Albanian,Tirana
        XK|Kosovo|EU|Kosovar,Pristina
        EE|Estonia|EU|Estonian,Tallinn
        LV|Latvia|EU|Latvian,Riga
        LT|Lithuania|EU|Lithuanian,Vilnius
        BY|Belarus|EU|Belarusian,Minsk
        UA|Ukraine|EU|Ukrainian,Ukrainians,Kyiv,Kiev,Kharkiv,Odesa,Odessa,Donbas,Zaporizhzhia,Lviv,Kherson,Crimea
        MD|Moldova|EU|Moldovan,Chisinau
        RU|Russia|EU|Russian,Russians,Moscow,Kremlin,St. Petersburg,Siberia
        TR|Turkey|ME|Turkish,Türkiye,Turkiye,Ankara,Istanbul,Erdogan
        IL|Israel|ME|Israeli,Israelis,Jerusalem,Tel Aviv,IDF,Knesset
        PS|Palestinian Territories|ME|Palestinian,Palestinians,Gaza,West Bank,Ramallah,Hamas
        LB|Lebanon|ME|Lebanese,Beirut,Hezbollah
        SY|Syria|ME|Syrian,Syrians,Damascus,Aleppo
        JO|Jordan|ME|Jordanian,Amman
        IQ|Iraq|ME|Iraqi,Iraqis,Baghdad,Mosul,Kurdistan
        IR|Iran|ME|Iranian,Iranians,Tehran
        SA|Saudi Arabia|ME|Saudi,Saudis,Riyadh,Jeddah
        YE|Yemen|ME|Yemeni,Sanaa,Houthi,Houthis
        OM|Oman|ME|Omani,Muscat
        AE|United Arab Emirates|ME|UAE,Emirati,Dubai,Abu Dhabi
        QA|Qatar|ME|Qatari,Doha
        BH|Bahrain|ME|Bahraini,Manama
        KW|Kuwait|ME|Kuwaiti
        EG|Egypt|ME|Egyptian,Egyptians,Cairo,Alexandria,Suez
        CN|China|AS|Chinese,Beijing,Shanghai,Shenzhen,Guangzhou,Wuhan,Xinjiang,Tibet,Hong Kong
        TW|Taiwan|AS|Taiwanese,Taipei
        JP|Japan|AS|Japanese,Tokyo,Osaka,Kyoto
        KR|South Korea|AS|South Korean,Seoul,Busan
        KP|North Korea|AS|North Korean,Pyongyang
        MN|Mongolia|AS|Mongolian,Ulaanbaatar
        IN|India|AS|Indian,Indians,New Delhi,Delhi,Mumbai,Bengaluru,Bangalore,Kolkata,Chennai,Hyderabad,Kashmir
        PK|Pakistan|AS|Pakistani,Islamabad,Karachi,Lahore
        BD|Bangladesh|AS|Bangladeshi,Dhaka
        LK|Sri Lanka|AS|Sri Lankan,Colombo
        NP|Nepal|AS|Nepali,Nepalese,Kathmandu
        BT|Bhutan|AS|Bhutanese
        MV|Maldives|AS|Maldivian
        AF|Afghanistan|AS|Afghan,Afghans,Kabul,Taliban
        KZ|Kazakhstan|AS|Kazakh,Astana,Almaty
        UZ|Uzbekistan|AS|Uzbek,Tashkent
        TM|Turkmenistan|AS|Turkmen
        KG|Kyrgyzstan|AS|Kyrgyz,Bishkek
        TJ|Tajikistan|AS|Tajik,Dushanbe
        AM|Armenia|AS|Armenian,Yerevan
        AZ|Azerbaijan|AS|Azerbaijani,Baku
        GE|Georgia|AS|?Georgian,Tbilisi
        TH|Thailand|AS|Thai,Bangkok
        VN|Vietnam|AS|Vietnamese,Hanoi,Ho Chi Minh City
        KH|Cambodia|AS|Cambodian,Phnom Penh
        LA|Laos|AS|Laotian,Vientiane
        MM|Myanmar|AS|Burma,Burmese,Yangon,Naypyidaw
        MY|Malaysia|AS|Malaysian,Kuala Lumpur
        SG|Singapore|AS|Singaporean
        ID|Indonesia|AS|Indonesian,Jakarta,Bali
        PH|Philippines|AS|Filipino,Filipinos,Philippine,Manila
        BN|Brunei|AS|Bruneian
        TL|Timor-Leste|AS|East Timor
        AU|Australia|OC|Australian,Australians,Canberra,Sydney,Melbourne,Brisbane,Perth,Adelaide
        NZ|New Zealand|OC|New Zealander,Kiwi,Kiwis,Wellington,Auckland,Christchurch
        PG|Papua New Guinea|OC|Papua New Guinean,Port Moresby
        FJ|Fiji|OC|Fijian,Suva
        SB|Solomon Islands|OC|
        VU|Vanuatu|OC|
        WS|Samoa|OC|Samoan
        TO|Tonga|OC|Tongan
        KI|Kiribati|OC|
        NG|Nigeria|AF|Nigerian,Nigerians,Abuja,Lagos
        ZA|South Africa|AF|South African,South Africans,Johannesburg,Cape Town,Pretoria,Durban
        KE|Kenya|AF|Kenyan,Kenyans,Nairobi,Mombasa
        ET|Ethiopia|AF|Ethiopian,Addis Ababa,Tigray
        GH|Ghana|AF|Ghanaian,Accra
        MA|Morocco|AF|Moroccan,Rabat,Casablanca
        DZ|Algeria|AF|Algerian,Algiers
        TN|Tunisia|AF|Tunisian,Tunis
        LY|Libya|AF|Libyan,Tripoli,Benghazi
        SD|Sudan|AF|Sudanese,Khartoum,Darfur
        SS|South Sudan|AF|South Sudanese,Juba
        SO|Somalia|AF|Somali,Mogadishu,Al-Shabaab
        UG|Uganda|AF|Ugandan,Kampala
        TZ|Tanzania|AF|Tanzanian,Dar es Salaam,Dodoma,Zanzibar
        RW|Rwanda|AF|Rwandan,Kigali
        BI|Burundi|AF|Burundian
        CD|Democratic Republic of the Congo|AF|DRC,DR Congo,Congolese,Kinshasa,Goma
        CG|Republic of the Congo|AF|Brazzaville
        CM|Cameroon|AF|Cameroonian,Yaoundé
        CF|Central African Republic|AF|
        TD|Chad|AF|?Chadian,N'Djamena
        NE|Niger|AF|Nigerien,Niamey
        ML|Mali|AF|Malian,Bamako
        BF|Burkina Faso|AF|Burkinabe,Ouagadougou
        SN|Senegal|AF|Senegalese,Dakar
        GM|Gambia|AF|Gambian,Banjul
        GN|Guinea|AF|Guinean,Conakry
        GW|Guinea-Bissau|AF|
        SL|Sierra Leone|AF|Freetown
        LR|Liberia|AF|Liberian,Monrovia
        CI|Côte d'Ivoire|AF|Ivory Coast,Ivorian,Abidjan,Cote d'Ivoire
        TG|Togo|AF|Togolese,Lomé
        BJ|Benin|AF|Beninese,Cotonou
        MR|Mauritania|AF|Mauritanian,Nouakchott
        ER|Eritrea|AF|Eritrean,Asmara
        DJ|Djibouti|AF|Djiboutian
        GA|Gabon|AF|Gabonese,Libreville
        GQ|Equatorial Guinea|AF|
        AO|Angola|AF|Angolan,Luanda
        ZM|Zambia|AF|Zambian,Lusaka
        ZW|Zimbabwe|AF|Zimbabwean,Harare
        MW|Malawi|AF|Malawian,Lilongwe
        MZ|Mozambique|AF|Mozambican,Maputo
        MG|Madagascar|AF|Malagasy,Antananarivo
        BW|Botswana|AF|Botswanan,Gaborone
        NA|Namibia|AF|Namibian,Windhoek
        LS|Lesotho|AF|
        SZ|Eswatini|AF|Swaziland
        MU|Mauritius|AF|Mauritian
        CV|Cape Verde|AF|Cabo Verde
        SC|Seychelles|AF|
        AQ|Antarctica|AN|Antarctic,South Pole
    """.trimIndent()

    val regions = """
        CA-AB|Alberta|CA|Albertan,Albertans
        CA-BC|British Columbia|CA|B.C.,British Columbian
        CA-MB|Manitoba|CA|Manitoban,Manitobans
        CA-NB|New Brunswick|CA|N.B.
        CA-NL|Newfoundland and Labrador|CA|Newfoundland,Labrador,N.L.
        CA-NS|Nova Scotia|CA|N.S.,Nova Scotian
        CA-NT|Northwest Territories|CA|N.W.T.,NWT
        CA-NU|Nunavut|CA|
        CA-ON|Ontario|CA|Ont.,Ontarian,Ontarians,Queen's Park
        CA-PE|Prince Edward Island|CA|P.E.I.,PEI
        CA-QC|Quebec|CA|Québec,Que.,Quebecer,Quebecers,Québécois,National Assembly
        CA-SK|Saskatchewan|CA|Sask.,Saskatchewanian
        CA-YT|Yukon|CA|
        US-AL|Alabama|US|Ala.
        US-AK|Alaska|US|Alaskan
        US-AZ|Arizona|US|Ariz.
        US-AR|Arkansas|US|Ark.
        US-CA|California|US|Calif.,Californian,Californians
        US-CO|Colorado|US|Colo.
        US-CT|Connecticut|US|Conn.
        US-DE|Delaware|US|Del.
        US-DC|District of Columbia|US|Washington D.C.,Washington DC,D.C.
        US-FL|Florida|US|Fla.,Floridian
        US-GA|Georgia|US|Ga.,?Georgia
        US-HI|Hawaii|US|Hawaiian
        US-ID|Idaho|US|
        US-IL|Illinois|US|Ill.
        US-IN|Indiana|US|Ind.
        US-IA|Iowa|US|Iowan
        US-KS|Kansas|US|Kan.
        US-KY|Kentucky|US|Ky.
        US-LA|Louisiana|US|La.
        US-ME|Maine|US|
        US-MD|Maryland|US|Md.
        US-MA|Massachusetts|US|Mass.
        US-MI|Michigan|US|Mich.
        US-MN|Minnesota|US|Minn.
        US-MS|Mississippi|US|Miss.
        US-MO|Missouri|US|Mo.
        US-MT|Montana|US|Mont.
        US-NE|Nebraska|US|Neb.
        US-NV|Nevada|US|Nev.
        US-NH|New Hampshire|US|N.H.
        US-NJ|New Jersey|US|N.J.
        US-NM|New Mexico|US|N.M.
        US-NY|New York|US|N.Y.,New York State
        US-NC|North Carolina|US|N.C.
        US-ND|North Dakota|US|N.D.
        US-OH|Ohio|US|
        US-OK|Oklahoma|US|Okla.
        US-OR|Oregon|US|Ore.
        US-PA|Pennsylvania|US|Pa.
        US-RI|Rhode Island|US|R.I.
        US-SC|South Carolina|US|S.C.
        US-SD|South Dakota|US|S.D.
        US-TN|Tennessee|US|Tenn.
        US-TX|Texas|US|Texan,Texans
        US-UT|Utah|US|
        US-VT|Vermont|US|Vt.
        US-VA|Virginia|US|Va.
        US-WA|Washington State|US|?Washington,Wash.
        US-WV|West Virginia|US|W.Va.
        US-WI|Wisconsin|US|Wis.
        US-WY|Wyoming|US|Wyo.
        AU-NSW|New South Wales|AU|NSW
        AU-VIC|Victoria (Australia)|AU|?Victoria
        AU-QLD|Queensland|AU|
        AU-WA|Western Australia|AU|
        AU-SA|South Australia|AU|
        AU-TAS|Tasmania|AU|Tasmanian
        GB-SCT|Scotland|GB|Scottish,Holyrood
        GB-WLS|Wales|GB|Welsh,Senedd
        GB-NIR|Northern Ireland|GB|Stormont
        GB-ENG|England|GB|English
    """.trimIndent()

    val cities = """
        CA-SK:regina|Regina|CA-SK|?Regina
        CA-SK:saskatoon|Saskatoon|CA-SK|
        CA-SK:prince-albert|Prince Albert|CA-SK|
        CA-SK:moose-jaw|Moose Jaw|CA-SK|
        CA-SK:swift-current|Swift Current|CA-SK|
        CA-SK:yorkton|Yorkton|CA-SK|
        CA-SK:north-battleford|North Battleford|CA-SK|The Battlefords,Battlefords
        CA-SK:estevan|Estevan|CA-SK|
        CA-SK:weyburn|Weyburn|CA-SK|
        CA-SK:lloydminster|Lloydminster|CA-SK|
        CA-SK:melfort|Melfort|CA-SK|
        CA-SK:humboldt|Humboldt|CA-SK|?Humboldt
        CA-SK:meadow-lake|Meadow Lake|CA-SK|
        CA-SK:kindersley|Kindersley|CA-SK|
        CA-SK:la-ronge|La Ronge|CA-SK|
        CA-SK:martensville|Martensville|CA-SK|
        CA-SK:warman|Warman|CA-SK|
        CA-SK:melville|Melville|CA-SK|?Melville
        CA-SK:nipawin|Nipawin|CA-SK|
        CA-SK:tisdale|Tisdale|CA-SK|
        CA-SK:rosetown|Rosetown|CA-SK|
        CA-SK:outlook|Outlook|CA-SK|?Outlook
        CA-SK:la-loche|La Loche|CA-SK|
        CA-SK:creighton|Creighton|CA-SK|
        CA-SK:maple-creek|Maple Creek|CA-SK|
        CA-SK:fort-qu-appelle|Fort Qu'Appelle|CA-SK|
        CA-SK:white-city|White City|CA-SK|
        CA-SK:carlyle|Carlyle|CA-SK|
        CA-SK:shaunavon|Shaunavon|CA-SK|
        CA-SK:unity|Unity|CA-SK|?Unity
        CA-AB:calgary|Calgary|CA-AB|
        CA-AB:edmonton|Edmonton|CA-AB|
        CA-AB:red-deer|Red Deer|CA-AB|
        CA-AB:lethbridge|Lethbridge|CA-AB|
        CA-AB:medicine-hat|Medicine Hat|CA-AB|
        CA-AB:grande-prairie|Grande Prairie|CA-AB|
        CA-AB:fort-mcmurray|Fort McMurray|CA-AB|Wood Buffalo
        CA-AB:banff|Banff|CA-AB|
        CA-AB:jasper|Jasper|CA-AB|?Jasper
        CA-MB:winnipeg|Winnipeg|CA-MB|
        CA-MB:brandon|Brandon|CA-MB|?Brandon
        CA-MB:thompson|Thompson|CA-MB|?Thompson
        CA-MB:steinbach|Steinbach|CA-MB|
        CA-MB:churchill|Churchill|CA-MB|?Churchill
        CA-BC:vancouver|Vancouver|CA-BC|
        CA-BC:victoria|Victoria (B.C.)|CA-BC|?Victoria
        CA-BC:surrey|Surrey|CA-BC|?Surrey
        CA-BC:burnaby|Burnaby|CA-BC|
        CA-BC:kelowna|Kelowna|CA-BC|
        CA-BC:kamloops|Kamloops|CA-BC|
        CA-BC:nanaimo|Nanaimo|CA-BC|
        CA-BC:prince-george|Prince George|CA-BC|?Prince George
        CA-BC:richmond|Richmond (B.C.)|CA-BC|?Richmond
        CA-BC:abbotsford|Abbotsford|CA-BC|
        CA-BC:whistler|Whistler|CA-BC|
        CA-ON:toronto|Toronto|CA-ON|GTA,Greater Toronto Area
        CA-ON:ottawa|Ottawa|CA-ON|?Ottawa
        CA-ON:mississauga|Mississauga|CA-ON|
        CA-ON:brampton|Brampton|CA-ON|
        CA-ON:hamilton|Hamilton (Ont.)|CA-ON|?Hamilton
        CA-ON:london|London (Ont.)|CA-ON|London, Ont.,London, Ontario
        CA-ON:windsor|Windsor|CA-ON|?Windsor
        CA-ON:kitchener|Kitchener|CA-ON|Kitchener-Waterloo
        CA-ON:waterloo|Waterloo|CA-ON|?Waterloo
        CA-ON:sudbury|Sudbury|CA-ON|Greater Sudbury
        CA-ON:thunder-bay|Thunder Bay|CA-ON|
        CA-ON:kingston|Kingston (Ont.)|CA-ON|?Kingston
        CA-ON:barrie|Barrie|CA-ON|
        CA-ON:guelph|Guelph|CA-ON|
        CA-ON:oshawa|Oshawa|CA-ON|
        CA-ON:niagara-falls|Niagara Falls|CA-ON|
        CA-QC:montreal|Montreal|CA-QC|Montréal
        CA-QC:quebec-city|Quebec City|CA-QC|Québec City,Ville de Québec
        CA-QC:gatineau|Gatineau|CA-QC|
        CA-QC:laval|Laval|CA-QC|?Laval
        CA-QC:sherbrooke|Sherbrooke|CA-QC|
        CA-QC:trois-rivieres|Trois-Rivières|CA-QC|Trois-Rivieres
        CA-QC:saguenay|Saguenay|CA-QC|
        CA-NS:halifax|Halifax|CA-NS|?Halifax
        CA-NS:sydney|Sydney (N.S.)|CA-NS|Cape Breton
        CA-NB:moncton|Moncton|CA-NB|
        CA-NB:saint-john|Saint John|CA-NB|
        CA-NB:fredericton|Fredericton|CA-NB|
        CA-NL:st-johns|St. John's|CA-NL|St John's
        CA-PE:charlottetown|Charlottetown|CA-PE|
        CA-YT:whitehorse|Whitehorse|CA-YT|
        CA-NT:yellowknife|Yellowknife|CA-NT|
        CA-NU:iqaluit|Iqaluit|CA-NU|
        US-NY:nyc|New York City|US-NY|NYC,Manhattan,Brooklyn,Queens,Bronx,Staten Island,Wall Street
        US-CA:los-angeles|Los Angeles|US-CA|L.A.,LA
        US-CA:san-francisco|San Francisco|US-CA|Bay Area,Silicon Valley
        US-CA:san-diego|San Diego|US-CA|
        US-CA:sacramento|Sacramento|US-CA|
        US-CA:san-jose|San Jose|US-CA|
        US-IL:chicago|Chicago|US-IL|
        US-TX:houston|Houston|US-TX|
        US-TX:dallas|Dallas|US-TX|
        US-TX:austin|Austin|US-TX|?Austin
        US-TX:san-antonio|San Antonio|US-TX|
        US-AZ:phoenix|Phoenix|US-AZ|?Phoenix
        US-PA:philadelphia|Philadelphia|US-PA|Philly
        US-PA:pittsburgh|Pittsburgh|US-PA|
        US-FL:miami|Miami|US-FL|
        US-FL:orlando|Orlando|US-FL|
        US-FL:tampa|Tampa|US-FL|
        US-GA:atlanta|Atlanta|US-GA|
        US-MA:boston|Boston|US-MA|
        US-WA:seattle|Seattle|US-WA|
        US-CO:denver|Denver|US-CO|
        US-MI:detroit|Detroit|US-MI|
        US-MN:minneapolis|Minneapolis|US-MN|St. Paul,Twin Cities
        US-NV:las-vegas|Las Vegas|US-NV|
        US-OR:portland|Portland (Ore.)|US-OR|?Portland
        US-LA:new-orleans|New Orleans|US-LA|
        US-TN:nashville|Nashville|US-TN|
        US-MD:baltimore|Baltimore|US-MD|
        US-MO:st-louis|St. Louis|US-MO|
        US-OH:cleveland|Cleveland|US-OH|?Cleveland
        US-ND:fargo|Fargo|US-ND|
        US-ND:bismarck|Bismarck|US-ND|
        US-MT:billings|Billings|US-MT|?Billings
        US-HI:honolulu|Honolulu|US-HI|
        US-AK:anchorage|Anchorage|US-AK|
    """.trimIndent()

    /** Terms indicating space / astronomy coverage. Matched case-insensitively. */
    val spaceTerms = listOf(
        "nasa", "esa", "spacex", "blue origin", "roscosmos", "isro", "jaxa", "cnsa", "canadian space agency",
        "astronaut", "astronauts", "cosmonaut", "spacecraft", "space station", "iss", "artemis", "orion capsule",
        "starship", "falcon 9", "rocket launch", "launch pad", "satellite launch", "into orbit", "low earth orbit", "orbital",
        "mars", "martian", "moon landing", "lunar", "jupiter", "saturn", "venus", "mercury probe", "pluto", "asteroid",
        "comet", "meteor", "meteorite", "exoplanet", "galaxy", "galaxies", "black hole", "nebula", "supernova",
        "telescope", "james webb", "jwst", "hubble", "astronomy", "astronomer", "astronomers", "astrophysics",
        "cosmology", "solar system", "solar flare", "aurora", "northern lights", "eclipse", "spacewalk",
        "mars rover", "lunar rover", "perseverance rover", "lunar lander", "space agency", "spaceflight", "light-years", "light years",
    )
}
