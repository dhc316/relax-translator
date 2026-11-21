package com.yijian.translate

const val SAMPLE_JAPANESE = "今日は何を食べますか。"
const val SAMPLE_JAPANESE_ZH = "今天吃什么？"
fun demoSourceReading(source: String) = when (source) {
    SAMPLE_CHINESE -> Reading("zh-CN", "zhè fèn gōngzuò hěn shìhé nǐ。")
    SAMPLE_SOURCE -> Reading("en", "/kʊd juː ɡɪv miː ə ˈməʊmənt/\n/aɪ wɒnt tə ɡet ðɪs raɪt/", notes = "英式参考读音；自然语流中可弱读、连读。")
    SAMPLE_JAPANESE_ZH -> Reading("zh-CN", "jīntiān chī shénme？")
    SAMPLE_JAPANESE -> Reading("ja", kana = "きょうはなにをたべますか。", romaji = "Kyō wa nani o tabemasu ka?")
    "不用着急，慢慢来。" -> Reading("zh-CN", "bú yòng zháojí，mànmàn lái。")
    else -> Reading()
}
fun demoTargetReading(source: String, target: String) = when {
    source == SAMPLE_JAPANESE_ZH && target == "ja" -> demoSourceReading(SAMPLE_JAPANESE)
    source == SAMPLE_JAPANESE && target.startsWith("zh") -> demoSourceReading(SAMPLE_JAPANESE_ZH)
    source == SAMPLE_CHINESE && target == "en" -> Reading("en", "/ðɪs dʒɒb ɪz ə ɡʊd fɪt fə juː/", notes = "英式参考读音。")
    source == SAMPLE_SOURCE && target.startsWith("zh") -> Reading(target, "néng gěi wǒ yì diǎn shíjiān ma？wǒ xiǎng bǎ zhè jiàn shì zuò hǎo。")
    source == SAMPLE_SOURCE && target == "en" -> demoSourceReading(source)
    source == SAMPLE_SOURCE && target == "ja" -> Reading("ja", kana = "すこしじかんをいただけますか。きちんとしあげたいんです。", romaji = "Sukoshi jikan o itadakemasu ka. Kichinto shiagetain desu.")
    else -> Reading()
}
