package com.crnogorski.trener.data

/**
 * Картинка к словарной карточке — там, где она осмысленна.
 *
 * Не иллюстрации в файлах, а эмодзи, и это выбор, а не экономия. Рисунки
 * пришлось бы искать, обрезать, проверять глазами и тащить в APK мегабайтами
 * вместе с блоком лицензий; эмодзи рисует системный шрифт, весит ноль, всегда
 * одного стиля и не может «не найтись». Плата — грубость: «понос» и «болото»
 * картинки не получат никогда.
 *
 * **Слово без картинки — это норма, а не пробел.** Из 1152 слов покрыто 342,
 * то есть каждое третье: те, у которых у эмодзи есть ровно то же значение.
 * Натягивать сову было бы хуже, чем оставить пусто: неверная картинка
 * запоминается вместе со словом, и потом её оттуда не выковырять. Поэтому
 * абстрактному («ответственность», «обстоятельство») не даётся ничего, а
 * спорное («пора», «случай») выброшено.
 *
 * Повторы допустимы: `mačka` и `mačak` — кошка и кот, `naočare` и `naočale` —
 * одни и те же очки. Слово с картинкой соседа не путается, потому что рядом
 * всегда стоит текст.
 *
 * **Обратному переводу картинка не даётся вовсе** (см. `exerciseFor`): там
 * спрашивают, что значит черногорское слово, и картинка была бы ответом.
 *
 * Все ключи — леммы из `assets/vocab/words.json`; чужих быть не должно,
 * проверяется скриптом при правке.
 */
object WordEmoji {

    /** Картинка к слову или `null`, если её нет. */
    fun of(word: String): String? = MAP[word]

    /** Сколько слов покрыто — для отчётов и проверок. */
    val size: Int get() = MAP.size

