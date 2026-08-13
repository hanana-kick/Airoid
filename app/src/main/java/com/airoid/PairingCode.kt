package com.airoid

import android.content.Context
import kotlin.random.Random

/**
 * 대기 화면에 표시되는 페어링용 한글 단어 코드(3~5자).
 * 최초 접근 시 한 번 생성되어 SharedPreferences에 저장되며, 이후 재실행해도 같은 코드를 유지한다.
 * AirPlay 기기 이름 "Airoid [코드]"와 대기 화면 표시에 함께 쓰인다.
 */
object PairingCode {

    private val WORDS = listOf(
        // 3자
        "노을빛", "별하늘", "달빛길", "햇살길", "숲속길", "바닷길", "하늘길",
        "초록빛", "파란빛", "노란빛", "보랏빛", "주황빛", "분홍빛", "하얀빛", "검은빛", "회색빛",
        "봄바람", "여름비", "겨울눈", "첫눈길", "소나기", "장마비", "은하수", "무지개", "반딧불",
        "갈매기", "참새떼", "나비춤", "개미굴", "토끼굴", "사슴뿔", "고래등",
        "수박씨", "참외향", "딸기밭", "복숭아", "살구꽃", "감귤향", "밤송이",
        "감자꽃", "고구마", "당근밭", "양파향", "마늘쫑", "고추밭", "버섯숲", "호박꽃", "미나리",
        "멸치국", "다시마", "미역국", "새우젓",
        "한강변", "금강산", "설악산", "지리산", "한라산", "백두산", "울릉도", "제주도", "남해안", "서해안",
        "단풍길", "낙엽길", "벚꽃길", "매화향", "국화향", "무궁화", "진달래", "철쭉꽃",
        "보름달", "초승달", "별자리", "태양빛", "녹음길", "황금빛", "장마철", "서리꽃", "안개길", "여름밤",
        // 4자
        "가을하늘", "겨울바다", "봄꽃길", "산들바람", "포도송이", "대추나무",
        "김치찌개", "된장찌개", "조개껍질", "독도바다", "동해바다", "은빛물결", "금빛물결",
        "소나기길", "코스모스", "해바라기", "첫눈내림", "무지개빛",
        "별빛마을", "달빛연못", "숲속마을", "바닷마을", "하늘연못", "햇살마을",
        // 5자
        "소나무숲길", "은하수마을", "무지개마을", "반딧불마을", "달빛소나무", "햇살소나무",
        "별빛소나무", "바람소리길", "산들바람길", "노을마을길", "초록바람길", "파란하늘길",
        "하늘빛마을", "은하수물결", "무지개물결", "황금들판길",
    )

    /** 저장된 코드를 반환하고, 없으면 새로 생성해 저장한다. */
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val idx = prefs.getInt(Prefs.PAIRING_CODE_INDEX, -1)
        if (idx in WORDS.indices) return WORDS[idx]
        val fresh = Random.nextInt(WORDS.size)
        prefs.edit().putInt(Prefs.PAIRING_CODE_INDEX, fresh).apply()
        return WORDS[fresh]
    }

    /** AirPlay에 노출되는 기기 이름: "Airoid [코드]". */
    fun deviceName(context: Context): String = "Airoid ${get(context)}"
}
