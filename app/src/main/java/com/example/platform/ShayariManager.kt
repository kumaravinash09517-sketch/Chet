package com.example.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DailyShayari(
    val id: Int,
    val mood: String,
    val lines: String
)

object ShayariManager {
    private const val PREFS_NAME = "hello_sir_shayari_prefs"
    private const val KEY_OPEN_COUNT = "open_count"
    private const val KEY_LAST_INDEX = "last_index"
    private const val KEY_LAST_NOTIFIED_DATE = "last_notified_date"

    val shayariCollection: List<DailyShayari> = listOf(
        DailyShayari(
            id = 1,
            mood = "नई सुबह",
            lines = "सुबह की पहली किरण के साथ मुस्कान रखिए,\nजो दिल के करीब हैं उनका थोड़ा ध्यान रखिए।"
        ),
        DailyShayari(
            id = 2,
            mood = "अपनापन",
            lines = "लफ़्ज़ छोटे सही पर एहसास गहरे होने चाहिए,\nरिश्तों में दिखावे नहीं, बस दिल के पहरे होने चाहिए।"
        ),
        DailyShayari(
            id = 3,
            mood = "हौसला",
            lines = "रास्ते खुद ब खुद आसान लगने लगते हैं,\nजब अपनों के साथ कदम मिलाए जाते हैं।"
        ),
        DailyShayari(
            id = 4,
            mood = "दोस्ती",
            lines = "वक्त चाहे कितना भी तेज़ क्यों न भागे,\nसच्चे दोस्त हमेशा दिल के पास ही रहते हैं।"
        ),
        DailyShayari(
            id = 5,
            mood = "मुस्कान",
            lines = "थोड़ी सी धूप, एक कप चाय और अपनों की बात,\nबस इतना ही काफी है खूबसूरत बनाने को हर प्रभात।"
        ),
        DailyShayari(
            id = 6,
            mood = "सुकून",
            lines = "शोर से दूर कहीं सुकून की बात लिखते हैं,\nचलो आज फिर किसी अपने को प्यारी सी याद लिखते हैं।"
        ),
        DailyShayari(
            id = 7,
            mood = "उम्मीद",
            lines = "हर नया दिन एक कोरा पन्ना लेकर आता है,\nअच्छे कर्मों से उस पर सुंदर किस्सा लिखा जाता है।"
        ),
        DailyShayari(
            id = 8,
            mood = "साथ",
            lines = "बातें कम हों तो भी फर्क नहीं पड़ता,\nबस दिल में एक-दूसरे के लिए सम्मान होना चाहिए।"
        ),
        DailyShayari(
            id = 9,
            mood = "खुशी",
            lines = "खुशियाँ बड़ी चीज़ों में नहीं, छोटे लम्हों में मिलती हैं,\nजैसे किसी पुराने दोस्त का अचानक आया हुआ संदेश।"
        ),
        DailyShayari(
            id = 10,
            mood = "साहस",
            lines = "मंज़िलें उन्हें ही मिलती हैं जो चलना नहीं छोड़ते,\nछोटी-छोटी मुश्किलों से जो अपने हौसले नहीं तोड़ते।"
        ),
        DailyShayari(
            id = 11,
            mood = "प्रभात",
            lines = "ताज़ी हवाओं का पैगाम लेकर आई है सुबह,\nदिल से दुआ है कि आज आपका हर काम बन जाए।"
        ),
        DailyShayari(
            id = 12,
            mood = "यादें",
            lines = "कुछ लोग दूर रहकर भी पास महसूस होते हैं,\nउनकी एक आवाज़ से ही मन के बोझ हल्के होते हैं।"
        ),
        DailyShayari(
            id = 13,
            mood = "सफलता",
            lines = "मेहनत की स्याही से जो अपनी कहानी लिखते हैं,\nएक दिन उन्हीं के नाम की चर्चा हर महफ़िल में होती है।"
        ),
        DailyShayari(
            id = 14,
            mood = "सकारात्मकता",
            lines = "अंधेरा चाहे कितना भी गहरा क्यों न लगे,\nसुबह का उजाला आने से कोई रोक नहीं सकता।"
        ),
        DailyShayari(
            id = 15,
            mood = "विश्वास",
            lines = "मीठे बोल और साफ नीयत इंसान को बड़ा बनाते हैं,\nवरना दौलत तो हर किसी के हिस्से में आती-जाती रहती है।"
        ),
        DailyShayari(
            id = 16,
            mood = "सफर",
            lines = "जिंदगी का सफर तब और खूबसूरत हो जाता है,\nजब साथ चलने वाला बिना कहे मन की बात समझ जाता है।"
        ),
        DailyShayari(
            id = 17,
            mood = "दुआ",
            lines = "हर सुबह बस इतनी सी दुआ करते हैं,\nआपके चेहरे की मुस्कान हमेशा सलामत रहे।"
        ),
        DailyShayari(
            id = 18,
            mood = "शांति",
            lines = "मन को शांत रखिए और दिल को साफ,\nजिंदगी खुद ब खुद आसान लगने लगेगी आज।"
        ),
        DailyShayari(
            id = 19,
            mood = "मेहनत",
            lines = "सपने वो नहीं जो नींद में दिखते हैं,\nसपने वो हैं जो सुबह जल्दी जगा देते हैं।"
        ),
        DailyShayari(
            id = 20,
            mood = "प्यार और सम्मान",
            lines = "जहाँ कदर होती है वहाँ रिश्ते खुद निखरते हैं,\nजहाँ भरोसा हो वहाँ फासले भी पास लगते हैं।"
        ),
        DailyShayari(
            id = 21,
            mood = "प्रेरणा",
            lines = "हर छोटा कदम आपको आगे ले जाता है,\nबस रुकने के बजाय चलते रहना सिखाइए खुद को।"
        ),
        DailyShayari(
            id = 22,
            mood = "चाय और बात",
            lines = "सुबह की चाय में थोड़ी मिठास और घोल लीजिए,\nजिन्हें याद किया है आज, उन्हें प्यार से 'हैलो' बोल दीजिए।"
        ),
        DailyShayari(
            id = 23,
            mood = "दिल की बात",
            lines = "शब्द कम पड़ जाएं तो खामोशी भी बोलती है,\nसच्ची भावनाएँ हमेशा दिल का दरवाज़ा खोलती हैं।"
        ),
        DailyShayari(
            id = 24,
            mood = "आभार",
            lines = "जो मिला है उसका शुक्र अदा कीजिए,\nऔर जो चाहत है उसके लिए हंसकर प्रयास कीजिए।"
        ),
        DailyShayari(
            id = 25,
            mood = "वक्त",
            lines = "अच्छा वक्त आए तो विनम्र रहिए,\nमुश्किल वक्त आए तो धैर्य बनाए रखिए।"
        ),
        DailyShayari(
            id = 26,
            mood = "मिलन",
            lines = "एक छोटा सा संदेश भी दिन बना देता है,\nजब वह किसी खास के नाम से स्क्रीन पर आता है।"
        ),
        DailyShayari(
            id = 27,
            mood = "रौशनी",
            lines = "खुद पर भरोसा रखिए जनाब,\nआपकी मुस्कान भी किसी के लिए रौशनी बन सकती है।"
        ),
        DailyShayari(
            id = 28,
            mood = "नया आगाज़",
            lines = "बीते कल की चिंता छोड़ आज को गले लगाइए,\nएक नई उम्मीद के साथ इस सुबह को सजाइए।"
        )
    )

