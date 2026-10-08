package com.notificationhistory.data

/**
 * Причины исчезновения уведомления. Числа совпадают с константами
 * NotificationListenerService.REASON_*; часть из них появилась в поздних API,
 * поэтому держим свои копии, чтобы не зависеть от уровня API.
 */
object RemovalReason {
    /** Наша собственная: исчезло, пока сервис не работал (обнаружено при переподключении). */
    const val UNKNOWN = 0
    const val CLICK = 1
    const val CANCEL = 2
    const val CANCEL_ALL = 3
    const val ERROR = 4
    const val PACKAGE_CHANGED = 5
    const val USER_STOPPED = 6
    const val PACKAGE_BANNED = 7
    const val APP_CANCEL = 8
    const val APP_CANCEL_ALL = 9
    const val LISTENER_CANCEL = 10
    const val LISTENER_CANCEL_ALL = 11
    const val GROUP_SUMMARY_CANCELED = 12
    const val GROUP_OPTIMIZATION = 13
    const val PACKAGE_SUSPENDED = 14
    const val PROFILE_TURNED_OFF = 15
    const val UNAUTOBUNDLED = 16
    const val CHANNEL_BANNED = 17
    const val SNOOZED = 18
    const val TIMEOUT = 19
    const val CHANNEL_REMOVED = 20
    const val CLEAR_DATA = 21
    const val ASSISTANT_CANCEL = 22
    const val LOCKDOWN = 23

    /** Наша собственная: содержимое заменено новой версией. */
    const val REPLACED = -1

    enum class Kind { USER_DISMISSED, OPENED, APP_REMOVED, SYSTEM, REPLACED }

    fun kind(reason: Int?): Kind = when (reason) {
        CANCEL, CANCEL_ALL -> Kind.USER_DISMISSED
        CLICK -> Kind.OPENED
        APP_CANCEL, APP_CANCEL_ALL, GROUP_SUMMARY_CANCELED -> Kind.APP_REMOVED
        REPLACED -> Kind.REPLACED
        else -> Kind.SYSTEM
    }

    fun shortLabel(reason: Int?): String = when (reason) {
        CLICK -> "Открыто"
        CANCEL -> "Смахнуто"
        CANCEL_ALL -> "Очищено"
        APP_CANCEL, APP_CANCEL_ALL -> "Удалено приложением"
        GROUP_SUMMARY_CANCELED -> "Удалена группа"
        TIMEOUT -> "Истёк срок"
        SNOOZED -> "Отложено"
        REPLACED -> "Обновлено"
        UNKNOWN, null -> "Исчезло"
        else -> "Удалено системой"
    }

    fun description(reason: Int?): String = when (reason) {
        CLICK -> "Пользователь нажал на уведомление и открыл его"
        CANCEL -> "Пользователь смахнул уведомление"
        CANCEL_ALL -> "Пользователь нажал «Очистить всё»"
        ERROR -> "Ошибка отображения уведомления"
        PACKAGE_CHANGED -> "Приложение было обновлено или отключено"
        USER_STOPPED -> "Приложение принудительно остановлено"
        PACKAGE_BANNED -> "Уведомления приложения запрещены"
        APP_CANCEL -> "Приложение само убрало уведомление (например, сообщение удалено или прочитано на другом устройстве)"
        APP_CANCEL_ALL -> "Приложение убрало все свои уведомления"
        LISTENER_CANCEL, LISTENER_CANCEL_ALL -> "Уведомление убрано другим приложением с доступом к уведомлениям"
        GROUP_SUMMARY_CANCELED -> "Удалена вся группа уведомлений"
        GROUP_OPTIMIZATION -> "Система перегруппировала уведомления"
        PACKAGE_SUSPENDED -> "Приложение приостановлено"
        PROFILE_TURNED_OFF -> "Рабочий профиль выключен"
        UNAUTOBUNDLED -> "Система разгруппировала уведомления"
        CHANNEL_BANNED -> "Категория уведомлений отключена"
        SNOOZED -> "Уведомление отложено"
        TIMEOUT -> "Истёк срок показа, заданный приложением"
        CHANNEL_REMOVED -> "Категория уведомлений удалена приложением"
        CLEAR_DATA -> "Данные приложения очищены"
        ASSISTANT_CANCEL -> "Убрано системным помощником уведомлений"
        LOCKDOWN -> "Устройство в режиме блокировки"
        REPLACED -> "Приложение обновило уведомление — новая версия сохранена отдельно"
        else -> "Уведомление исчезло, пока история не работала — причина неизвестна"
    }
}
