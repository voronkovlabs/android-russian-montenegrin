package com.crnogorski.trener.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Можно ли показывать уведомления — одним ответом на всё приложение.
 *
 * Спрашивать разрешение начали с Android 13 (`POST_NOTIFICATIONS`). До неё его
 * **не существует**, и это не мелочь: `checkSelfPermission` про незнакомое
 * разрешение отвечает «запрещено», а не «не нужно.
 *
 * Значит на Android 11 и 12 все три уведомления — вечернее напоминание, «твою
 * жалобу разобрали» и новости версии — замолчали бы **навсегда**. Причём
 * замолчали тихо: код отрабатывает штатно, просто всегда по ветке «разрешения
 * нет», и в отчёте это выглядело бы как «человек не разрешил».
 *
 * Ровно тот вид поломки, которого проект боится больше прочих: невидимой и
 * отложенной.
 *
 * Мест, где это спрашивают, четыре, и поэтому ответ здесь один: четыре копии
 * одной проверки версии — это четыре места, где её однажды поправят порознь.
 */
fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
