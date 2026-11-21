package com.yijian.translate

const val SAMPLE_CHINESE = "这份工作很适合你。"

fun chineseLearning() = LearningInfo(sourceLanguage = "zh-CN", direction = "zh_to_foreign",
    usage = "本句谈人与职位的匹配，a good fit 比 appropriate 更自然。中文的“很”在这里不必逐字译为 very；英语用 a good fit 已能表达积极的匹配评价。",
    vocabulary = listOf(
        Vocabulary("适合", "本句强调能力、性格与岗位的匹配。根据场景，可以选择以下不同表达。", listOf(
            WordChoice("a good fit", meaning = "匹配、合拍的人或事物", usage = "常用 a good fit for，适合描述人与岗位、团队或产品需求的匹配。这里选它最自然。", example = "This role is a good fit for you.", exampleTranslation = "这个职位很适合你。"),
            WordChoice("suitable", meaning = "符合条件或用途的，合适的", usage = "形容词，常搭配 suitable for。侧重客观条件、用途或人群是否合适。", example = "This course is suitable for beginners.", exampleTranslation = "这门课程适合初学者。"),
            WordChoice("appropriate", meaning = "得体的，符合场合或规范的", usage = "侧重场合、行为和语气是否妥当，不是本句“人岗匹配”的首选。", example = "Choose appropriate clothes for the interview.", exampleTranslation = "为面试选择得体的衣着。")
        )),
        Vocabulary("工作", "具体职位、工作活动与职责角色，在英语中通常分别表达。", listOf(
            WordChoice("job", meaning = "一份具体工作或职位，可数名词", usage = "this job / a new job。强调一个具体职位，本句可选 job。", example = "She has a new job.", exampleTranslation = "她有了一份新工作。"),
            WordChoice("work", meaning = "工作活动、任务或劳动，通常不可数", usage = "go to work / a lot of work。一般不用 a work 表示“一份工作”。", example = "I have a lot of work to do.", exampleTranslation = "我有很多工作要做。"),
            WordChoice("role", meaning = "职位中的职责或角色，可数名词", usage = "a role in a team / this role。招聘和团队沟通常用，侧重职责。", example = "What does this role involve?", exampleTranslation = "这个职位包含哪些职责？")
        ))
    ))

fun foreignLearning() = LearningInfo(sourceLanguage = "en", direction = "foreign_to_zh",
    pronunciation = "英式参考读音：\nCould you give me a moment?\n/kʊd juː ɡɪv miː ə ˈməʊmənt/\nI want to get this right.\n/aɪ wɒnt tə ɡet ðɪs raɪt/\n\n自然语流中 could you、want to 可能发生弱读与连读；moment 的重音在第一音节。",
    usage = "Could you + 动词原形构成礼貌请求。give someone a moment 表示给某人一点时间。get + 宾语 + right 表示把某件事处理正确、做好，不宜逐词理解为“得到这个正确”。",
    correctionSummary = "原文没有明显的拼写、语法或搭配错误。用 a moment 提出短暂等待的请求自然得体；第二句补充原因，语气认真。",
    vocabulary = listOf(
        Vocabulary("a moment", "这里是请求短暂等待，不指一个特定的历史时刻。", listOf(
            WordChoice("moment", "英 /ˈməʊ.mənt/；美 /ˈmoʊ.mənt/", "片刻；某一时刻", "give me a moment / just a moment。比 give me a second 稍中性，日常和工作场景均可。", "Just a moment, please.", "请稍等片刻。"),
            WordChoice("a second", meaning = "一会儿，口语中的短暂时间", usage = "更口语，通常不表示精确的一秒钟。", example = "Give me a second.", exampleTranslation = "给我一点时间。")
        )),
        Vocabulary("get this right", "强调认真完成这件事，this 指当前任务。", listOf(
            WordChoice("get something right", meaning = "把某件事做对、做好", usage = "right 是宾语补足语，描述做事的结果；可与 get it wrong 对照。", example = "Let’s make sure we get the details right.", exampleTranslation = "我们确保把细节处理正确。")
        ))
    ))
