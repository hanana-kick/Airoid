package com.airoid

import android.content.Context
import java.util.Locale
import kotlin.random.Random

/**
 * 대기 화면에 표시되는 페어링용 코드(유명 문학 작품 제목, 2~6자).
 * 기기 언어에 맞는 목록(한국어/영어/일본어/중국어)에서 뽑는다.
 * 최초 접근 시 한 번 생성되어 SharedPreferences에 저장되며, 이후 재실행해도 같은 코드를 유지한다.
 * AirPlay 기기 이름 "Airoid [코드]"와 대기 화면 표시에 함께 쓰인다.
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

    /** 영어 — 영미 문학 (한국어 표기) */
    private val ENGLISH = listOf(
        "모비딕", "노인과바다", "오만과편견", "위대한유산", "폭풍의언덕", "제인에어",
        "셜록홈즈", "드라큘라", "타임머신", "동물농장", "파리대왕", "보물섬",
        "개츠비", "톰소여", "피터팬", "앨리스", "아이반호", "반지의제왕", "호빗",
        "해리포터", "엠마", "테스", "주홍글자", "멋진신세계", "도리언그레이",
        "프랑켄슈타인", "걸리버여행기", "로빈슨크루소", "올리버트위스트",
        "오즈의마법사", "안네의일기", "햄릿", "다빈치코드", "천사와악마",
        "정글북", "나니아", "우주전쟁", "투명인간", "작은아씨들", "비밀의화원",
        "소공녀", "잃어버린세계", "원더",
    )

    /** 일본어 — 일본 문학 (한국어 표기) */
    private val JAPANESE = listOf(
        "인간실격", "금각사", "가면의고백", "풍요의바다", "라쇼몽", "덤불속",
        "지옥변", "치인의사랑", "산소리", "어떤여자", "상실의시대", "해변의카프카",
        "개인적인체험", "도련님", "백야행", "설국", "마음", "사양", "침묵",
        "화차", "비밀", "키친", "도쿄타워", "센바즈루", "투우", "세설", "열쇠",
        "이즈의무희", "무희", "다카세부네", "바다와독약", "은하철도의밤",
    )

    /** 중국어 — 중국 문학 (한국어 표기) */
    private val CHINESE = listOf(
        "삼국지", "수호전", "서유기", "홍루몽", "금병매", "유림외사", "아Q정전",
        "광인일기", "낙타상자", "사조영웅전", "신조협려", "의천도룡기", "녹정기",
        "소오강호", "천룡팔부", "경세통언", "동주열국지", "벽혈검", "비호외전",
        "설산비호", "연성결", "서검은구록", "백발마녀전", "고향", "약", "축복",
        "공을기", "초한지", "유성호접검", "천잠변", "대당쌍룡전",
    )

    /** 기기 언어에 맞는 코드 목록. 기본은 한국어. */
    private fun listFor(locale: Locale): List<String> = when (locale.language) {
        "en" -> ENGLISH
        "ja" -> JAPANESE
        "zh" -> CHINESE
        else -> KOREAN
    }

    /** 저장된 코드를 반환하고, 없으면 현재 언어 목록에서 새로 생성해 저장한다. */
    fun get(context: Context): String {
        val words = listFor(Locale.getDefault())
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val idx = prefs.getInt(Prefs.PAIRING_CODE_INDEX, -1)
        if (idx in words.indices) return words[idx]
        val fresh = Random.nextInt(words.size)
        prefs.edit().putInt(Prefs.PAIRING_CODE_INDEX, fresh).apply()
        return words[fresh]
    }

    /** AirPlay에 노출되는 기기 이름: "Airoid [코드]". */
    fun deviceName(context: Context): String = "Airoid ${get(context)}"
}
