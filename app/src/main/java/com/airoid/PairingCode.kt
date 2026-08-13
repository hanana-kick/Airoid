package com.airoid

import java.util.Locale

/**
 * 페어링용 코드(유명 문학 작품 제목).
 * 앱 언어(시스템 언어 + per-app 언어 오버라이드가 반영된 리소스 로케일)에 맞는
 * 목록(한국어/영어/일본어/중국어)에서 뽑으며, 각 목록은 해당 언어의 원어 표기다 —
 * 언어를 바꾸면 코드도 그 언어로 나온다. Locale.getDefault()는 per-app 오버라이드를
 * 반영하지 않으므로(시스템 로케일을 반환) 호출자가 리소스 로케일을 넘겨야 한다.
 * 코드 생성은 랜덤이며, 영속화(앱 재시작 시 유지)와 언어 변경 시 갱신은 호출자(서비스)가
 * 담당한다. AirPlay 기기 이름 "Airoid [코드]"와 대기 화면 표시에 함께 쓰인다.
 * 기기 식별(영속 MAC/페어링 인증서)은 별개로 유지되므로 이름이 바뀌어도 동일 기기로 인식된다.
 */
object PairingCode {

    /** 한국어 — 한국 근현대/고전 문학 */
    private val KOREAN = listOf(
        "태백산맥", "아리랑", "소나기", "카인의후예", "상록수", "혈의누",
        "천변풍경", "운수좋은날", "무진기행", "삼포가는길", "장길산",
        "칼의노래", "남한산성", "하얼빈", "채식주의자", "소년이온다", "작별인사",
        "아몬드", "완득이", "구운몽", "허생전", "홍길동전", "춘향전", "한중록",
        "바람의화원", "해를품은달", "토지", "무정", "광장", "장마", "오발탄",
        "수난이대", "사하촌", "모래톱이야기", "별들의고향", "열하일기",
        "박씨전", "금오신화", "이생규장전", "운영전", "심청전", "흥부전",
        "토끼전", "별주부전", "콩쥐팥쥐", "장화홍련", "빈처",
    )

    /** 영어 — 영미 문학 (영어 표기, 코드박스에 들어가도록 짧은 형태) */
    private val ENGLISH = listOf(
        "Moby Dick", "Old Man and Sea", "Pride and Prejudice", "Great Expectations",
        "Wuthering Heights", "Jane Eyre", "Sherlock Holmes", "Dracula", "Time Machine",
        "Animal Farm", "Lord of the Flies", "Treasure Island", "Gatsby", "Tom Sawyer",
        "Peter Pan", "Alice", "Ivanhoe", "Lord of the Rings", "Hobbit", "Harry Potter",
        "Emma", "Tess", "Scarlet Letter", "Brave New World", "Dorian Gray",
        "Frankenstein", "Gulliver's Travels", "Robinson Crusoe", "Oliver Twist",
        "Wizard of Oz", "Anne Frank", "Hamlet", "Da Vinci Code", "Angels and Demons",
        "Jungle Book", "Narnia", "War of the Worlds", "Invisible Man", "Little Women",
        "Secret Garden", "Little Princess", "Lost World", "Wonder",
    )

    /** 일본어 — 일본 문학 (일본어 표기) */
    private val JAPANESE = listOf(
        "人間失格", "金閣寺", "仮面の告白", "豊饒の海", "羅生門", "藪の中",
        "地獄変", "痴人の愛", "山の音", "或る女", "ノルウェイの森", "海辺のカフカ",
        "個人的な体験", "坊っちゃん", "白夜行", "雪国", "こころ", "斜陽", "沈黙",
        "火車", "秘密", "キッチン", "東京タワー", "千羽鶴", "闘牛", "細雪", "鍵",
        "伊豆の踊子", "舞姫", "高瀬舟", "海と毒薬", "銀河鉄道の夜",
    )

    /** 중국어 — 중국 문학 (중국어 간체 표기) */
    private val CHINESE = listOf(
        "三国演义", "水浒传", "西游记", "红楼梦", "金瓶梅", "儒林外史", "阿Q正传",
        "狂人日记", "骆驼祥子", "射雕英雄传", "神雕侠侣", "倚天屠龙记", "鹿鼎记",
        "笑傲江湖", "天龙八部", "警世通言", "东周列国志", "碧血剑", "飞狐外传",
        "雪山飞狐", "连城诀", "书剑恩仇录", "白发魔女传", "故乡", "药", "祝福",
        "孔乙己", "楚汉春秋", "流星蝴蝶剑", "天蚕变", "大唐双龙传",
    )

    /** 기기 언어(앱 언어 오버라이드 포함)에 맞는 코드 목록. 기본은 한국어. */
    private fun listFor(locale: Locale): List<String> = when (locale.language) {
        "en" -> ENGLISH
        "ja" -> JAPANESE
        "zh" -> CHINESE
        else -> KOREAN
    }

    /** 연결 대기마다 새 코드를 뽑는다. appLocale은 per-app 오버라이드가 반영된 리소스 로케일. */
    fun random(appLocale: Locale): String = listFor(appLocale).random()

    /** AirPlay에 노출되는 기기 이름: "Airoid [코드]". */
    fun deviceName(code: String): String = "Airoid $code"
}