    private val MAP: Map<String, String> = mapOf(
        // --------------------------------------------------------- люди
        "muškarac" to "👨", "dečak" to "👦", "beba" to "👶", "novorođenče" to "👶",
        "baka" to "👵", "baba" to "👵", "ćerka" to "👧", "kći" to "👧",
        "obitelj" to "👨‍👩‍👧", "prijatelj" to "🧑‍🤝‍🧑", "mladoženja" to "🤵",
        "kralj" to "👑", "kraljica" to "👸", "vitez" to "🛡️", "ratnik" to "⚔️",
        "policajac" to "👮", "vojnik" to "🪖", "vatrogasac" to "🧑‍🚒",
        "radnik" to "👷", "kuvar" to "🧑‍🍳", "poštar" to "📮", "dadilja" to "🧑‍🍼",
        "bolničar" to "🧑‍⚕️", "bolničarka" to "👩‍⚕️", "naučnik" to "🔬",
        "znanstvenik" to "🔬", "hemičar" to "⚗️", "računovođa" to "🧮",
        "slikar" to "🖌️", "pesnik" to "✍️", "pevac" to "🎤", "plesač" to "💃",
        "igračica" to "💃", "sportista" to "🏅", "trkač" to "🏃",
        "biciklist" to "🚴", "jahač" to "🏇", "ronilac" to "🤿",
        "mačevalac" to "🤺", "mađioničar" to "🎩", "veštica" to "🧙‍♀️",
        "čarobnjak" to "🧙‍♂️", "domaćica" to "🧹", "krojač" to "🧵",
        "mesar" to "🥩", "vrtlar" to "🌻", "đak" to "🎒", "kriminalac" to "🚓",
        "zatvorenik" to "⛓️", "avet" to "👻", "englez" to "🇬🇧", "japanac" to "🇯🇵",

        // ------------------------------------------------------ животные
        "pas" to "🐕", "mačka" to "🐈", "mačak" to "🐱", "vuk" to "🐺",
        "lisica" to "🦊", "zec" to "🐇", "majmun" to "🐒", "magarac" to "🐴",
        "kamila" to "🐫", "jarac" to "🐐", "jagnje" to "🐑", "prase" to "🐖",
        "veverica" to "🐿️", "hrčak" to "🐹", "rakun" to "🦝", "foka" to "🦭",
        "zmija" to "🐍", "gušter" to "🦎", "kornjača" to "🐢", "puž" to "🐌",
        "pacov" to "🐀", "štakor" to "🐀", "šišmiš" to "🦇",
        "orao" to "🦅", "labud" to "🦢", "paun" to "🦚", "patka" to "🦆",
        "vrabac" to "🐦", "pile" to "🐤", "pilić" to "🐤",
        "leptir" to "🦋", "mrav" to "🐜", "insekt" to "🐞", "cvrčak" to "🦗",
        "bubašvaba" to "🪳", "košnica" to "🐝", "hobotnica" to "🐙",
        "lignja" to "🦑", "jastog" to "🦞", "bakalar" to "🐟",
        "mekušac" to "🐚", "dinosaurus" to "🦖",

        // ---------------------------------------------------------- еда
        "hrana" to "🍲", "doručak" to "🥐", "večera" to "🍽️", "piće" to "🥤",
        "šampanjac" to "🍾", "sladoled" to "🍦", "pečenje" to "🍖",
        "šunka" to "🥓", "krompir" to "🥔", "šargarepa" to "🥕",
        "krastavac" to "🥒", "kupus" to "🥬", "zelje" to "🥬",
        "paradajz" to "🍅", "grožđe" to "🍇", "lubenica" to "🍉",
        "breskva" to "🍑", "pomorandža" to "🍊", "borovnica" to "🫐",
        "gljiva" to "🍄", "pirinač" to "🍚", "riža" to "🍚", "brašno" to "🌾",
        "krafna" to "🍩", "pekmez" to "🍯", "začin" to "🧂", "umak" to "🥫",

        // ------------------------------------------------------- природа
        "šuma" to "🌲", "planina" to "⛰️", "obala" to "🏖️", "ada" to "🏝️",
        "val" to "🌊", "vrtlog" to "🌀", "livada" to "🌿", "grm" to "🌳",
        "biljka" to "🌱", "lala" to "🌷", "proleće" to "🌸", "stena" to "🪨",
        "magla" to "🌫️", "mraz" to "❄️", "mećava" to "🌨️", "pljusak" to "🌧️",
        "grmljavina" to "⛈️", "suša" to "🏜️", "pomračenje" to "🌑",
        "svemir" to "🌌", "čovečanstvo" to "🌍",

        // ---------------------------------------------------- дом и вещи
        "zgrada" to "🏢", "zid" to "🧱", "prozor" to "🪟", "kvaka" to "🚪",
        "stepenište" to "🪜", "dizalo" to "🛗", "nameštaj" to "🛋️",
        "stolica" to "🪑", "stolac" to "🪑", "pokrivač" to "🛏️",
        "ogledalo" to "🪞", "fioka" to "🗄️", "svetiljka" to "💡",
        "sapun" to "🧼", "četkica" to "🪥", "rublje" to "🧺", "kanta" to "🪣",
        "smeće" to "🗑️", "kašika" to "🥄", "žlica" to "🥄", "tiganj" to "🍳",
        "ćup" to "🏺", "makaze" to "✂️", "škare" to "✂️", "čekić" to "🔨",
        "alat" to "🛠️", "alatka" to "🔧", "odvijač" to "🪛", "klin" to "🔩",
        "konopac" to "🪢", "udica" to "🎣", "šator" to "⛺", "baterija" to "🔦",
        "baklja" to "🔥", "zamka" to "🪤", "uređaj" to "🔌", "računar" to "💻",
        "štampač" to "🖨️", "televizija" to "📺", "harmonika" to "🪗",
        "violina" to "🎻", "klavir" to "🎹", "bubanj" to "🥁",

        // -------------------------------------------------------- одежда
        "košulja" to "👕", "kaput" to "🧥", "hlače" to "👖", "gaće" to "🩲",
        "čarapa" to "🧦", "obuća" to "👟", "šešir" to "👒", "naočare" to "👓",
        "naočale" to "👓", "torbica" to "👜", "mašna" to "🎀",
        "ogrlica" to "📿", "dragulj" to "💎", "frizura" to "💇",

        // ---------------------------------------------------------- тело
        "mozak" to "🧠", "lobanja" to "💀", "lubanja" to "💀", "kostur" to "🦴",
        "usna" to "👄", "prst" to "☝️", "dlan" to "🤲", "pesnica" to "✊",
        "peta" to "🦶", "nokat" to "💅", "zubić" to "🦷", "vid" to "👁️",
        "znoj" to "💦", "suza" to "😢", "trudnoća" to "🤰",

        // ------------------------------------------------------ движение
        "avion" to "✈️", "brod" to "🚢", "barka" to "🛶", "jedro" to "⛵",
        "kamion" to "🚚", "cesta" to "🛣️", "šina" to "🛤️",
        "raskršće" to "🚦", "parkiralište" to "🅿️", "padobran" to "🪂",
        "balon" to "🎈", "putovanje" to "🧳", "korak" to "👣", "šetnja" to "🚶",
        "jugozapad" to "🧭", "mapa" to "🗺️",

        // ------------------------------------------------ город и занятия
        "toranj" to "🗼", "kula" to "🏰", "tvrđava" to "🏯", "vila" to "🏡",
        "spomenik" to "🗿", "groblje" to "🪦", "grob" to "🪦",
        "samostan" to "⛪", "raspeće" to "✝️", "pozorište" to "🎭",
        "bioskop" to "🎬", "sajam" to "🎪", "igralište" to "🛝",
        "vrtić" to "🧸", "sveučilište" to "🎓", "knjižnica" to "📚",
        "rečnik" to "📖", "prestonica" to "🏙️", "vlada" to "🏛️",
        "elektrana" to "⚡", "izgradnja" to "🏗️", "radionica" to "🧰",
        "poljoprivreda" to "🚜", "vinograd" to "🍇", "trgovina" to "🛒",
        "kupovina" to "🛍️", "blagajna" to "💰", "novčanica" to "💵",
        "kovanica" to "🪙", "narudžba" to "🧾", "porudžbina" to "🧾",
        "tovar" to "📦", "gorivo" to "⛽", "nafta" to "🛢️",

        // -------------------------------------------------------- занятия
        "lov" to "🏹", "puška" to "🔫", "lopta" to "⚽", "košarka" to "🏀",
        "odbojka" to "🏐", "bilijar" to "🎱", "kockanje" to "🎰",
        "lutrija" to "🎟️", "adut" to "🃏", "vežba" to "🏋️",
        "uspavanka" to "🎶", "aplauz" to "👏", "ispit" to "📝",
        "predavanje" to "🧑‍🏫", "glasanje" to "🗳️", "oporuka" to "📜",
        "posetnica" to "🪪", "dijagram" to "📊", "lista" to "📋",
        "olovka" to "✏️", "slovo" to "🔤", "upitnik" to "❓",
        "broj" to "🔢", "nula" to "0️⃣", "jednačina" to "➗",
        "oduzimanje" to "➖", "trougao" to "🔺", "ugao" to "📐",
        "centimetar" to "📏", "vaga" to "⚖️", "okvir" to "🖼️",
        "umetnost" to "🖼️", "boja" to "🎨", "časopis" to "📰",

        // --------------------------------------------------- то, что чувствуют
        "osmeh" to "😊", "sreća" to "🍀", "šala" to "😄", "zagrljaj" to "🤗",
        "poljubac" to "💋", "tuga" to "😔", "bes" to "😡", "sumnja" to "🤔",
        "nada" to "🤞", "zahvalnost" to "🙏", "podrška" to "🙌",
        "poverenje" to "🤝", "pobednik" to "🏆", "godišnjica" to "🎉",
        "rođendan" to "🎂", "dar" to "🎁", "pozdrav" to "👋", "šapat" to "🤫",
        "rasprava" to "💬", "umor" to "😫", "nesanica" to "😴",
        "prehlada" to "🤧", "mučnina" to "🤢", "povraćanje" to "🤮",
        "glavobolja" to "🤕", "povreda" to "🩹", "proliv" to "🚽",
        "žeđ" to "💧", "duvan" to "🚬", "eksplozija" to "💥", "alarm" to "🚨",
        "zastava" to "🚩", "varnica" to "✨", "mehur" to "🫧", "promaja" to "💨",
        "brzina" to "🏎️", "žurba" to "⏱️", "sedmica" to "📅", "rupa" to "🕳️",
        "lanac" to "⛓️", "bezbednost" to "🔒", "vlasnik" to "🔑",
        "pažnja" to "⚠️", "sudar" to "🚗"
    )
}