    fun nextShayariForAppOpen(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): DailyShayari {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openCount = prefs.getInt(KEY_OPEN_COUNT, 0) + 1
        val lastIndex = prefs.getInt(KEY_LAST_INDEX, -1)
        val dayOffset = Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.DAY_OF_YEAR)
        var candidate = (dayOffset + openCount) % shayariCollection.size
        if (candidate == lastIndex) {
            candidate = (candidate + 1) % shayariCollection.size
        }
        prefs.edit()
            .putInt(KEY_OPEN_COUNT, openCount)
            .putInt(KEY_LAST_INDEX, candidate)
            .apply()
        return shayariCollection[candidate]
    }

    fun getShayariAfterIndex(currentId: Int): DailyShayari {
        val currentIdx = shayariCollection.indexOfFirst { it.id == currentId }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % shayariCollection.size
        return shayariCollection[nextIdx]
    }

    fun shayariForCalendarDay(nowMillis: Long = System.currentTimeMillis()): DailyShayari {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val idx = (cal.get(Calendar.YEAR) * 366 + cal.get(Calendar.DAY_OF_YEAR)) % shayariCollection.size
        return shayariCollection[idx]
    }

    fun shouldSendMorningNotificationToday(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
        val lastDate = prefs.getString(KEY_LAST_NOTIFIED_DATE, null)
        return lastDate != todayKey
    }

    fun markMorningNotificationSent(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
        prefs.edit().putString(KEY_LAST_NOTIFIED_DATE, todayKey).apply()
    }

    fun scheduleDailyMorningNotification(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, MorningShayariReceiver::class.java).apply {
            action = MorningShayariReceiver.ACTION_MORNING_SHAYARI
        }
        val pending = PendingIntent.getBroadcast(
            context,
            4011,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextMorning = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextMorning.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pending
        )
    }
}
